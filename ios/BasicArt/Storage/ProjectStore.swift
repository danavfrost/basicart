import CryptoKit
import Foundation
import ImageIO
import UniformTypeIdentifiers

enum ProjectStatus: Equatable {
    case ok
    case newerVersion
    case corrupt
}

struct ProjectSummary: Identifiable, Equatable {
    var id: String
    var folder: URL
    var name: String
    var modified: Date
    var status: ProjectStatus
    var thumbURL: URL { folder.appendingPathComponent("thumb.png") }
    var hasThumb: Bool
}

enum StoreError: LocalizedError {
    case io(String)
    var errorDescription: String? {
        switch self { case .io(let s): return s }
    }
}

/// File-system access for projects. All methods are synchronous and thread-safe
/// (callers run them off the main thread).
final class ProjectStore {
    static let shared = ProjectStore()

    let root: URL
    private let fm = FileManager.default

    init(root: URL? = nil) {
        if let root {
            self.root = root
        } else {
            let support = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                       appropriateFor: nil, create: true))
                ?? FileManager.default.temporaryDirectory
            self.root = support.appendingPathComponent("projects", isDirectory: true)
        }
        try? fm.createDirectory(at: self.root, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = false
        var r = self.root
        try? r.setResourceValues(values)
    }

    func folder(for id: String) -> URL { root.appendingPathComponent(id, isDirectory: true) }

    // MARK: Listing

    func listProjects() -> [ProjectSummary] {
        guard let items = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey, .contentModificationDateKey],
                                                      options: [.skipsHiddenFiles]) else { return [] }
        var out: [ProjectSummary] = []
        for url in items {
            guard (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true else { continue }
            out.append(summary(folder: url))
        }
        return out.sorted { $0.modified > $1.modified }
    }

    func summary(folder: URL) -> ProjectSummary {
        let id = folder.lastPathComponent
        let jsonURL = folder.appendingPathComponent("project.json")
        let mdate = (try? jsonURL.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)
            ?? (try? folder.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
        let hasThumb = fm.fileExists(atPath: folder.appendingPathComponent("thumb.png").path)
        guard let data = try? Data(contentsOf: jsonURL) else {
            return ProjectSummary(id: id, folder: folder, name: "Damaged project", modified: mdate, status: .corrupt, hasThumb: hasThumb)
        }
        switch ProjectCodec.decode(data, folderID: id, fileDate: mdate) {
        case .success(let doc):
            return ProjectSummary(id: id, folder: folder, name: doc.name, modified: doc.modified, status: .ok, hasThumb: hasThumb)
        case .failure(.newerVersion(let name)):
            return ProjectSummary(id: id, folder: folder, name: name ?? "Untitled", modified: mdate, status: .newerVersion, hasThumb: hasThumb)
        case .failure(.corrupt):
            return ProjectSummary(id: id, folder: folder, name: "Damaged project", modified: mdate, status: .corrupt, hasThumb: hasThumb)
        }
    }

    // MARK: Loading

    /// Loads a project folder. Never modifies the folder.
    func load(folder: URL) -> Result<Document, LoadFailure> {
        let jsonURL = folder.appendingPathComponent("project.json")
        guard let data = try? Data(contentsOf: jsonURL) else { return .failure(.corrupt("project.json missing")) }
        let mdate = (try? jsonURL.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date()
        switch ProjectCodec.decode(data, folderID: folder.lastPathComponent, fileDate: mdate) {
        case .failure(let f): return .failure(f)
        case .success(var doc):
            for i in doc.layers.indices where doc.layers[i].type != .drawing {
                let url = folder.appendingPathComponent("strokes/\(doc.layers[i].id).mask.json")
                if let mdata = try? Data(contentsOf: url) {
                    if let m = ProjectCodec.decodeMask(mdata) {
                        doc.layers[i].mask = m
                        doc.layers[i].maskFileState = .ok
                    } else {
                        doc.layers[i].maskFileState = .invalid
                    }
                }
            }
            for i in doc.layers.indices {
                guard case .drawing(var d) = doc.layers[i].content else { continue }
                let url = folder.appendingPathComponent("strokes/\(doc.layers[i].id).json")
                if let sdata = try? Data(contentsOf: url) {
                    if let strokes = ProjectCodec.decodeStrokes(sdata) {
                        d.strokes = strokes
                        d.fileState = .ok
                    } else {
                        d.strokes = []
                        d.fileState = .invalid
                    }
                } else {
                    d.fileState = .missing
                }
                doc.layers[i].content = .drawing(d)
            }
            return .success(doc)
        }
    }

    func load(id: String) -> Result<Document, LoadFailure> { load(folder: folder(for: id)) }

    /// Deletes leftover *.tmp files (call when a project is opened for editing).
    func cleanupTemporaryFiles(id: String) {
        let folder = folder(for: id)
        guard let e = fm.enumerator(at: folder, includingPropertiesForKeys: nil) else { return }
        for case let url as URL in e where url.pathExtension == "tmp" {
            try? fm.removeItem(at: url)
        }
    }

    // MARK: Saving

    /// Writes project.json and changed strokes atomically. `writtenStrokes` tracks
    /// strokes already on disk (per layer id) to skip unchanged files.
    func save(_ doc: Document, writtenStrokes: inout [String: [Stroke]]) throws {
        let folder = folder(for: doc.id)
        try fm.createDirectory(at: folder.appendingPathComponent("assets"), withIntermediateDirectories: true)
        let strokesDir = folder.appendingPathComponent("strokes")
        try fm.createDirectory(at: strokesDir, withIntermediateDirectories: true)
        for layer in doc.layers {
            guard case .drawing(let d) = layer.content else { continue }
            let url = strokesDir.appendingPathComponent("\(layer.id).json")
            if d.fileState == .invalid, writtenStrokes[layer.id] == nil, fm.fileExists(atPath: url.path) {
                let bad = strokesDir.appendingPathComponent("\(layer.id).json.corrupt")
                try? fm.removeItem(at: bad)
                try? fm.moveItem(at: url, to: bad)
            }
            if let prev = writtenStrokes[layer.id], prev == d.strokes, fm.fileExists(atPath: url.path) { continue }
            if writtenStrokes[layer.id] == nil, d.fileState == .missing, d.strokes.isEmpty { writtenStrokes[layer.id] = []; continue }
            try atomicWrite(ProjectCodec.encodeStrokes(layerID: layer.id, strokes: d.strokes), to: url)
            writtenStrokes[layer.id] = d.strokes
        }
        // Eraser masks of image/text/shape layers (§9.1).
        for layer in doc.layers where layer.type != .drawing {
            let key = "mask:\(layer.id)"
            let url = strokesDir.appendingPathComponent("\(layer.id).mask.json")
            if layer.maskFileState == .invalid, writtenStrokes[key] == nil, fm.fileExists(atPath: url.path) {
                let bad = strokesDir.appendingPathComponent("\(layer.id).mask.json.corrupt")
                try? fm.removeItem(at: bad)
                try? fm.moveItem(at: url, to: bad)
            }
            if layer.mask.isEmpty {
                if writtenStrokes[key] != nil || layer.maskFileState == .ok {
                    if fm.fileExists(atPath: url.path) { try? fm.removeItem(at: url) }
                }
                writtenStrokes[key] = []
                continue
            }
            if let prev = writtenStrokes[key], prev == layer.mask, fm.fileExists(atPath: url.path) { continue }
            try atomicWrite(ProjectCodec.encodeStrokes(layerID: layer.id, strokes: layer.mask), to: url)
            writtenStrokes[key] = layer.mask
        }
        try atomicWrite(ProjectCodec.encode(doc), to: folder.appendingPathComponent("project.json"))
    }

    func writeThumbnail(_ pngData: Data, id: String) {
        try? atomicWrite(pngData, to: folder(for: id).appendingPathComponent("thumb.png"))
    }

    // MARK: Assets

    /// Stores image bytes as assets/<sha256>.<ext> (dedup). Returns the file name.
    func storeAsset(_ data: Data, ext: String, projectID: String) throws -> String {
        let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let name = "\(hash).\(ext)"
        let dir = folder(for: projectID).appendingPathComponent("assets")
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(name)
        if !fm.fileExists(atPath: url.path) {
            try atomicWrite(data, to: url)
        }
        return name
    }

    func assetURL(_ ref: String, projectID: String) -> URL {
        folder(for: projectID).appendingPathComponent("assets").appendingPathComponent(ref)
    }

    /// Removes assets and strokes files not referenced by any of `docs` (call when the editor closes).
    func collectGarbage(projectID: String, keeping docs: [Document]) {
        var assets = Set<String>(), drawings = Set<String>(), masked = Set<String>()
        for d in docs {
            for l in d.layers {
                if case .image(let p) = l.content { assets.insert(p.assetRef) }
                if case .drawing = l.content { drawings.insert(l.id) } else if !l.mask.isEmpty { masked.insert(l.id) }
            }
        }
        let folder = folder(for: projectID)
        if let items = try? fm.contentsOfDirectory(atPath: folder.appendingPathComponent("assets").path) {
            for f in items where !assets.contains(f) && !f.hasSuffix(".tmp") {
                try? fm.removeItem(at: folder.appendingPathComponent("assets/\(f)"))
            }
        }
        if let items = try? fm.contentsOfDirectory(atPath: folder.appendingPathComponent("strokes").path) {
            for f in items where f.hasSuffix(".mask.json") && !masked.contains(String(f.dropLast(10))) {
                try? fm.removeItem(at: folder.appendingPathComponent("strokes/\(f)"))
            }
            for f in items where f.hasSuffix(".json") && !f.hasSuffix(".mask.json") && !drawings.contains(String(f.dropLast(5))) {
                try? fm.removeItem(at: folder.appendingPathComponent("strokes/\(f)"))
            }
        }
    }

    // MARK: Project management

    func delete(id: String) throws {
        let f = folder(for: id)
        guard f.deletingLastPathComponent().standardizedFileURL == root.standardizedFileURL else { return }
        try fm.removeItem(at: f)
    }

    func deleteAll() throws {
        let items = try fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
        for url in items { try fm.removeItem(at: url) }
    }

    func rename(id: String, to name: String) throws {
        guard case .success(var doc) = load(id: id) else { throw StoreError.io("Can't open this project") }
        doc.name = name
        doc.modified = Date()
        var written: [String: [Stroke]] = [:]
        for l in doc.layers {
            if case .drawing(let d) = l.content { written[l.id] = d.strokes } else if l.maskFileState == .ok { written["mask:\(l.id)"] = l.mask }
        }
        try save(doc, writtenStrokes: &written)
    }

    /// Copies a project folder under a new id with " copy" appended to the name.
    @discardableResult
    func duplicate(id: String) throws -> String {
        guard case .success(var doc) = load(id: id) else { throw StoreError.io("Can't open this project") }
        let newID = Document.newID()
        let src = folder(for: id), dst = folder(for: newID)
        let tmp = root.appendingPathComponent(".\(newID).tmpdir")
        try? fm.removeItem(at: tmp)
        try fm.copyItem(at: src, to: tmp)
        // Drop leftovers and the json; rewrite json with new id/name.
        if let e = fm.enumerator(at: tmp, includingPropertiesForKeys: nil) {
            for case let url as URL in e where url.pathExtension == "tmp" { try? fm.removeItem(at: url) }
        }
        doc.id = newID
        doc.name = Document.copyName(doc.name)
        doc.created = Date()
        doc.modified = Date()
        try atomicWrite(ProjectCodec.encode(doc), to: tmp.appendingPathComponent("project.json"))
        try fm.moveItem(at: tmp, to: dst)
        return newID
    }

    // MARK: Atomic write

    func atomicWrite(_ data: Data, to url: URL) throws {
        let tmp = url.appendingPathExtension("tmp")
        try? fm.removeItem(at: tmp)
        guard fm.createFile(atPath: tmp.path, contents: nil) else { throw StoreError.io("Can't write \(url.lastPathComponent)") }
        let h = try FileHandle(forWritingTo: tmp)
        do {
            try h.write(contentsOf: data)
            try h.synchronize()
            try h.close()
        } catch {
            try? h.close()
            try? fm.removeItem(at: tmp)
            throw error
        }
        if Darwin.rename(tmp.path, url.path) != 0 {
            let err = String(cString: strerror(errno))
            try? fm.removeItem(at: tmp)
            throw StoreError.io("Rename failed: \(err)")
        }
    }
}

import CryptoKit
import Foundation

// MARK: - Editor state (FORMAT.md §10.7)

struct EditorState: Equatable {
    var selectedLayerIds: [String] = []
    var activeTool: EditorTool?
    var textTab: TextTab?
    var zoom: Double?          // multiple of fit-to-screen
    var centerX: Double?
    var centerY: Double?
    var textSelection: (layerId: String, start: Int, end: Int)?

    static func == (a: EditorState, b: EditorState) -> Bool {
        a.selectedLayerIds == b.selectedLayerIds && a.activeTool == b.activeTool && a.textTab == b.textTab &&
            a.zoom == b.zoom && a.centerX == b.centerX && a.centerY == b.centerY &&
            a.textSelection?.layerId == b.textSelection?.layerId && a.textSelection?.start == b.textSelection?.start &&
            a.textSelection?.end == b.textSelection?.end
    }

    /// Lenient parse: every field is optional; invalid values are dropped one by one.
    static func decode(_ data: Data) -> EditorState? {
        guard let o = (try? JSONValue.parse(data))?.objectValue else { return nil }
        var s = EditorState()
        if let ids = o["selectedLayerIds"]?.arrayValue { s.selectedLayerIds = ids.compactMap { $0.stringValue } }
        s.activeTool = o["activeTool"]?.stringValue.flatMap(EditorTool.init(rawValue:))
        s.textTab = o["textTab"]?.stringValue.flatMap(TextTab.init(rawValue:))
        if let v = o["view"]?.objectValue {
            if let z = v["zoom"]?.doubleValue, z >= 0.1, z <= 32 { s.zoom = z }
            if let x = v["centerX"]?.doubleValue, x.isFinite { s.centerX = x }
            if let y = v["centerY"]?.doubleValue, y.isFinite { s.centerY = y }
        }
        if let t = o["textSelection"]?.objectValue, let id = t["layerId"]?.stringValue,
           let a = t["start"]?.intValue, let b = t["end"]?.intValue, a >= 0, b >= a {
            s.textSelection = (id, a, b)
        }
        return s
    }

    func encode() -> Data {
        var o = JSONObject()
        o["selectedLayerIds"] = .array(selectedLayerIds.map { .string($0) })
        if let t = activeTool { o["activeTool"] = .string(t.rawValue) }
        if let t = textTab { o["textTab"] = .string(t.rawValue) }
        if let z = zoom, let x = centerX, let y = centerY {
            o["view"] = .object(JSONObject([("zoom", .num(z)), ("centerX", .num(x)), ("centerY", .num(y))]))
        }
        if let ts = textSelection {
            o["textSelection"] = .object(JSONObject([("layerId", .string(ts.layerId)), ("start", .int(ts.start)), ("end", .int(ts.end))]))
        }
        return JSONValue.object(o).serialized()
    }

    /// The text selection, if it is valid for the document (text layer, grapheme boundaries).
    func validTextSelection(in doc: Document) -> (String, NSRange)? {
        guard let ts = textSelection, let t = doc.layer(ts.layerId)?.text else { return nil }
        let b = Spans.graphemeBoundaries(t.text)
        guard b.contains(ts.start), b.contains(ts.end) else { return nil }
        return (ts.layerId, NSRange(location: ts.start, length: ts.end - ts.start))
    }
}

extension ProjectStore {
    func editorStateURL(_ id: String) -> URL { folder(for: id).appendingPathComponent("editor-state.json") }

    func loadEditorState(id: String) -> EditorState? {
        (try? Data(contentsOf: editorStateURL(id))).flatMap(EditorState.decode)
    }

    func saveEditorState(_ s: EditorState, id: String) {
        try? atomicWrite(s.encode(), to: editorStateURL(id))
    }
}

// MARK: - Package (FORMAT.md §13)

enum PackageRejection: String, Error, CaseIterable {
    case corruptZip, unsafePath, symlink, duplicateEntry, unexpectedEntry, encrypted
    case tooManyEntries, tooLarge, compressionRatio, notAPackage, newerVersion, corruptProject, ioError

    /// Friendly, non-technical message per reason.
    var message: String {
        switch self {
        case .corruptZip: return "This file is damaged or isn't a zip file."
        case .notAPackage: return "This zip isn't a Basic Art project file."
        case .newerVersion: return "This project was made with a newer version of Basic Art. Update the app to import it."
        case .corruptProject: return "The project inside this file is damaged and can't be opened."
        case .tooLarge: return "This project file is too large to import."
        case .compressionRatio, .tooManyEntries: return "This file looks unsafe (it expands far beyond its size), so it wasn't imported."
        case .unsafePath, .symlink, .duplicateEntry, .unexpectedEntry, .encrypted:
            return "This file contains unexpected or unsafe contents, so it wasn't imported."
        case .ioError: return "Basic Art couldn't read or save this file. Try again."
        }
    }
}

enum ProjectPackage {
    static let maxEntries = 10_000
    static let maxTotal: UInt64 = 1 << 30
    static let maxEntry: UInt64 = 256 << 20
    static let ratioMin: UInt64 = 1 << 20
    static let maxRatio: UInt64 = 100

    private static let assetRe = try! NSRegularExpression(pattern: "^assets/[0-9a-f]{64}\\.(png|jpg|webp|gif|heic)$")
    private static let strokeRe = try! NSRegularExpression(pattern: "^strokes/[A-Za-z0-9_-]{1,64}(\\.mask)?\\.json$")
    private static let fixed: Set<String> = ["basicart-package.json", "project.json", "thumb.png", "editor-state.json", "assets/", "strokes/"]

    static func isJunk(_ name: String) -> Bool {
        name.hasPrefix("__MACOSX/") || name == ".DS_Store" || name.hasSuffix("/.DS_Store")
    }

    private static func matches(_ re: NSRegularExpression, _ s: String) -> Bool {
        re.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil
    }

    /// Central-directory checks before extracting anything (§13.2 step 2). Returns every reason.
    static func scan(_ entries: [ZipEntry]) -> [PackageRejection] {
        var reasons = Set<PackageRejection>()
        if entries.count > maxEntries { reasons.insert(.tooManyEntries) }
        var seen = Set<String>()
        var total: UInt64 = 0
        for e in entries {
            let n = e.name
            let trimmed = n.hasSuffix("/") ? String(n.dropLast()) : n
            let segs = trimmed.split(separator: "/", omittingEmptySubsequences: false)
            let drive = n.count >= 2 && n[n.index(after: n.startIndex)] == ":" && n.first!.isLetter
            if n.hasPrefix("/") || n.contains("\\") || drive || n.contains("\u{0}") || segs.contains("..") || segs.contains("") {
                reasons.insert(.unsafePath)
            }
            if e.isSymlink { reasons.insert(.symlink) }
            let key = trimmed.lowercased()
            if !seen.insert(key).inserted { reasons.insert(.duplicateEntry) }
            if e.isEncrypted { reasons.insert(.encrypted) }
            if !isJunk(n) && !fixed.contains(n) && !matches(assetRe, n) && !matches(strokeRe, n) {
                reasons.insert(.unexpectedEntry)
            }
            total += e.uncompressedSize
            if e.uncompressedSize > maxEntry { reasons.insert(.tooLarge) }
            if e.uncompressedSize >= ratioMin && e.uncompressedSize > maxRatio * max(e.compressedSize, 1) {
                reasons.insert(.compressionRatio)
            }
        }
        if total > maxTotal { reasons.insert(.tooLarge) }
        return reasons.sorted { $0.rawValue < $1.rawValue }
    }

    struct ImportResult {
        var projectID: String
        var name: String
    }

    /// Imports a package as a brand-new project (§13.2). Nothing is left behind on failure.
    static func importPackage(at zipURL: URL, store: ProjectStore = .shared) -> Result<ImportResult, PackageRejection> {
        let fm = FileManager.default
        // 1. Copy the incoming file to a private temp file.
        let tmpZip = fm.temporaryDirectory.appendingPathComponent("import-\(UUID().uuidString).zip")
        defer { try? fm.removeItem(at: tmpZip) }
        do { try fm.copyItem(at: zipURL, to: tmpZip) } catch { return .failure(.ioError) }
        let reader: ZipReader
        do { reader = try ZipReader(url: tmpZip) } catch { return .failure(.corruptZip) }
        // 2. Central directory checks.
        if let first = scan(reader.entries).first { return .failure(first) }
        // 3. Manifest.
        guard let manEntry = reader.entries.first(where: { $0.name == "basicart-package.json" }),
              let manData = try? reader.data(manEntry, max: 1 << 20),
              let man = (try? JSONValue.parse(manData))?.objectValue,
              man["package"]?.stringValue == "basicart-project",
              let pv = man["packageVersion"]?.intValue, let fv = man["formatVersion"]?.intValue else {
            return .failure(.notAPackage)
        }
        if pv > 1 || fv > Document.supportedFormatVersion { return .failure(.newerVersion) }
        // 4. Extract into projects/.import-<random>/, counting real bytes.
        let staging = store.root.appendingPathComponent(".import-\(UUID().uuidString.lowercased())", isDirectory: true)
        func fail(_ r: PackageRejection) -> Result<ImportResult, PackageRejection> {
            try? fm.removeItem(at: staging)
            return .failure(r)
        }
        do {
            try fm.createDirectory(at: staging, withIntermediateDirectories: true)
            var totalOut: UInt64 = 0
            for e in reader.entries where !isJunk(e.name) && !e.isDirectory {
                let out = staging.appendingPathComponent(e.name)
                try fm.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
                fm.createFile(atPath: out.path, contents: nil)
                let h = try FileHandle(forWritingTo: out)
                defer { try? h.close() }
                try reader.extract(e, maxBytes: maxEntry, ratioMinBytes: ratioMin, maxRatio: maxRatio) { d in
                    totalOut += UInt64(d.count)
                    if totalOut > maxTotal { throw ZipError.limit("tooLarge") }
                    try h.write(contentsOf: d)
                }
            }
        } catch ZipError.limit(let r) {
            return fail(PackageRejection(rawValue: r) ?? .tooLarge)
        } catch ZipError.corrupt {
            return fail(.corruptZip)
        } catch {
            return fail(.ioError)
        }
        // 5. Classify project.json.
        guard let pdata = try? Data(contentsOf: staging.appendingPathComponent("project.json")) else { return fail(.corruptProject) }
        let decoded = ProjectCodec.decode(pdata, folderID: nil)
        var doc: Document
        switch decoded {
        case .failure(.newerVersion): return fail(.newerVersion)
        case .failure(.corrupt): return fail(.corruptProject)
        case .success(let d): doc = d
        }
        if let raw = (try? JSONValue.parse(pdata))?.objectValue?["formatVersion"]?.intValue, raw != fv { return fail(.corruptProject) }
        // Assets whose bytes don't hash to their name are deleted (they render as missing).
        let assetsDir = staging.appendingPathComponent("assets")
        if let items = try? fm.contentsOfDirectory(atPath: assetsDir.path) {
            for f in items {
                let url = assetsDir.appendingPathComponent(f)
                guard let data = try? Data(contentsOf: url) else { continue }
                let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
                if !f.hasPrefix(hash + ".") { try? fm.removeItem(at: url) }
            }
        }
        // 6. Fresh id, modified = now, unique name. Rewrite only project.json (raw keys
        //    are preserved via our writer; layer ids, created and editor state unchanged).
        let newID = Document.newID()
        doc.id = newID
        doc.modified = Date()
        doc.name = uniqueName(doc.name, store: store)
        do {
            try store.atomicWrite(ProjectCodec.encode(doc), to: staging.appendingPathComponent("project.json"))
        } catch { return fail(.ioError) }
        // 7. Thumbnail if missing, then atomic rename into place.
        let thumb = staging.appendingPathComponent("thumb.png")
        if !fm.fileExists(atPath: thumb.path) {
            let tmpStore = ProjectStore(root: store.root)
            if case .success(let full) = tmpStore.load(folder: staging) {
                var d = full; d.id = staging.lastPathComponent
                let assets = AssetProvider(folder: staging.appendingPathComponent("assets"))
                let scale = min(1, 512 / CGFloat(max(d.canvas.width, d.canvas.height)))
                var o = RenderOptions(); o.imageMaxPixel = 1024
                if let img = Renderer.renderImage(d, scale: scale, assets: assets, options: o), let png = ImageEncoder.png(img) {
                    try? store.atomicWrite(png, to: thumb)
                }
            }
        }
        if Darwin.rename(staging.path, store.folder(for: newID).path) != 0 { return fail(.ioError) }
        return .success(ImportResult(projectID: newID, name: doc.name))
    }

    /// "Name", else "Name (2)", "Name (3)"…
    static func uniqueName(_ name: String, store: ProjectStore) -> String {
        let used = Set(store.listProjects().map(\.name))
        if !used.contains(name) { return name }
        var n = 2
        while used.contains("\(name) (\(n))") { n += 1 }
        return String("\(name) (\(n))".prefix(100))
    }

    /// Deletes leftover projects/.import-* folders (call at launch).
    static func cleanupStaging(store: ProjectStore = .shared) {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(atPath: store.root.path) else { return }
        for f in items where f.hasPrefix(".import-") {
            try? fm.removeItem(at: store.root.appendingPathComponent(f))
        }
    }

    // MARK: Export temp files

    /// Deletes one exported file (project zip or image) once the Files picker or share sheet
    /// is finished with it, along with its private ProjectExport-* folder.
    static func discardExport(_ url: URL?) {
        guard let url else { return }
        let fm = FileManager.default
        let parent = url.deletingLastPathComponent()
        if parent.lastPathComponent.hasPrefix("ProjectExport-") { try? fm.removeItem(at: parent) }
        else { try? fm.removeItem(at: url) }
    }

    /// Removes export/import leftovers in the temp directory (call at launch, off the main thread).
    static func sweepExportTemp(in tmp: URL = FileManager.default.temporaryDirectory) {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(atPath: tmp.path) else { return }
        for f in items where f.hasPrefix("ProjectExport-") || f == "Export" || (f.hasPrefix("import-") && f.hasSuffix(".zip")) {
            try? fm.removeItem(at: tmp.appendingPathComponent(f))
        }
    }

    // MARK: Export

    static func fileName(for projectName: String) -> String {
        let bad = CharacterSet(charactersIn: "/\\:*?\"<>|").union(.controlCharacters)
        var s = String(projectName.unicodeScalars.map { bad.contains($0) ? "_" : Character($0) })
        s = s.trimmingCharacters(in: .whitespacesAndNewlines)
        s = String(s.prefix(80))
        return (s.isEmpty ? "Basic Art project" : s) + ".zip"
    }

    /// Writes the saved project (plus editor state) as a package into the temp directory.
    /// Call off the main thread after flushing autosave.
    static func export(id: String, store: ProjectStore = .shared) throws -> URL {
        let fm = FileManager.default
        let folder = store.folder(for: id)
        guard case .success(let doc) = store.load(id: id) else { throw StoreError.io("Can't open this project") }
        let dir = fm.temporaryDirectory.appendingPathComponent("ProjectExport-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(fileName(for: doc.name))
        let zip = try ZipWriter(url: url)
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0"
        let manifest = JSONObject([("package", .string("basicart-project")), ("packageVersion", .int(1)),
                                   ("formatVersion", .int(Document.supportedFormatVersion)), ("appPlatform", .string("ios")),
                                   ("appVersion", .string(version)), ("exported", .string(ISODate.format(Date())))])
        try zip.add(name: "basicart-package.json", data: JSONValue.object(manifest).serialized())
        try zip.add(name: "project.json", data: Data(contentsOf: folder.appendingPathComponent("project.json")))
        var assets = Set<String>()
        for l in doc.layers {
            if case .image(let p) = l.content { assets.insert(p.assetRef) }
            if case .drawing = l.content {
                let f = folder.appendingPathComponent("strokes/\(l.id).json")
                if let d = try? Data(contentsOf: f) { try zip.add(name: "strokes/\(l.id).json", data: d) }
            } else if !l.mask.isEmpty {
                // Eraser masks of image, text and shape layers (§9.1).
                let f = folder.appendingPathComponent("strokes/\(l.id).mask.json")
                if let d = try? Data(contentsOf: f) { try zip.add(name: "strokes/\(l.id).mask.json", data: d) }
            }
        }
        for a in assets.sorted() {
            if let d = try? Data(contentsOf: folder.appendingPathComponent("assets/\(a)")) { try zip.add(name: "assets/\(a)", data: d) }
        }
        if let d = try? Data(contentsOf: folder.appendingPathComponent("thumb.png")) { try zip.add(name: "thumb.png", data: d) }
        if let d = try? Data(contentsOf: store.editorStateURL(id)) { try zip.add(name: "editor-state.json", data: d) }
        try zip.finish()
        return url
    }
}

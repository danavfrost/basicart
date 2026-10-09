import SwiftUI
import UIKit

/// Home grid state. File work happens on a background queue.
@MainActor
final class HomeModel: ObservableObject {
    @Published var projects: [ProjectSummary] = []
    @Published var loaded = false
    @Published var errorMessage: String?
    @Published var errorTitle = "Something went wrong"
    /// Bumped when thumbnails change so tiles reload them.
    @Published var thumbGeneration = 0

    let store = ProjectStore.shared
    private let queue = DispatchQueue(label: "home.io", qos: .userInitiated)

    func reload() {
        queue.async { [store] in
            let list = store.listProjects()
            DispatchQueue.main.async {
                self.projects = list
                self.loaded = true
                self.regenerateMissingThumbnails(list)
            }
        }
    }

    private func regenerateMissingThumbnails(_ list: [ProjectSummary]) {
        let missing = list.filter { $0.status == .ok && !$0.hasThumb }
        guard !missing.isEmpty else { return }
        DispatchQueue.global(qos: .utility).async { [store] in
            for p in missing {
                guard case .success(let doc) = store.load(folder: p.folder) else { continue }
                if let data = Thumbnailer.png(for: doc, store: store) { store.writeThumbnail(data, id: p.id) }
                DispatchQueue.main.async {
                    ThumbnailCache.shared.invalidate(p.thumbURL)
                    if let i = self.projects.firstIndex(where: { $0.id == p.id }) { self.projects[i].hasThumb = true }
                    self.thumbGeneration += 1
                }
            }
        }
    }

    func delete(_ p: ProjectSummary) {
        projects.removeAll { $0.id == p.id }
        queue.async { [store] in
            do { try store.delete(id: p.id) } catch {
                DispatchQueue.main.async { self.errorMessage = "Couldn't delete the project." }
            }
            DispatchQueue.main.async { self.reload() }
        }
    }

    func duplicate(_ p: ProjectSummary) {
        queue.async { [store] in
            do { try store.duplicate(id: p.id) } catch {
                DispatchQueue.main.async { self.errorMessage = "Couldn't duplicate the project." }
            }
            DispatchQueue.main.async { self.reload() }
        }
    }

    func rename(_ p: ProjectSummary, to name: String) {
        let trimmed = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(100))
        guard !trimmed.isEmpty else { return }
        if let i = projects.firstIndex(where: { $0.id == p.id }) { projects[i].name = trimmed }
        queue.async { [store] in
            do { try store.rename(id: p.id, to: trimmed) } catch {
                DispatchQueue.main.async { self.errorMessage = "Couldn't rename the project." }
            }
            DispatchQueue.main.async { self.reload() }
        }
    }

    // MARK: Project packages (§11a / FORMAT §13)

    @Published var busyMessage: String?

    /// Imports a project file; calls back with the new project id on success.
    func importPackage(_ url: URL, completion: @escaping (String?) -> Void) {
        busyMessage = "Importing project…"
        let scoped = url.startAccessingSecurityScopedResource()
        queue.async { [store] in
            let result = ProjectPackage.importPackage(at: url, store: store)
            if scoped { url.stopAccessingSecurityScopedResource() }
            // Files handed over via "Open in" land in our Inbox: remove the copy.
            if url.path.contains("/Inbox/") { try? FileManager.default.removeItem(at: url) }
            DispatchQueue.main.async {
                self.busyMessage = nil
                switch result {
                case .success(let r):
                    self.reload()
                    completion(r.projectID)
                case .failure(let reason):
                    self.errorTitle = "Couldn't import"
                    self.errorMessage = reason.message
                    completion(nil)
                }
            }
        }
    }

    /// Builds the project file off the main thread.
    func exportPackage(_ p: ProjectSummary, completion: @escaping (URL?) -> Void) {
        busyMessage = "Preparing project file…"
        queue.async { [store] in
            let url = try? ProjectPackage.export(id: p.id, store: store)
            DispatchQueue.main.async {
                self.busyMessage = nil
                if url == nil { self.errorMessage = "Couldn't export the project file." }
                completion(url)
            }
        }
    }

    func deleteAll(completion: @escaping () -> Void) {
        queue.async { [store] in
            try? store.deleteAll()
            DispatchQueue.main.async {
                ThumbnailCache.shared.removeAll()
                self.projects = []
                completion()
            }
        }
    }

    /// Creates and saves a new project; returns its id on the main queue.
    func create(_ doc: Document, photo: ImportedImage?, completion: @escaping (String?) -> Void) {
        queue.async { [store] in
            var doc = doc
            do {
                if let photo {
                    let ref = try store.storeAsset(photo.data, ext: photo.ext, projectID: doc.id)
                    var p = ImageProps(assetRef: ref, naturalWidth: photo.naturalWidth, naturalHeight: photo.naturalHeight)
                    p.crop = CropRect(x: 0, y: 0, width: Double(photo.naturalWidth), height: Double(photo.naturalHeight))
                    let scale = Double(doc.canvas.width) / Double(photo.naturalWidth)
                    doc.layers.insert(Layer(id: Document.newID(), name: "Photo", transform: Transform(
                        x: Double(doc.canvas.width) / 2, y: Double(doc.canvas.height) / 2, scale: scale, rotation: 0),
                        content: .image(p)), at: 0)
                }
                var written: [String: [Stroke]] = [:]
                try store.save(doc, writtenStrokes: &written)
                DispatchQueue.main.async { completion(doc.id) }
            } catch {
                DispatchQueue.main.async {
                    self.errorMessage = "Couldn't create the project."
                    completion(nil)
                }
            }
        }
    }
}

enum Thumbnailer {
    /// thumb.png: full canvas, ≤512 px on the longest side, never upscaled, alpha kept.
    static func png(for doc: Document, store: ProjectStore) -> Data? {
        let longest = CGFloat(max(doc.canvas.width, doc.canvas.height))
        let scale = min(1, 512 / longest)
        let assets = AssetProvider(folder: store.folder(for: doc.id).appendingPathComponent("assets"))
        var opts = RenderOptions()
        opts.imageMaxPixel = 1024
        guard let img = Renderer.renderImage(doc, scale: scale, assets: assets, options: opts) else { return nil }
        return ImageEncoder.png(img)
    }
}

/// Decoded home-grid thumbnails.
final class ThumbnailCache {
    static let shared = ThumbnailCache()
    private let cache = NSCache<NSURL, UIImage>()

    func cached(_ url: URL) -> UIImage? { cache.object(forKey: url as NSURL) }

    func load(_ url: URL, maxPixel: Int = 600) async -> UIImage? {
        if let c = cached(url) { return c }
        let img: UIImage? = await withCheckedContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
                      let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceThumbnailMaxPixelSize: maxPixel,
                        kCGImageSourceShouldCacheImmediately: true] as CFDictionary) else {
                    cont.resume(returning: nil); return
                }
                cont.resume(returning: UIImage(cgImage: cg))
            }
        }
        if let img { cache.setObject(img, forKey: url as NSURL) }
        return img
    }

    func invalidate(_ url: URL) { cache.removeObject(forKey: url as NSURL) }
    func removeAll() { cache.removeAllObjects() }
}

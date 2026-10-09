import Foundation
import ImageIO
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// A photo prepared for the project format: portable bytes + oriented size.
struct ImportedImage {
    var data: Data
    var ext: String
    var naturalWidth: Int
    var naturalHeight: Int
}

enum ImageImporter {
    /// Normalizes raw image bytes (§2.1): keeps png/jpg/webp/gif, converts HEIC → JPEG 0.95
    /// (metadata incl. orientation preserved), anything else → PNG/JPEG.
    static func prepare(_ data: Data) -> ImportedImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(src) > 0,
              let type = CGImageSourceGetType(src) as String? else { return nil }
        let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] ?? [:]
        var w = props[kCGImagePropertyPixelWidth] as? Int ?? 0
        var h = props[kCGImagePropertyPixelHeight] as? Int ?? 0
        let orientation = props[kCGImagePropertyOrientation] as? Int ?? 1
        if orientation >= 5 && orientation <= 8 { swap(&w, &h) }
        guard w > 0, h > 0 else { return nil }
        let ut = UTType(type)
        var out = data
        var ext: String
        if ut == .png { ext = "png" }
        else if ut == .jpeg { ext = "jpg" }
        else if ut == .gif { ext = "gif" }
        else if ut == .webP { ext = "webp" }
        else {
            // HEIC/HEIF and everything else: re-encode, keeping metadata.
            let hasAlpha = (props[kCGImagePropertyHasAlpha] as? Bool) ?? false
            let isHEIC = ut?.conforms(to: .heic) == true || ut?.conforms(to: .heif) == true
            let target: UTType = (hasAlpha && !isHEIC) ? .png : .jpeg
            let md = NSMutableData()
            guard let dest = CGImageDestinationCreateWithData(md, target.identifier as CFString, 1, nil) else { return nil }
            CGImageDestinationAddImageFromSource(dest, src, 0, [kCGImageDestinationLossyCompressionQuality: 0.95] as CFDictionary)
            guard CGImageDestinationFinalize(dest) else { return nil }
            out = md as Data
            ext = target == .png ? "png" : "jpg"
        }
        return ImportedImage(data: out, ext: ext, naturalWidth: w, naturalHeight: h)
    }

    /// Loads picked items (order preserved), off the main thread.
    static func load(_ results: [PHPickerResult]) async -> [ImportedImage] {
        var out: [ImportedImage] = []
        for r in results {
            if let data = await loadData(r.itemProvider), let img = prepare(data) { out.append(img) }
        }
        return out
    }

    private static func loadData(_ provider: NSItemProvider) async -> Data? {
        let types = [UTType.heic, .heif, .jpeg, .png, .webP, .gif, .image]
        guard let t = types.first(where: { provider.hasItemConformingToTypeIdentifier($0.identifier) }) else { return nil }
        return await withCheckedContinuation { cont in
            provider.loadDataRepresentation(forTypeIdentifier: t.identifier) { data, _ in
                cont.resume(returning: data)
            }
        }
    }
}

/// PHPickerViewController wrapper (no photo-library permission needed).
struct PhotoPicker: UIViewControllerRepresentable {
    var selectionLimit: Int
    var onPick: ([PHPickerResult]) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = selectionLimit
        config.preferredAssetRepresentationMode = .current
        if #available(iOS 15.0, *) { config.selection = .ordered }
        let vc = PHPickerViewController(configuration: config)
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onPick: ([PHPickerResult]) -> Void
        init(onPick: @escaping ([PHPickerResult]) -> Void) { self.onPick = onPick }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            onPick(results)
        }
    }
}

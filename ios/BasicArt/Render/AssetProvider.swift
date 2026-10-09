import CoreGraphics
import CoreImage
import Foundation
import ImageIO

/// Decodes project assets (EXIF-oriented) with an optional size cap (editing proxy)
/// and caches decoded + adjusted images.
final class AssetProvider {
    let folder: URL
    private let cache = NSCache<NSString, CGImage>()
    private var missing = Set<String>()
    private let lock = NSLock()

    init(folder: URL) {
        self.folder = folder
        cache.totalCostLimit = 384 * 1024 * 1024
    }

    func url(_ ref: String) -> URL { folder.appendingPathComponent(ref) }

    func isMissing(_ ref: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return missing.contains(ref)
    }

    /// Decoded image with EXIF orientation applied. `maxPixel` nil = full resolution.
    func image(_ ref: String, maxPixel: Int?) -> CGImage? {
        let key = "\(ref)|\(maxPixel ?? 0)" as NSString
        if let c = cache.object(forKey: key) { return c }
        lock.lock()
        let isMiss = missing.contains(ref)
        lock.unlock()
        if isMiss { return nil }
        guard let src = CGImageSourceCreateWithURL(url(ref) as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(src) > 0 else {
            lock.lock(); missing.insert(ref); lock.unlock()
            return nil
        }
        let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any]
        let pw = props?[kCGImagePropertyPixelWidth] as? Int ?? 0
        let ph = props?[kCGImagePropertyPixelHeight] as? Int ?? 0
        let full = max(pw, ph)
        let cap = min(full > 0 ? full : 16384, maxPixel ?? Int.max)
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, cap),
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let img = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else {
            lock.lock(); missing.insert(ref); lock.unlock()
            return nil
        }
        cache.setObject(img, forKey: key, cost: img.bytesPerRow * img.height)
        return img
    }

    /// Image with the §6 colour adjustments applied (cached).
    func adjustedImage(_ ref: String, maxPixel: Int?, adjust: ImageAdjust) -> CGImage? {
        guard let base = image(ref, maxPixel: maxPixel) else { return nil }
        if adjust.isIdentity { return base }
        let key = "\(ref)|\(maxPixel ?? 0)|\(adjust.brightness),\(adjust.contrast),\(adjust.saturation),\(adjust.warmth)" as NSString
        if let c = cache.object(forKey: key) { return c }
        guard let out = ImageAdjuster.apply(adjust, to: base) else { return base }
        cache.setObject(out, forKey: key, cost: out.bytesPerRow * out.height)
        return out
    }

    func purge() { cache.removeAllObjects() }
}

enum ImageAdjuster {
    static let ciContext: CIContext = CIContext(options: [
        .workingColorSpace: RGBA.sRGB,
        .outputColorSpace: RGBA.sRGB,
        .cacheIntermediates: false,
    ])

    /// Composed 4×5 colour matrix (§6). Rows r, g, b; last column is the bias (0–1 units).
    static func matrix(_ a: ImageAdjust) -> (r: [Double], g: [Double], b: [Double], bias: [Double]) {
        let B = a.brightness / 100, C = a.contrast / 100, S = a.saturation / 100, W = a.warmth / 100
        let lum = [0.2126, 0.7152, 0.0722]
        func row(_ i: Int) -> [Double] {
            (0..<3).map { j in (1 + C) * ((i == j ? 1 + S : 0) - S * lum[j]) }
        }
        let bias0 = -0.5 * C + 0.4 * B   // (ch − 0.5)(1 + C) + 0.5 + 0.4B
        return (row(0), row(1), row(2), [bias0 + 0.1 * W, bias0, bias0 - 0.1 * W])
    }

    /// Reference per-pixel math (used by tests).
    static func applyReference(_ a: ImageAdjust, r: Double, g: Double, b: Double) -> (Double, Double, Double) {
        let B = a.brightness / 100, C = a.contrast / 100, S = a.saturation / 100, W = a.warmth / 100
        let L = 0.2126 * r + 0.7152 * g + 0.0722 * b
        var c = [r, g, b].map { L + ($0 - L) * (1 + S) }
        c = c.map { ($0 - 0.5) * (1 + C) + 0.5 }
        c = c.map { $0 + 0.4 * B }
        c[0] += 0.1 * W; c[2] -= 0.1 * W
        c = c.map { min(1, max(0, $0)) }
        return (c[0], c[1], c[2])
    }

    static func apply(_ a: ImageAdjust, to image: CGImage) -> CGImage? {
        let m = matrix(a)
        let ci = CIImage(cgImage: image).unpremultiplyingAlpha()
        let f = ci.applyingFilter("CIColorMatrix", parameters: [
            "inputRVector": CIVector(x: m.r[0], y: m.r[1], z: m.r[2], w: 0),
            "inputGVector": CIVector(x: m.g[0], y: m.g[1], z: m.g[2], w: 0),
            "inputBVector": CIVector(x: m.b[0], y: m.b[1], z: m.b[2], w: 0),
            "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 1),
            "inputBiasVector": CIVector(x: m.bias[0], y: m.bias[1], z: m.bias[2], w: 0),
        ]).applyingFilter("CIColorClamp").premultiplyingAlpha()
        return ciContext.createCGImage(f, from: ci.extent, format: .RGBA8, colorSpace: RGBA.sRGB)
    }
}

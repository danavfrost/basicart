import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

enum ImageEncoder {
    static func png(_ img: CGImage) -> Data? {
        encode(img, type: UTType.png, props: [:])
    }

    static func jpeg(_ img: CGImage, quality: Double) -> Data? {
        encode(img, type: UTType.jpeg, props: [kCGImageDestinationLossyCompressionQuality: quality])
    }

    static func encode(_ img: CGImage, type: UTType, props: [CFString: Any]) -> Data? {
        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(data, type.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, img, props as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return data as Data
    }
}

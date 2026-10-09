import CoreGraphics
import Foundation

/// sRGB color with straight (non-premultiplied) 8-bit alpha, serialized as "#RRGGBBAA".
struct RGBA: Hashable, Codable {
    var r: UInt8
    var g: UInt8
    var b: UInt8
    var a: UInt8

    init(r: UInt8, g: UInt8, b: UInt8, a: UInt8 = 255) {
        self.r = r; self.g = g; self.b = b; self.a = a
    }

    init(red: Double, green: Double, blue: Double, alpha: Double = 1) {
        func q(_ v: Double) -> UInt8 { UInt8(max(0, min(255, (v * 255).rounded()))) }
        self.init(r: q(red), g: q(green), b: q(blue), a: q(alpha))
    }

    /// Accepts "#RRGGBBAA" or "#RRGGBB" (upper or lower case). Returns nil otherwise.
    init?(hex: String) {
        guard hex.hasPrefix("#") else { return nil }
        let body = hex.dropFirst()
        guard body.count == 6 || body.count == 8,
              body.allSatisfy({ $0.isHexDigit && $0.isASCII }),
              var v = UInt32(body, radix: 16) else { return nil }
        if body.count == 6 { v = (v << 8) | 0xFF }
        r = UInt8((v >> 24) & 0xFF)
        g = UInt8((v >> 16) & 0xFF)
        b = UInt8((v >> 8) & 0xFF)
        a = UInt8(v & 0xFF)
    }

    var hex: String { String(format: "#%02X%02X%02X%02X", r, g, b, a) }
    var hexRGB: String { String(format: "%02X%02X%02X", r, g, b) }

    var red: Double { Double(r) / 255 }
    var green: Double { Double(g) / 255 }
    var blue: Double { Double(b) / 255 }
    var alpha: Double { Double(a) / 255 }

    func withAlpha(_ alpha: Double) -> RGBA {
        var c = self
        c.a = UInt8(max(0, min(255, (alpha * 255).rounded())))
        return c
    }

    static let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!

    var cgColor: CGColor {
        CGColor(colorSpace: RGBA.sRGB, components: [red, green, blue, alpha])!
    }

    static let white = RGBA(r: 255, g: 255, b: 255)
    static let black = RGBA(r: 0, g: 0, b: 0)
    static let clear = RGBA(r: 0, g: 0, b: 0, a: 0)
}

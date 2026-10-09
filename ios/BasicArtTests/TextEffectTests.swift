import XCTest
@testable import BasicArt

/// Text effects must never be clipped by the layout box (screen, thumbnail, export share one renderer).
final class TextEffectTests: XCTestCase {
    func render(_ t: TextProps) -> (CGImage, CGRect) {
        var doc = Document(canvas: Canvas(width: 1600, height: 800, background: .clear))
        doc.layers.append(Layer(id: "t", name: "t", transform: Transform(x: 800, y: 400), content: .text(t)))
        let size = LayerGeometry.boxSize(of: doc.layers[0])
        let box = CGRect(x: 800 - size.width / 2, y: 400 - size.height / 2, width: size.width, height: size.height)
        return (Renderer.renderImage(doc, scale: 1, assets: nil)!, box)
    }

    func alpha(_ img: CGImage, _ p: CGPoint) -> UInt8 {
        let ctx = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: RGBA.sRGB,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(img, in: CGRect(x: -Int(p.x), y: -(img.height - 1 - Int(p.y)), width: img.width, height: img.height))
        return ctx.data!.bindMemory(to: UInt8.self, capacity: 4)[3]
    }

    func base() -> TextProps {
        var t = TextProps(text: "HIH")
        t.fontSize = 200
        t.fontId = "inter"
        return t
    }

    func testGlowExtendsBeyondBox() {
        var t = base()
        t.outline = TextOutline(enabled: true, style: .glow, color: .black, width: 0.05, glowRadius: 1.0)
        let (img, box) = render(t)
        XCTAssertGreaterThan(alpha(img, CGPoint(x: box.minX - 0.25 * 200, y: box.midY)), 0)
        XCTAssertGreaterThan(alpha(img, CGPoint(x: box.maxX + 0.25 * 200, y: box.midY)), 0)
    }

    func testDoubleOutlineBeyondBox() {
        var t = base()
        t.outline = TextOutline(enabled: true, style: .double, color: .black, width: 0.2, color2: .white, width2: 0.2)
        let (img, box) = render(t)
        XCTAssertGreaterThan(alpha(img, CGPoint(x: box.minX - 0.2 * 200, y: box.midY)), 200)
    }

    func testShadowAndBoxBeyondBox() {
        var t = base()
        t.shadow = TextShadow(enabled: true, color: .black, blur: 0, offsetX: 0, offsetY: 0.8)
        let (img, box) = render(t)
        XCTAssertGreaterThan(alpha(img, CGPoint(x: box.midX, y: box.maxY + 0.3 * 200)), 0)
        var b = base()
        b.backgroundBox = TextBackgroundBox(enabled: true, color: .black, padding: 0.5, cornerRadius: 0)
        let (img2, box2) = render(b)
        XCTAssertEqual(alpha(img2, CGPoint(x: box2.minX - 0.4 * 200, y: box2.minY - 0.4 * 200)), 255)
    }

    func testItalicOverhangAndSkewNotClipped() {
        var t = base()
        t.text = "fff"
        t.fontId = "anton" // no italic file → synthesized 12° shear
        t.italic = true
        t.skew = 30
        let (img, box) = render(t)
        // Top of the last glyph leans right past the box edge.
        var found = false
        for dx in stride(from: 2.0, to: 120, by: 4) {
            for y in stride(from: box.minY, to: box.midY, by: 6) where alpha(img, CGPoint(x: box.maxX + dx, y: y)) > 0 { found = true }
        }
        XCTAssertTrue(found, "overhang was clipped")
    }
}

import XCTest
import CoreText
@testable import BasicArt

/// Where tests write cross-platform exchange files and renders. Override with the
/// BASICART_TEST_OUT environment variable (pass TEST_RUNNER_BASICART_TEST_OUT=<dir> to xcodebuild).
let testOutputRoot: String = ProcessInfo.processInfo.environment["BASICART_TEST_OUT"]
    ?? (NSTemporaryDirectory() as NSString).appendingPathComponent("basicart-tests")

final class EmojiTests: XCTestCase {
    /// Some simulator runtimes (iOS 26.3) ship without colour-emoji glyph data; even UILabel shows "?" there.
    var systemEmojiWorks: Bool {
        let f = CTFontCreateWithName("AppleColorEmoji" as CFString, 64, nil)
        let line = CTLineCreateWithAttributedString(NSAttributedString(string: "👍🏽", attributes: [NSAttributedString.Key(kCTFontAttributeName as String): f]))
        return (CTLineGetGlyphRuns(line) as! [CTRun]).reduce(0) { $0 + CTRunGetGlyphCount($1) } == 1
    }

    func testColorEmojiRendersInColorWithFallbackMeasurement() throws {
        try XCTSkipUnless(systemEmojiWorks, "This simulator runtime has no colour-emoji data")
        var t = TextProps(text: "a👍🏽b")
        t.fontSize = 100
        t.outline = TextOutline(enabled: true, style: .solid, color: .black, width: 0.1)
        let layout = TextLayoutEngine.shared.layout(t)
        XCTAssertEqual(layout.glyphs.filter { $0.path == nil }.count, 1, "skin-tone emoji is one colour glyph")
        XCTAssertLessThan(layout.size.width, 300, "emoji measured with the fallback font")
        var doc = Document(canvas: Canvas(width: 400, height: 200, background: .white))
        doc.layers.append(Layer(id: "t", name: "t", transform: Transform(x: 200, y: 100), content: .text(t)))
        let img = try XCTUnwrap(Renderer.renderImage(doc, scale: 1, assets: nil))
        let ctx = CGContext(data: nil, width: img.width, height: img.height, bitsPerComponent: 8, bytesPerRow: img.width * 4,
                            space: RGBA.sRGB, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: img.width, height: img.height))
        let px = ctx.data!.bindMemory(to: UInt8.self, capacity: img.width * img.height * 4)
        var colored = 0
        for i in 0..<(img.width * img.height) {
            let r = Int(px[i * 4]), g = Int(px[i * 4 + 1]), b = Int(px[i * 4 + 2])
            if max(r, g, b) - min(r, g, b) > 60 { colored += 1 }
        }
        XCTAssertGreaterThan(colored, 200, "emoji drawn in colour")
    }
}

/// Cross-platform write test: our writer's output must load on Android and vice versa.
final class CrossPlatformTests: XCTestCase {
    let xplat = URL(fileURLWithPath: testOutputRoot + "/xplat")

    func testWriteFixturesAndReadAndroidOutput() throws {
        let fixtures = Bundle(for: CrossPlatformTests.self).url(forResource: "fixtures", withExtension: nil)!
        let names = try FileManager.default.contentsOfDirectory(atPath: fixtures.path).filter { !$0.hasSuffix(".json") && !$0.hasSuffix(".md") }.sorted()
        let out = xplat.appendingPathComponent("ios-out")
        try? FileManager.default.removeItem(at: out)
        let store = ProjectStore(root: out)
        var written = 0
        for name in names {
            guard case .success(let doc) = store.load(folder: fixtures.appendingPathComponent(name)) else { continue }
            // Copy assets, then save with our writer (project.json, strokes, masks).
            let src = fixtures.appendingPathComponent(name).appendingPathComponent("assets")
            let dst = store.folder(for: doc.id).appendingPathComponent("assets")
            try FileManager.default.createDirectory(at: dst.deletingLastPathComponent(), withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: src.path) { try FileManager.default.copyItem(at: src, to: dst) }
            var w: [String: [Stroke]] = [:]
            try store.save(doc, writtenStrokes: &w)
            guard case .success(let again) = store.load(id: doc.id) else { XCTFail("reload \(name)"); continue }
            XCTAssertEqual(again.layers, doc.layers, name)
            written += 1
        }
        XCTAssertGreaterThanOrEqual(written, 8)
        print("XPLAT wrote \(written) projects to \(out.path)")

        let android = xplat.appendingPathComponent("android-out")
        guard FileManager.default.fileExists(atPath: android.path) else {
            print("XPLAT no android-out yet"); return
        }
        let reader = ProjectStore(root: android)
        var checked = 0
        for folder in try FileManager.default.contentsOfDirectory(at: android, includingPropertiesForKeys: nil) where folder.hasDirectoryPath {
            let name = folder.lastPathComponent
            guard case .success(let doc) = reader.load(folder: folder) else { XCTFail("android-out/\(name) didn't open"); continue }
            if case .success(let orig) = store.load(folder: fixtures.appendingPathComponent(name)) {
                let a = doc.layers.map(\.id), b = orig.layers.map(\.id)
                if a != b && a.count < b.count && Set(a).isSubset(of: Set(b)) {
                    // The shared fixture gained layers after Android wrote its copy: stale, not a mismatch.
                    print("XPLAT stale android-out/\(name): fixture has new layers \(Set(b).subtracting(a).sorted())")
                    checked += 1
                    continue
                }
                XCTAssertEqual(doc.layers.map(\.id), orig.layers.map(\.id), "android-out/\(name) layer ids/order")
                XCTAssertEqual(doc.layers.map { $0.mask.count }, orig.layers.map { $0.mask.count }, "android-out/\(name) masks")
            }
            checked += 1
        }
        print("XPLAT read \(checked) android projects")
    }
}

import CryptoKit

final class PencilGrainTests: XCTestCase {
    func testGrainTileIsBitExact() {
        let g = Renderer.pencilGrain
        XCTAssertEqual(Array(g.prefix(8)), [223, 184, 185, 173, 156, 252, 239, 252])
        let hash = SHA256.hash(data: Data(g)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(hash, "84e46aa5abb160be583d2f609760f24f6964dd7dde7af5b556cf6ad3fa8b9bc1")
        XCTAssertEqual(g.min(), 114); XCTAssertEqual(g.max(), 255)
    }

    func testGrainMappedInLocalPixels() throws {
        // A wide opaque pencil stroke on a 64×64 layer: pixel alpha follows G at that local pixel.
        var doc = Document(canvas: Canvas(width: 64, height: 64, background: .clear))
        let s = Stroke(brush: .pencil, size: 200, color: .black, opacity: 1, points: [CGPoint(x: 32, y: 32)])
        doc.layers.append(Layer(id: "d", name: "d", transform: Transform(x: 32, y: 32),
                                content: .drawing(DrawingProps(width: 64, height: 64, strokes: [s]))))
        let img = try XCTUnwrap(Renderer.renderImage(doc, scale: 1, assets: nil))
        let ctx = CGContext(data: nil, width: 64, height: 64, bitsPerComponent: 8, bytesPerRow: 256, space: RGBA.sRGB,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: 64, height: 64))
        let px = ctx.data!.bindMemory(to: UInt8.self, capacity: 64 * 64 * 4)
        for (x, y) in [(5, 3), (40, 10), (33, 63), (0, 0)] {
            let a = Double(px[(y * 64 + x) * 4 + 3])            // bitmap row 0 = top
            let expected = Double(Renderer.pencilGrain[(y % 32) * 32 + (x % 32)]) * 0.85
            XCTAssertEqual(a, expected, accuracy: 2.0, "grain at (\(x),\(y))")
        }
    }

    func testCurveCapKeepsRadiusAtLeastHeight() {
        var t = TextProps(text: "hi")
        t.fontSize = 100
        t.curve = 100
        let l = TextLayoutEngine.shared.layout(t)
        // With R ≥ h the two glyphs stay apart (no knot): their anchors are separated.
        let xs = l.glyphs.compactMap { $0.path?.boundingBoxOfPath.midX }
        XCTAssertEqual(xs.count, 2)
        XCTAssertGreaterThan(abs(xs[1] - xs[0]), 20)
    }
}

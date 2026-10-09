import XCTest
@testable import BasicArt

final class PerfTests: XCTestCase {
    /// 20 layers on a 4K canvas rendered at a phone-screen size: should stay well under a frame budget on a Mac sim.
    func testScreenRenderCost() throws {
        let url = Bundle(for: PerfTests.self).url(forResource: "fixtures", withExtension: nil)!.appendingPathComponent("01-all-layer-types")
        guard case .success(let src) = ProjectStore(root: FileManager.default.temporaryDirectory).load(folder: url) else { return XCTFail() }
        var doc = Document(canvas: Canvas(width: 3840, height: 2160))
        for i in 0..<20 {
            var l = src.layers[i % src.layers.count]
            l.id = "l\(i)"
            l.transform.x = Double(200 + i * 170)
            l.transform.y = Double(300 + (i % 5) * 350)
            if var t = l.text {
                t.outline = TextOutline(enabled: true, style: i % 2 == 0 ? .glow : .solid, color: .black, width: 0.06, glowRadius: 0.4)
                t.shadow.enabled = true
                l.text = t
            }
            doc.layers.append(l)
        }
        let assets = AssetProvider(folder: url.appendingPathComponent("assets"))
        var opts = RenderOptions(); opts.imageMaxPixel = 2048
        let scale = 1206.0 / 3840.0
        _ = Renderer.renderImage(doc, scale: scale, assets: assets, options: opts) // warm caches
        let start = CFAbsoluteTimeGetCurrent()
        for _ in 0..<5 { _ = Renderer.renderImage(doc, scale: scale, assets: assets, options: opts) }
        let ms = (CFAbsoluteTimeGetCurrent() - start) / 5 * 1000
        print("PERF full render \(String(format: "%.1f", ms)) ms")
        XCTAssertLessThan(ms, 250)
    }
}

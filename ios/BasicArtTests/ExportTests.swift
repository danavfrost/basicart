import ImageIO
import XCTest
@testable import BasicArt

final class ExportTests: XCTestCase {
    func loadFixture(_ name: String) -> (Document, AssetProvider) {
        let url = Bundle(for: ExportTests.self).url(forResource: "fixtures", withExtension: nil)!.appendingPathComponent(name)
        guard case .success(let doc) = ProjectStore(root: FileManager.default.temporaryDirectory).load(folder: url) else { fatalError() }
        return (doc, AssetProvider(folder: url.appendingPathComponent("assets")))
    }

    func decode(_ data: Data) -> (CGImage, String)? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil), let img = CGImageSourceCreateImageAtIndex(src, 0, nil),
              let type = CGImageSourceGetType(src) as String? else { return nil }
        return (img, type)
    }

    func alpha(_ img: CGImage, x: Int, y: Int) -> UInt8 {
        let ctx = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: RGBA.sRGB,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(img, in: CGRect(x: -x, y: -(img.height - 1 - y), width: img.width, height: img.height))
        return ctx.data!.bindMemory(to: UInt8.self, capacity: 4)[3]
    }

    func testEncodersProduceValidFilesWithCorrectSize() throws {
        let (doc, assets) = loadFixture("04-flags-shapes-image") // 800×600, transparent
        for (fmt, uti) in [(ExportFormat.png, "public.png"), (.jpeg, "public.jpeg"), (.gif, "com.compuserve.gif")] {
            for size in [ExportSize.full, .half, .quarter] {
                let (w, h) = Exporter.pixelSize(for: doc, size: size, customLongEdge: 0)
                let data = try XCTUnwrap(Exporter.export(doc, assets: assets, options: .init(format: fmt, jpegQuality: 90, pixelWidth: w, pixelHeight: h)))
                let (img, type) = try XCTUnwrap(decode(data), "\(fmt)")
                XCTAssertEqual(type, uti)
                XCTAssertEqual(img.width, w, "\(fmt) \(size)")
                XCTAssertEqual(img.height, h, "\(fmt) \(size)")
                // Top-left corner is empty canvas: transparent for PNG/GIF, opaque white for JPEG.
                let a = alpha(img, x: 2, y: h - 3)
                if fmt == .jpeg { XCTAssertEqual(a, 255) } else { XCTAssertEqual(a, 0, "\(fmt) keeps transparency") }
            }
        }
        let (w, h) = Exporter.pixelSize(for: doc, size: .custom, customLongEdge: 1000)
        XCTAssertEqual(w, 1000); XCTAssertEqual(h, 750)
    }

    func testGIFQuantizesManyColors() throws {
        let (doc, assets) = loadFixture("02-text-effects")
        let img = try XCTUnwrap(Renderer.renderImage(doc, scale: 0.5, assets: assets))
        let data = try XCTUnwrap(GIFEncoder.encode(img))
        let (out, _) = try XCTUnwrap(decode(data))
        XCTAssertEqual(out.width, img.width)
        XCTAssertEqual(out.height, img.height)
    }

    func testSanitizedFileName() {
        XCTAssertEqual(Exporter.sanitizedFileName("a/b:c"), "a-b-c")
        XCTAssertEqual(Exporter.sanitizedFileName("  "), "Basic Art")
    }

    func testStoreSaveDeleteAndClearAll() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("store-\(UUID().uuidString)")
        let store = ProjectStore(root: root)
        var doc = Document(canvas: Canvas(width: 100, height: 100))
        doc.layers.append(Layer(id: "d", name: "D", transform: Transform(x: 50, y: 50),
                                content: .drawing(DrawingProps(width: 100, height: 100, strokes: [Stroke(brush: .pen, points: [.zero])]))))
        var written: [String: [Stroke]] = [:]
        try store.save(doc, writtenStrokes: &written)
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.folder(for: doc.id).appendingPathComponent("strokes/d.json").path))
        let dup = try store.duplicate(id: doc.id)
        XCTAssertEqual(store.listProjects().count, 2)
        guard case .success(let d2) = store.load(id: dup) else { return XCTFail() }
        XCTAssertEqual(d2.name, "Untitled copy")
        XCTAssertEqual(d2.layers[0].drawing?.strokes.count, 1)
        try store.delete(id: doc.id)
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.folder(for: doc.id).path))
        try store.deleteAll()
        XCTAssertEqual(store.listProjects().count, 0)
        // No temp files left behind.
        let leftovers = (try? FileManager.default.contentsOfDirectory(atPath: root.path)) ?? []
        XCTAssertTrue(leftovers.isEmpty)
    }

    func testAtomicWriteLeavesNoTmp() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("aw-\(UUID().uuidString)")
        let store = ProjectStore(root: root)
        let doc = Document(canvas: Canvas(width: 50, height: 50))
        var w: [String: [Stroke]] = [:]
        try store.save(doc, writtenStrokes: &w)
        let files = try FileManager.default.contentsOfDirectory(atPath: store.folder(for: doc.id).path)
        XCTAssertFalse(files.contains { $0.hasSuffix(".tmp") })
    }
}

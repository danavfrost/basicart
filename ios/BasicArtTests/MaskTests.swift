import XCTest
@testable import BasicArt

/// Eraser masks for image/text/shape layers (FORMAT.md §9.1).
final class MaskTests: XCTestCase {
    func eraser(_ pts: [CGPoint], size: Double = 40) -> Stroke {
        Stroke(brush: .eraser, size: size, color: .black, opacity: 1, points: pts)
    }

    func shapeDoc(mask: [Stroke]) -> Document {
        var doc = Document(canvas: Canvas(width: 200, height: 200, background: .clear))
        var l = Layer(id: "s", name: "S", transform: Transform(x: 100, y: 100),
                      content: .shape(ShapeProps(shape: .rect, width: 100, height: 100)))
        l.mask = mask
        doc.layers.append(l)
        return doc
    }

    func alpha(_ img: CGImage, _ x: Int, _ y: Int) -> UInt8 {
        let ctx = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: RGBA.sRGB,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(img, in: CGRect(x: -x, y: -(img.height - 1 - y), width: img.width, height: img.height))
        return ctx.data!.bindMemory(to: UInt8.self, capacity: 4)[3]
    }

    func testMaskErasesInRenderAndExport() throws {
        let doc = shapeDoc(mask: [eraser([CGPoint(x: 50, y: 50)])]) // dot in the box centre (local)
        let img = try XCTUnwrap(Renderer.renderImage(doc, scale: 1, assets: nil))
        XCTAssertEqual(alpha(img, 100, 100), 0, "centre erased")
        XCTAssertEqual(alpha(img, 60, 60), 255, "corner kept")
        let png = try XCTUnwrap(Exporter.export(doc, assets: AssetProvider(folder: URL(fileURLWithPath: "/nonexistent")),
                                                 options: .init(format: .png, jpegQuality: 90, pixelWidth: 100, pixelHeight: 100)))
        let src = CGImageSourceCreateWithData(png as CFData, nil)!
        let out = CGImageSourceCreateImageAtIndex(src, 0, nil)!
        XCTAssertEqual(alpha(out, 50, 50), 0)
    }

    func testMaskRoundTripAndCleanup() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("mask-\(UUID().uuidString)")
        let store = ProjectStore(root: root)
        var doc = shapeDoc(mask: [eraser([CGPoint(x: 10, y: 10), CGPoint(x: 30, y: 40)])])
        var w: [String: [Stroke]] = [:]
        try store.save(doc, writtenStrokes: &w)
        let file = store.folder(for: doc.id).appendingPathComponent("strokes/s.mask.json")
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        guard case .success(let back) = store.load(id: doc.id) else { return XCTFail() }
        XCTAssertEqual(back.layers[0].mask, doc.layers[0].mask)
        // Undo to no mask → file removed.
        doc.layers[0].mask = []
        try store.save(doc, writtenStrokes: &w)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }

    func testInvalidMaskIsIgnoredAndRenamed() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("maskbad-\(UUID().uuidString)")
        let store = ProjectStore(root: root)
        let doc = shapeDoc(mask: [])
        var w: [String: [Stroke]] = [:]
        try store.save(doc, writtenStrokes: &w)
        let file = store.folder(for: doc.id).appendingPathComponent("strokes/s.mask.json")
        // A pen stroke makes the mask invalid.
        try ProjectCodec.encodeStrokes(layerID: "s", strokes: [Stroke(brush: .pen, points: [.zero])]).write(to: file)
        guard case .success(let loaded) = store.load(id: doc.id) else { return XCTFail("must still open") }
        XCTAssertTrue(loaded.layers[0].mask.isEmpty)
        var w2: [String: [Stroke]] = [:]
        try store.save(loaded, writtenStrokes: &w2)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path + ".corrupt"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }

    func testDrawingLayerIgnoresMaskFile() throws {
        let url = Bundle(for: MaskTests.self).url(forResource: "fixtures", withExtension: nil)!.appendingPathComponent("05-drawing-brushes")
        guard case .success(let d) = ProjectStore(root: FileManager.default.temporaryDirectory).load(folder: url) else { return XCTFail() }
        XCTAssertTrue(d.layers.allSatisfy { $0.mask.isEmpty })
    }

    func testImageMaskFollowsCropRotateFlip() {
        var a = ImageProps(assetRef: String(repeating: "a", count: 64) + ".png", naturalWidth: 64, naturalHeight: 48)
        a.crop = CropRect(x: 8, y: 4, width: 40, height: 30)
        let naturalPoint = CGPoint(x: 20, y: 10)
        let boxPoint = naturalPoint.applying(Renderer.boxFromNatural(a))
        XCTAssertEqual(boxPoint, CGPoint(x: 12, y: 6))
        var b = a
        b.rotate90 = 1
        swap(&b.flipH, &b.flipV)
        b.crop = CropRect(x: 0, y: 0, width: 64, height: 48)
        let mask = [eraser([boxPoint])]
        let remapped = Renderer.remapImageMask(mask, from: a, to: b)
        let back = remapped[0].points[0].applying(Renderer.boxFromNatural(b).inverted())
        XCTAssertEqual(back.x, naturalPoint.x, accuracy: 1e-9)
        XCTAssertEqual(back.y, naturalPoint.y, accuracy: 1e-9)
        XCTAssertEqual(remapped[0].size, 40)
    }

    @MainActor
    func testEditorRemapsMaskOnRotateAndDuplicateCopiesIt() {
        var doc = Document(canvas: Canvas(width: 200, height: 200))
        var l = Layer(id: "img", name: "P", transform: Transform(x: 100, y: 100),
                      content: .image(ImageProps(assetRef: String(repeating: "b", count: 64) + ".png", naturalWidth: 40, naturalHeight: 20)))
        l.mask = [eraser([CGPoint(x: 5, y: 5)])]
        doc.layers.append(l)
        let store = ProjectStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("m-\(UUID().uuidString)"))
        let m = EditorModel(doc: doc, store: store)
        m.updateLayer("img") { l in
            guard var p = l.image else { return }
            p.rotate90 = 1
            l.image = p
        }
        // (5,5) in a 40×20 box → after a clockwise quarter turn the box is 20×40: (ch − y, x) = (15, 5)
        XCTAssertEqual(m.doc.layers[0].mask[0].points[0], CGPoint(x: 15, y: 5))
        m.undo()
        XCTAssertEqual(m.doc.layers[0].mask[0].points[0], CGPoint(x: 5, y: 5))
        m.duplicateLayer("img")
        XCTAssertEqual(m.doc.layers[1].mask, m.doc.layers[0].mask)
    }
}

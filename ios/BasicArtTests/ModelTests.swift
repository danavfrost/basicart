import XCTest
@testable import BasicArt

final class SpanTests: XCTestCase {
    let B = CharStyle(bold: true)

    func props(_ text: String, spans: [TextSpan] = []) -> TextProps {
        var t = TextProps(text: text)
        t.spans = spans
        return t
    }
    func sp(_ a: Int, _ b: Int, _ st: CharStyle) -> TextSpan { TextSpan(start: a, end: b, style: st) }

    func testUTF16Offsets() {
        let s = "I ❤️ you"
        XCTAssertEqual((s as NSString).length, 8)
        XCTAssertEqual(Spans.graphemeBoundaries(s), [0, 1, 2, 4, 5, 6, 7, 8])
    }

    func testNormalizeSnapsOutwardAndClamps() {
        let t = props("a👋🏽b")
        XCTAssertEqual(Spans.normalize(t, spans: [sp(2, 3, B)]).map { [$0.start, $0.end] }, [[1, 5]])
        XCTAssertEqual(Spans.normalize(t, spans: [sp(-4, 99, B)]).map { [$0.start, $0.end] }, [[0, 6]])
    }

    func testNormalizeStripsLayerEqualFieldsAndEmpty() {
        var t = props("hello")
        t.fontSize = 64; t.fill = TextFill(type: .solid, color: .black)
        let n = Spans.normalize(t, spans: [sp(0, 2, CharStyle(bold: false, color: .black, fontId: "inter", weight: 400, size: 64)),
                                           sp(3, 3, B)])
        XCTAssertTrue(n.isEmpty)
        // Against a gradient fill a span colour is always kept.
        t.fill.type = .linear
        XCTAssertEqual(Spans.normalize(t, spans: [sp(0, 2, CharStyle(color: .black))]).count, 1)
    }

    func testNormalizeMergesTouchingIdentical() {
        let t = props("hello world")
        let n = Spans.normalize(t, spans: [sp(6, 11, B), sp(0, 3, B), sp(3, 6, B)])
        XCTAssertEqual(n.map { [$0.start, $0.end] }, [[0, 11]])
        // Different fields don't merge.
        let m = Spans.normalize(t, spans: [sp(0, 3, B), sp(3, 6, CharStyle(bold: true, size: 90))])
        XCTAssertEqual(m.count, 2)
    }

    func testOverlapLaterWins() {
        let it = CharStyle(italic: true)
        let n = Spans.normalize(props("abcdefgh"), spans: [sp(0, 6, B), sp(3, 8, it)])
        XCTAssertEqual(n, [sp(0, 3, B), sp(3, 8, it)])
    }

    func testToggleSelectionAndWhole() {
        var t = props("Hello brave new world")
        Spans.toggle(.bold, in: &t, selection: 6..<11)
        XCTAssertEqual(t.spans, [sp(6, 11, B)])
        Spans.toggle(.bold, in: &t, selection: 6..<11)
        XCTAssertEqual(t.spans, [])
        Spans.toggle(.bold, in: &t, selection: 6..<11)
        Spans.toggle(.bold, in: &t, selection: 0..<8)
        XCTAssertEqual(t.spans, [sp(0, 11, B)])
        // Whole text: layer flag set, field removed from spans.
        var u = props("Hello world", spans: [sp(0, 5, CharStyle(italic: true, size: 90))])
        Spans.toggle(.bold, in: &u, selection: nil)
        XCTAssertTrue(u.bold)
        XCTAssertEqual(u.spans, [sp(0, 5, CharStyle(italic: true, size: 90))])
        Spans.toggle(.bold, in: &u, selection: 0..<11)
        XCTAssertFalse(u.bold)
    }

    func testColorFontSizeOnSelectionAndWhole() {
        var t = props("plain RED plain")
        t.fill = TextFill(type: .solid, color: .white)
        Spans.setColor(RGBA(hex: "#E53935FF")!, in: &t, selection: 6..<9)
        XCTAssertEqual(t.spans, [sp(6, 9, CharStyle(color: RGBA(hex: "#E53935FF")!))])
        Spans.setSize(90, in: &t, selection: 6..<9)
        let caveat = FontCatalog.shared.font(id: "caveat")
        Spans.setFont(caveat, in: &t, selection: 0..<5)
        XCTAssertEqual(t.spans.first?.style.fontId, "caveat")
        // weight equals the layer weight (400) → stripped by normalization; effective weight is 400.
        XCTAssertEqual(t.effective(t.spans.first?.style).weight, caveat.nearestUprightWeight(to: 400))
        // Whole-text colour: layer fill, span colours removed (sizes/fonts kept).
        Spans.setColor(.black, in: &t, selection: nil)
        XCTAssertEqual(t.fill.color, .black)
        XCTAssertTrue(t.spans.allSatisfy { $0.style.color == nil })
        XCTAssertTrue(t.spans.contains { $0.style.size == 90 })
        // Whole-text size: layer size, span sizes removed.
        Spans.setSize(50, in: &t, selection: nil)
        XCTAssertEqual(t.fontSize, 50)
        XCTAssertTrue(t.spans.allSatisfy { $0.style.size == nil })
        // Gradient fill clears span colours.
        Spans.setColor(.white, in: &t, selection: 6..<9)
        Spans.setLayerFill(TextFill(type: .linear), in: &t)
        XCTAssertTrue(t.spans.allSatisfy { $0.style.color == nil })
    }

    func testInsertGrowsSpanAndShifts() {
        var t = props("abcdef", spans: [sp(2, 4, B)])
        Spans.replace(in: &t, range: 4..<4, with: "XY")
        XCTAssertEqual(t.spans, [sp(2, 6, B)])
        Spans.replace(in: &t, range: 2..<2, with: "Z")
        XCTAssertEqual(t.spans, [sp(3, 7, B)])
        Spans.replace(in: &t, range: 0..<0, with: "Q")
        XCTAssertEqual(t.spans, [sp(4, 8, B)])
    }

    func testInsertAtZeroGrowsSpanStartingAtZero() {
        var t = props("abc", spans: [sp(0, 2, B)])
        Spans.replace(in: &t, range: 0..<0, with: "xx")
        XCTAssertEqual(t.spans, [sp(0, 4, B)])
    }

    func testDeleteCollapses() {
        let I = CharStyle(italic: true)
        var t = props("abcdefgh", spans: [sp(2, 5, B), sp(6, 8, I)])
        Spans.replace(in: &t, range: 3..<7, with: "")
        XCTAssertEqual(t.text, "abch")
        XCTAssertEqual(t.spans, [sp(2, 3, B), sp(3, 4, I)])
        Spans.replace(in: &t, range: 0..<4, with: "")
        XCTAssertEqual(t.spans, [])
    }

    func testTypingStyleOverride() {
        var t = props("ab")
        var typing = CharStyle(t.layerStyle)
        typing.bold = true
        typing.size = 90
        Spans.replace(in: &t, range: 1..<1, with: "X", typing: typing)
        XCTAssertEqual(t.spans, [sp(1, 2, CharStyle(bold: true, size: 90))])
    }

    func testEmojiInsertUsesUTF16() {
        var t = props("I  you", spans: [sp(3, 6, B)])
        Spans.replace(in: &t, range: 2..<2, with: "❤️")
        XCTAssertEqual(t.text, "I ❤️ you")
        XCTAssertEqual(t.spans.map { [$0.start, $0.end] }, [[5, 8]])
    }

    func testCaseTransformKeepsSourceStyle() {
        var t = props("straße x", spans: [sp(4, 5, B)])
        t.textCase = .upper
        let g = TextLayoutEngine.displayGraphemes(t)
        XCTAssertEqual(g.map(\.text).joined(), "STRASSE X")
        XCTAssertEqual(g.filter { $0.flags.bold }.map(\.text), ["S", "S"])
        t.textCase = .title
        XCTAssertEqual(TextLayoutEngine.displayGraphemes(t).map(\.text).joined(), "Straße X")
    }

    func testScaleSizes() {
        var t = props("ab", spans: [sp(0, 1, CharStyle(size: 33.33333))])
        Spans.scaleSizes(&t, by: 1.5)
        XCTAssertEqual(t.spans[0].style.size, 50)
    }

    func testMixedSizeLinesStackAndShareBaseline() {
        var t = props("Big\nsmall", spans: [sp(0, 3, CharStyle(size: 120))])
        t.fontSize = 40
        let l = TextLayoutEngine.shared.layout(t)
        XCTAssertEqual(l.lines.count, 2)
        XCTAssertEqual(l.lines[0].height, 1.2 * 120, accuracy: 0.001)
        XCTAssertEqual(l.lines[1].height, 1.2 * 40, accuracy: 0.001)
        XCTAssertEqual(l.size.height, 1.2 * 160, accuracy: 0.001)
        XCTAssertEqual(l.lines[1].top, 1.2 * 120, accuracy: 0.001)
        // Mixed sizes on one line share one baseline and the line uses the largest size.
        var m = props("aB", spans: [sp(1, 2, CharStyle(size: 100))])
        m.fontSize = 50
        XCTAssertEqual(TextLayoutEngine.shared.layout(m).lines[0].height, 120, accuracy: 0.001)
    }

    func testSpanColorOverridesGradientInRender() throws {
        var t = props("MMMM", spans: [sp(0, 2, CharStyle(color: RGBA(hex: "#FF0000FF")!))])
        t.fontSize = 120
        t.fill = TextFill(type: .linear, angle: 0, stops: [GradientStop(offset: 0, color: RGBA(hex: "#0000FFFF")!), GradientStop(offset: 1, color: RGBA(hex: "#0000FFFF")!)])
        let l = TextLayoutEngine.shared.layout(t)
        XCTAssertEqual(l.groups.filter { $0.color != nil }.count, 1)
        XCTAssertEqual(l.groups.filter { $0.color == nil }.count, 1)
    }
}

final class LayerOpsTests: XCTestCase {
    func doc() -> Document {
        var d = Document(canvas: Canvas(width: 100, height: 100))
        for id in ["a", "b", "c", "d"] {
            d.layers.append(Layer(id: id, name: id, transform: Transform(x: 50, y: 50),
                                  content: .shape(ShapeProps(shape: .rect, width: 10, height: 10))))
        }
        return d
    }
    func ids(_ d: Document) -> String { d.layers.map(\.id).joined() }

    func testOrdering() {
        var d = doc()
        d.moveLayerUp("a"); XCTAssertEqual(ids(d), "bacd")
        d.moveLayerUp("d"); XCTAssertEqual(ids(d), "bacd")
        d.moveLayerDown("c"); XCTAssertEqual(ids(d), "bcad")
        d.moveLayerDown("b"); XCTAssertEqual(ids(d), "bcad")
        d.moveLayerToTop("b"); XCTAssertEqual(ids(d), "cadb")
        d.moveLayerToBottom("d"); XCTAssertEqual(ids(d), "dcab")
        d.moveLayer("d", toIndex: 2); XCTAssertEqual(ids(d), "cadb")
        d.moveLayer("b", toIndex: 0); XCTAssertEqual(ids(d), "bcad")
        d.moveLayer("b", toIndex: 99); XCTAssertEqual(ids(d), "cadb")
    }

    func testDuplicateAndRemove() {
        var d = doc()
        let nid = d.duplicateLayer("b")!
        XCTAssertEqual(d.layers[2].id, nid)
        XCTAssertEqual(d.layers[2].name, "b copy")
        XCTAssertNotNil(d.removeLayer("a"))
        XCTAssertEqual(d.layers.count, 4)
    }

    func testUndoRedo() {
        var h = UndoHistory<Document>(limit: 3)
        var d = doc()
        h.record(d); d.moveLayerToTop("a")
        h.record(d); d.removeLayer("b")
        XCTAssertEqual(ids(d), "cda")
        d = h.undo(current: d)!; XCTAssertEqual(ids(d), "bcda")
        d = h.undo(current: d)!; XCTAssertEqual(ids(d), "abcd")
        XCTAssertNil(h.undo(current: d))
        d = h.redo(current: d)!; XCTAssertEqual(ids(d), "bcda")
        h.record(d); d.moveLayerToBottom("d")
        XCTAssertFalse(h.canRedo)
        for _ in 0..<10 { h.record(d) }
        XCTAssertEqual(h.past.count, 3)
    }

    func testDefaultHistoryHoldsAtLeast50() {
        var h = UndoHistory<Int>()
        for i in 0..<60 { h.record(i) }
        XCTAssertGreaterThanOrEqual(h.past.count, 50)
    }
}

final class CodecTests: XCTestCase {
    func testColorParsing() {
        XCTAssertEqual(RGBA(hex: "#ff000080")?.hex, "#FF000080")
        XCTAssertEqual(RGBA(hex: "#00FF00")?.hex, "#00FF00FF")
        XCTAssertNil(RGBA(hex: "00FF00"))
        XCTAssertNil(RGBA(hex: "#0F0"))
        XCTAssertNil(RGBA(hex: "#GG0000FF"))
    }

    func minimal(_ layers: String = "[]", extra: String = "") -> Data {
        Data("""
        {"formatVersion":1,"id":"p1","canvas":{"width":100,"height":100}\(extra),"layers":\(layers)}
        """.utf8)
    }

    func testDefaultsApplied() throws {
        let data = minimal(#"[{"id":"t","type":"text","transform":{},"text":"hi"}]"#)
        guard case .success(let d) = ProjectCodec.decode(data, folderID: nil) else { return XCTFail() }
        XCTAssertEqual(d.canvas.background, .white)
        XCTAssertEqual(d.name, "Untitled")
        let l = d.layers[0]
        XCTAssertEqual(l.transform.x, 50)
        XCTAssertEqual(l.text?.fontSize, 64)
        XCTAssertEqual(l.text?.align, .center)
        XCTAssertEqual(l.name, "Text")
    }

    func testClampingNotCorrupt() throws {
        let data = minimal(#"[{"id":"t","type":"text","opacity":1.3,"transform":{"rotation":-90},"text":"hi","fontSize":9000}]"#)
        guard case .success(let d) = ProjectCodec.decode(data, folderID: nil) else { return XCTFail() }
        XCTAssertEqual(d.layers[0].opacity, 1)
        XCTAssertEqual(d.layers[0].transform.rotation, 270)
        XCTAssertEqual(d.layers[0].text?.fontSize, 2000)
    }

    func testCorruptCases() {
        let cases: [Data] = [
            Data("[1,2]".utf8),
            Data(#"{"id":"x","canvas":{"width":100,"height":100},"layers":[]}"#.utf8),
            Data(#"{"formatVersion":0,"id":"x","canvas":{"width":100,"height":100},"layers":[]}"#.utf8),
            Data(#"{"formatVersion":1,"id":"x","canvas":{"width":10,"height":100},"layers":[]}"#.utf8),
            minimal(#"[{"id":"a","type":"blob","transform":{}}]"#),
            minimal(#"[{"id":"a","type":"shape","shape":"star","width":1,"height":1,"transform":{}}]"#),
            minimal(#"[{"id":"a","type":"text","text":"x","visible":1,"transform":{}}]"#),
            minimal(#"[{"id":"a","type":"text","transform":{}}]"#),
            minimal(#"[{"id":"a b","type":"text","text":"x","transform":{}}]"#),
            minimal(#"[{"id":"a","type":"image","assetRef":"../../etc/passwd","naturalWidth":1,"naturalHeight":1,"transform":{}}]"#),
        ]
        for (i, c) in cases.enumerated() {
            guard case .failure(.corrupt) = ProjectCodec.decode(c, folderID: nil) else { return XCTFail("case \(i)") }
        }
    }

    func testMalformedColorFallsBackToDefault() throws {
        let bad = Data(##"{"formatVersion":1,"id":"p","canvas":{"width":100,"height":100,"background":"#GGGGGG"},"layers":[{"id":"t","type":"text","transform":{},"text":"hi","fill":{"type":"solid","color":"red"},"outline":{"color":"#12"}}]}"##.utf8)
        guard case .success(let d) = ProjectCodec.decode(bad, folderID: nil) else { return XCTFail("malformed colours must not be corrupt") }
        XCTAssertEqual(d.canvas.background, .white)
        XCTAssertEqual(d.layers[0].text?.fill.color, .black)
        XCTAssertEqual(d.layers[0].text?.outline.color, .black)
        // A colour with the wrong JSON type is still corrupt.
        let wrongType = minimal(#"[{"id":"t","type":"text","transform":{},"text":"hi","fill":{"type":"solid","color":5}}]"#)
        guard case .failure(.corrupt) = ProjectCodec.decode(wrongType, folderID: nil) else { return XCTFail() }
    }

    func testNewerVersionBeforeValidation() {
        let data = Data(#"{"formatVersion":2,"name":"Future","layers":"nope"}"#.utf8)
        XCTAssertEqual(ProjectCodec.decode(data, folderID: nil), .failure(.newerVersion(name: "Future")))
    }

    func testWriterRounding() {
        XCTAssertEqual(JSONValue.format(1080), "1080")
        XCTAssertEqual(JSONValue.format(round4(0.123456)), "0.1235")
        XCTAssertEqual(JSONValue.format(round4(37.5)), "37.5")
        XCTAssertEqual(JSONValue.format(-0.0), "0")
    }

    func testStrokesRoundTrip() {
        let s = Stroke(brush: .calligraphy, size: 10, color: .black, opacity: 0.5,
                       points: [CGPoint(x: 1.234, y: 2), CGPoint(x: 3, y: 4)], pressure: [0.5, 1])
        let data = ProjectCodec.encodeStrokes(layerID: "d", strokes: [s])
        let back = ProjectCodec.decodeStrokes(data)!
        XCTAssertEqual(back[0].points[0].x, 1.23, accuracy: 0.0001)
        XCTAssertEqual(back[0].pressure!, [0.5, 1])
        XCTAssertNil(ProjectCodec.decodeStrokes(Data(#"{"strokes":[{"brush":"spray","points":[1,2]}]}"#.utf8)))
    }

    func testPresetApplication() throws {
        let url = Bundle.main.url(forResource: "text-presets", withExtension: "json")!
        let presets = TextPresets.load(from: url)
        XCTAssertEqual(presets.count, 6)
        var t = TextProps(text: "hello")
        t.fontSize = 99
        t.curve = 40
        t.bold = true
        let meme = presets.first { $0.id == "classic-meme" }!
        meme.apply(to: &t)
        XCTAssertEqual(t.fontId, "anton")
        XCTAssertEqual(t.weight, 400)
        XCTAssertEqual(t.textCase, .upper)
        XCTAssertTrue(t.outline.enabled)
        XCTAssertEqual(t.outline.width, 0.13)
        XCTAssertEqual(t.fontSize, 99)
        XCTAssertEqual(t.curve, 0)
        XCTAssertFalse(t.bold)
        // Presets drop span colour/font/weight but keep B/I/U/S and size.
        var v = TextProps(text: "hello world")
        v.spans = [TextSpan(start: 0, end: 5, style: CharStyle(underline: true, color: .white, fontId: "caveat", weight: 700, size: 99))]
        meme.apply(to: &v)
        XCTAssertEqual(v.spans, [TextSpan(start: 0, end: 5, style: CharStyle(underline: true, size: 99))])
        let caption = presets.first { $0.id == "caption-bar" }!
        caption.apply(to: &t)
        XCTAssertEqual(t.weight, 600)
        XCTAssertFalse(t.outline.enabled)
        XCTAssertTrue(t.backgroundBox.enabled)
    }
}

final class FontTests: XCTestCase {
    func testCatalogLoads() {
        let c = FontCatalog.shared
        XCTAssertEqual(c.allFonts.count, 265)
        XCTAssertEqual(c.allGroups.count, 17)
        for g in c.allGroups { XCTAssertTrue(c.isKnown(g.sampleFontId), g.id) }
        for id in ["inter", "anton", "bebas-neue", "caveat", "permanent-marker", "bungee"] { XCTAssertTrue(c.isKnown(id), id) }
    }

    func testFaceResolution() {
        let inter = FontCatalog.shared.font(id: "inter")
        XCTAssertEqual(FontCatalog.resolve(inter, weight: 400, bold: true, italic: false).file.weight, 700)
        XCTAssertEqual(FontCatalog.resolve(inter, weight: 900, bold: true, italic: false).file.weight, 900)
        XCTAssertFalse(FontCatalog.resolve(inter, weight: 900, bold: true, italic: false).synthBold)
        let it = FontCatalog.resolve(inter, weight: 400, bold: false, italic: true)
        XCTAssertTrue(it.file.italic); XCTAssertFalse(it.synthItalic)
        let anton = FontCatalog.shared.font(id: "anton")
        let ab = FontCatalog.resolve(anton, weight: 400, bold: true, italic: true)
        XCTAssertTrue(ab.synthBold); XCTAssertTrue(ab.synthItalic)
        // Ties go lighter when not bold.
        let fam = FontFamily(id: "x", family: "X", category: "sans", group: "", files: [
            FontFile(weight: 300, italic: false, styleName: "Light", path: ""),
            FontFile(weight: 500, italic: false, styleName: "Medium", path: "")],
            licenseName: "", licenseFile: "", copyright: "", author: "")
        XCTAssertEqual(FontCatalog.resolve(fam, weight: 400, bold: false, italic: false).file.weight, 300)
        XCTAssertTrue(FontCatalog.resolve(fam, weight: 400, bold: true, italic: false).synthBold)
    }

    func testVariableWeightAxisApplied() {
        let inter = FontCatalog.shared.font(id: "inter")
        func width(_ w: Int) -> CGFloat {
            let f = FontCatalog.shared.ctFont(FontCatalog.resolve(inter, weight: w, bold: false, italic: false), size: 100)
            let s = NSAttributedString(string: "Hamburgefonstiv", attributes: [.font: f as UIFont])
            return CTLineGetBoundsWithOptions(CTLineCreateWithAttributedString(s), []).width
        }
        let light = width(300), regular = width(400), black = width(900)
        XCTAssertLessThan(light, regular)
        XCTAssertLessThan(regular, black)
    }

    func testEveryFontFileLoads() {
        for fam in FontCatalog.shared.allFonts {
            for file in fam.files {
                XCTAssertNotNil(FontCatalog.shared.descriptor(for: file), file.path)
            }
        }
    }
}

final class AdjustTests: XCTestCase {
    func testComposedMatrixMatchesReference() {
        let a = ImageAdjust(brightness: 20, contrast: -30, saturation: 50, warmth: 40)
        let m = ImageAdjuster.matrix(a)
        for (r, g, b) in [(0.2, 0.5, 0.9), (1.0, 0.0, 0.3), (0.5, 0.5, 0.5)] {
            func row(_ v: [Double], _ bias: Double) -> Double { min(1, max(0, v[0] * r + v[1] * g + v[2] * b + bias)) }
            let ref = ImageAdjuster.applyReference(a, r: r, g: g, b: b)
            XCTAssertEqual(row(m.r, m.bias[0]), ref.0, accuracy: 1e-9)
            XCTAssertEqual(row(m.g, m.bias[1]), ref.1, accuracy: 1e-9)
            XCTAssertEqual(row(m.b, m.bias[2]), ref.2, accuracy: 1e-9)
        }
    }

    func testImageBoxAndTransform() {
        var p = ImageProps(assetRef: String(repeating: "a", count: 64) + ".png", naturalWidth: 64, naturalHeight: 48)
        p.crop = CropRect(x: 8, y: 4, width: 40, height: 30)
        p.rotate90 = 1
        XCTAssertEqual(p.boxSize, CGSize(width: 30, height: 40))
        let t = Renderer.imageContentTransform(p)
        // crop top-left goes to box top-right after a clockwise quarter turn
        XCTAssertEqual(CGPoint.zero.applying(t), CGPoint(x: 30, y: 0))
        p.flipH = true
        XCTAssertEqual(CGPoint.zero.applying(Renderer.imageContentTransform(p)), CGPoint(x: 0, y: 0))
    }
}

@MainActor
final class EditorRuleTests: XCTestCase {
    func model(width: Int = 1000) -> EditorModel {
        let store = ProjectStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("rule-\(UUID().uuidString)"))
        return EditorModel(doc: Document(canvas: Canvas(width: width, height: 800)), store: store)
    }

    /// §8.1: auto-width text grows up to canvas width − 5% each side, then wraps, inside the canvas.
    func testAutoWidthWrapsAtCanvasWidth() {
        let m = model()
        let id = m.addText(at: CGPoint(x: 900, y: 100), edit: false)
        m.replaceText(id, range: NSRange(location: 0, length: 0), with: "Short")
        XCTAssertEqual(m.doc.layer(id)?.text?.autoWidth, true)
        // Short text near the right edge is kept inside the margin.
        let l0 = m.doc.layer(id)!
        XCTAssertLessThanOrEqual(l0.transform.x + Double(LayerGeometry.boxSize(of: l0).width) / 2, 950.001)
        m.replaceText(id, range: NSRange(location: 5, length: 0), with: " and a very long caption that keeps going well past the edge of the canvas")
        let l = m.doc.layer(id)!
        XCTAssertEqual(l.text?.autoWidth, true, "stays auto-width (FORMAT §7.3 step 5 wraps it)")
        let size = LayerGeometry.boxSize(of: l)
        XCTAssertLessThanOrEqual(size.width, 900.01, "wraps at 0.9 × canvas width")
        XCTAssertGreaterThan(TextLayoutEngine.shared.layout(l.text!).lines.count, 1)
        XCTAssertGreaterThanOrEqual(l.transform.x - Double(size.width) / 2, 49.99, "kept inside the canvas")
        XCTAssertLessThanOrEqual(l.transform.x + Double(size.width) / 2, 950.01)
    }

    /// Spec §8.3: a preset with no text box selected creates one in that style.
    func testPresetWithoutSelectionCreatesTextBox() {
        let m = model()
        let meme = TextPresets.all.first { $0.id == "classic-meme" }!
        m.applyPreset(meme)
        XCTAssertEqual(m.doc.layers.count, 1)
        XCTAssertEqual(m.doc.layers[0].text?.fontId, "anton")
        // With a text box selected it only restyles (no new layers).
        let neon = TextPresets.all.first { $0.id == "neon" }!
        m.applyPreset(neon)
        XCTAssertEqual(m.doc.layers.count, 1)
        XCTAssertEqual(m.doc.layers[0].text?.fontId, "bebas-neue")
    }
}

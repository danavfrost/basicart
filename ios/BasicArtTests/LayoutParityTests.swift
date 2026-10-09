import XCTest
@testable import BasicArt

/// FORMAT.md §7.10: lays out every case in shared/fixtures/layout/cases.json with the real
/// engine and writes layout-results-ios.json for compare_layout.py.
final class LayoutParityTests: XCTestCase {
    let outDir = URL(fileURLWithPath: testOutputRoot + "/layout")

    func testWriteLayoutResults() throws {
        let url = Bundle(for: LayoutParityTests.self).url(forResource: "fixtures", withExtension: nil)!.appendingPathComponent("layout/cases.json")
        let root = try XCTUnwrap(JSONValue.parse(Data(contentsOf: url)).objectValue)
        let cases = try XCTUnwrap(root["cases"]?.arrayValue)
        var out: [JSONValue] = []
        for c in cases {
            let o = try XCTUnwrap(c.objectValue)
            let id = try XCTUnwrap(o["id"]?.stringValue)
            var layer = try XCTUnwrap(o["layer"]?.objectValue)
            if layer["text"] == nil { layer["text"] = .string("") }
            var t = try XCTUnwrap(ProjectCodec.textProps(fromPartial: layer), id)
            t.autoWidthLimit = 0.9 * (o["canvasWidth"]?.doubleValue ?? 1080)
            let l = TextLayoutEngine.shared.layout(t)
            let g = TextLayoutEngine.displayGraphemes(t)
            let lines: [JSONValue] = l.lines.map { line in
                let start = line.range.isEmpty ? (line.range.lowerBound < g.count ? g[line.range.lowerBound].source : (t.text as NSString).length) : g[line.range.lowerBound].source
                let end = line.range.isEmpty ? start : g[line.range.upperBound - 1].sourceEnd
                return .object(JSONObject([("start", .int(start)), ("end", .int(end)),
                                           ("x", .number(Double(line.x))), ("width", .number(Double(line.width))),
                                           ("top", .number(Double(line.top))), ("height", .number(Double(line.height))),
                                           ("baseline", .number(Double(line.baseline)))]))
            }
            out.append(.object(JSONObject([("id", .string(id)), ("w", .number(Double(l.size.width))),
                                           ("h", .number(Double(l.size.height))), ("lines", .array(lines))])))
            XCTAssertGreaterThan(l.size.width, 1, id)
        }
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0"
        let doc = JSONObject([("platform", .string("ios")), ("appVersion", .string(version)),
                              ("casesVersion", root["casesVersion"] ?? .int(1)), ("cases", .array(out))])
        try FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
        try JSONValue.object(doc).serialized().write(to: outDir.appendingPathComponent("layout-results-ios.json"))
    }

    /// Spot checks of the §7.3 rules independent of the other platform.
    func testRules() throws {
        func layout(_ json: String, canvas: Double = 1080) -> (TextProps, TextLayoutResult) {
            var t = ProjectCodec.textProps(fromPartial: (try! JSONValue.parse(Data(json.utf8))).objectValue!)!
            t.autoWidthLimit = 0.9 * canvas
            return (t, TextLayoutEngine.shared.layout(t))
        }
        // Auto-width wraps at 0.9 × canvas width.
        let (_, a) = layout(#"{"text":"This sentence is longer than ninety percent of the canvas","fontSize":64}"#, canvas: 600)
        XCTAssertGreaterThan(a.lines.count, 1)
        XCTAssertLessThanOrEqual(a.size.width, 540.01)
        // Hyphen break opportunities.
        let (t2, b) = layout(#"{"text":"state-of-the-art well-known","fontSize":48,"autoWidth":false,"boxWidth":200,"align":"left"}"#)
        let g = TextLayoutEngine.displayGraphemes(t2)
        XCTAssertTrue(b.lines.dropLast().contains { g[$0.range.upperBound - 1].text == "-" }, "breaks after a hyphen")
        // NBSP never breaks.
        let (_, c) = layout(#"{"text":"aaaa\u00a0bbbb","fontSize":48,"autoWidth":false,"boxWidth":60,"align":"left"}"#)
        XCTAssertFalse(c.lines.contains { $0.range.lowerBound == 5 })
        // Ligatures off: "fi" stays two glyphs.
        let (_, d) = layout(#"{"text":"fi","fontId":"eb-garamond","fontSize":80}"#)
        XCTAssertEqual(d.glyphs.count, 2)
        // Kerning on: "AV" is narrower than A + V measured apart.
        let (_, av) = layout(#"{"text":"AV","fontSize":96}"#)
        let (_, aa) = layout(#"{"text":"A","fontSize":96}"#)
        let (_, vv) = layout(#"{"text":"V","fontSize":96}"#)
        XCTAssertLessThan(av.size.width, aa.size.width + vv.size.width - 1)
    }
}

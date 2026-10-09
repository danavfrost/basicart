import XCTest
@testable import BasicArt

final class FixtureTests: XCTestCase {

    var fixturesURL: URL {
        Bundle(for: FixtureTests.self).url(forResource: "fixtures", withExtension: nil)!
    }

    var expectations: [String: Any] {
        let data = try! Data(contentsOf: fixturesURL.appendingPathComponent("expectations.json"))
        let root = try! JSONSerialization.jsonObject(with: data) as! [String: Any]
        return root["fixtures"] as! [String: Any]
    }

    func snapshot(_ folder: URL) -> [String: Data] {
        var out: [String: Data] = [:]
        let e = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: nil)!
        for case let url as URL in e {
            var isDir: ObjCBool = false
            FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir)
            if !isDir.boolValue { out[url.path] = try? Data(contentsOf: url) }
        }
        return out
    }

    func testEveryFixtureMatchesExpectations() throws {
        let exp = expectations
        XCTAssertGreaterThanOrEqual(exp.count, 11)
        let store = ProjectStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("fx-\(UUID().uuidString)"))
        for (name, raw) in exp.sorted(by: { $0.key < $1.key }) {
            let e = raw as! [String: Any]
            let folder = fixturesURL.appendingPathComponent(name)
            let before = snapshot(folder)
            let result = store.load(folder: folder)
            let summary = store.summary(folder: folder)
            XCTAssertEqual(snapshot(folder), before, "\(name): folder modified")
            switch e["result"] as! String {
            case "newerVersion":
                guard case .failure(.newerVersion) = result else { XCTFail("\(name) should be newerVersion: \(result)"); continue }
                XCTAssertEqual(summary.status, .newerVersion)
                XCTAssertEqual(summary.name, "From the future")
            case "corrupt":
                guard case .failure(.corrupt) = result else { XCTFail("\(name) should be corrupt: \(result)"); continue }
                XCTAssertEqual(summary.status, .corrupt)
            case "ok":
                guard case .success(let doc) = result else { XCTFail("\(name) should load: \(result)"); continue }
                XCTAssertEqual(summary.status, .ok)
                XCTAssertEqual(doc.id, name, "folder name wins")
                let canvas = e["canvas"] as! [Any]
                XCTAssertEqual(doc.canvas.width, canvas[0] as! Int)
                XCTAssertEqual(doc.canvas.height, canvas[1] as! Int)
                XCTAssertEqual(doc.canvas.background.hex, canvas[2] as! String)
                let layers = e["layers"] as! [[String: Any]]
                XCTAssertEqual(doc.layers.count, layers.count, name)
                for (l, le) in zip(doc.layers, layers) {
                    XCTAssertEqual(l.id, le["id"] as! String)
                    XCTAssertEqual(l.type.rawValue, le["type"] as! String)
                    XCTAssertEqual(l.visible, le["visible"] as! Bool)
                    XCTAssertEqual(l.locked, le["locked"] as! Bool)
                    if let box = le["box"] as? [Double] {
                        let size = LayerGeometry.boxSize(of: l)
                        XCTAssertEqual(Double(size.width), box[0], accuracy: 0.001, "\(name)/\(l.id) box w")
                        XCTAssertEqual(Double(size.height), box[1], accuracy: 0.001, "\(name)/\(l.id) box h")
                    }
                }
                if let spans = e["spans"] as? [String: [[Int]]] {
                    for (lid, ranges) in spans {
                        let t = doc.layer(lid)!.text!
                        XCTAssertEqual(t.spans.map { [$0.start, $0.end] }, ranges, "\(name)/\(lid) spans")
                    }
                }
                if let counts = e["strokeCounts"] as? [String: Int] {
                    for (lid, n) in counts {
                        XCTAssertEqual(doc.layer(lid)!.drawing!.strokes.count, n, "\(name)/\(lid) strokes")
                    }
                }
                if let counts = e["maskStrokeCounts"] as? [String: Int] {
                    for (lid, n) in counts {
                        XCTAssertEqual(doc.layer(lid)!.mask.count, n, "\(name)/\(lid) mask strokes")
                    }
                }
                if let missing = e["missingAssets"] as? [String] {
                    for lid in missing {
                        let ref = doc.layer(lid)!.image!.assetRef
                        XCTAssertFalse(FileManager.default.fileExists(atPath: folder.appendingPathComponent("assets/\(ref)").path))
                    }
                }
            default: XCTFail("unknown result")
            }
        }
    }

    func testRoundTripEveryOkFixture() throws {
        let tmpRoot = FileManager.default.temporaryDirectory.appendingPathComponent("rt-\(UUID().uuidString)")
        let store = ProjectStore(root: tmpRoot)
        defer { try? FileManager.default.removeItem(at: tmpRoot) }
        for (name, raw) in expectations where (raw as! [String: Any])["result"] as! String == "ok" {
            guard case .success(let doc) = store.load(folder: fixturesURL.appendingPathComponent(name)) else {
                XCTFail(name); continue
            }
            var written: [String: [Stroke]] = [:]
            try store.save(doc, writtenStrokes: &written)
            guard case .success(let again) = store.load(id: doc.id) else { XCTFail("reload \(name)"); continue }
            XCTAssertEqual(again.layers, doc.layers, "\(name) layers round-trip")
            XCTAssertEqual(again.canvas, doc.canvas)
            XCTAssertEqual(again.name, doc.name)
            XCTAssertEqual(again.created, doc.created)
            XCTAssertEqual(again.modified, doc.modified)
            // Semantic JSON equality with the fixture file after defaults: compare re-encoded forms.
            let a = JSONValue.object(ProjectCodec.encodeObject(doc))
            let b = JSONValue.object(ProjectCodec.encodeObject(again))
            XCTAssertEqual(a, b, name)
            // Second save is byte-identical (deterministic writer).
            let d1 = try Data(contentsOf: store.folder(for: doc.id).appendingPathComponent("project.json"))
            try store.save(again, writtenStrokes: &written)
            let d2 = try Data(contentsOf: store.folder(for: doc.id).appendingPathComponent("project.json"))
            XCTAssertEqual(d1, d2)
        }
    }

    func testRawFixtureSemanticEquality() throws {
        // Every key in the fixture's project.json must survive with the same value
        // after load → encode (the fixtures are written by a full writer).
        for name in ["01-all-layer-types", "02-text-effects", "03-rich-text-spans", "04-flags-shapes-image", "05-drawing-brushes", "06-missing-asset", "11-eraser-masks", "12-mixed-styles"] {
            let url = fixturesURL.appendingPathComponent(name).appendingPathComponent("project.json")
            let original = try JSONValue.parse(Data(contentsOf: url))
            guard case .success(let doc) = ProjectCodec.decode(try Data(contentsOf: url), folderID: name) else { XCTFail(name); continue }
            let encoded = JSONValue.object(ProjectCodec.encodeObject(doc))
            assertSubset(original, encoded, path: name, skip: ["generator"])
        }
    }

    private func assertSubset(_ a: JSONValue, _ b: JSONValue, path: String, skip: Set<String>) {
        switch (a, b) {
        case (.object(let oa), .object(let ob)):
            for k in oa.keys where !skip.contains(k) {
                guard let vb = ob[k] else { XCTFail("\(path).\(k) missing after round-trip"); continue }
                assertSubset(oa[k]!, vb, path: "\(path).\(k)", skip: skip)
            }
        case (.array(let xa), .array(let xb)):
            XCTAssertEqual(xa.count, xb.count, path)
            for (i, (x, y)) in zip(xa, xb).enumerated() { assertSubset(x, y, path: "\(path)[\(i)]", skip: skip) }
        case (.number(let x), .number(let y)):
            XCTAssertEqual(x, y, accuracy: 0.0001, path)
        case (.string(let x), .string(let y)):
            XCTAssertEqual(x, y, path)
        default:
            XCTAssertEqual(a, b, path)
        }
    }

    func testUnknownFontIdKeptOnSave() throws {
        let url = fixturesURL.appendingPathComponent("02-text-effects")
        guard case .success(let doc) = ProjectStore(root: FileManager.default.temporaryDirectory).load(folder: url) else { return XCTFail() }
        let t = doc.layer("t-case")!.text!
        XCTAssertEqual(t.fontId, "no-such-font")
        let enc = ProjectCodec.encodeObject(doc)
        let layer = enc["layers"]!.arrayValue!.last!.objectValue!
        XCTAssertEqual(layer["fontId"], .string("no-such-font"))
        XCTAssertEqual(FontCatalog.shared.font(id: "no-such-font").id, "inter")
    }

    func testFixtureTextBoxesMeasureNonZero() throws {
        for name in ["01-all-layer-types", "02-text-effects", "03-rich-text-spans", "12-mixed-styles"] {
            guard case .success(let doc) = ProjectStore(root: FileManager.default.temporaryDirectory)
                .load(folder: fixturesURL.appendingPathComponent(name)) else { XCTFail(name); continue }
            for l in doc.layers where l.type == .text {
                let size = LayerGeometry.boxSize(of: l)
                XCTAssertGreaterThan(size.width, 10, "\(name)/\(l.id)")
                let t = l.text!
                let layout = TextLayoutEngine.shared.layout(t)
                XCTAssertEqual(size.height, layout.lines.map(\.height).reduce(0, +), accuracy: 0.01)
                for line in layout.lines {
                    let sizes = layout.lines.isEmpty ? [] : Array(line.range).map { TextLayoutEngine.displayGraphemes(t)[$0].style.size }
                    XCTAssertEqual(Double(line.height), t.lineHeight * (sizes.max() ?? t.fontSize), accuracy: 0.01)
                }
            }
        }
    }

    func testRendersEveryOkFixture() throws {
        let store = ProjectStore(root: FileManager.default.temporaryDirectory)
        for name in ["01-all-layer-types", "02-text-effects", "03-rich-text-spans", "04-flags-shapes-image", "05-drawing-brushes", "06-missing-asset", "11-eraser-masks", "12-mixed-styles"] {
            let folder = fixturesURL.appendingPathComponent(name)
            guard case .success(let doc) = store.load(folder: folder) else { XCTFail(name); continue }
            let assets = AssetProvider(folder: folder.appendingPathComponent("assets"))
            let img = Renderer.renderImage(doc, scale: 1, assets: assets)
            XCTAssertEqual(img?.width, doc.canvas.width)
            XCTAssertEqual(img?.height, doc.canvas.height)
            // Golden renders for manual cross-platform review.
            let out = URL(fileURLWithPath: testOutputRoot + "/ios-shots/fixtures")
            if let img, (try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)) != nil,
               let data = ImageEncoder.png(img) {
                try? data.write(to: out.appendingPathComponent("\(name).png"))
            }
        }
    }
}

import XCTest
@testable import BasicArt

final class PackageTests: XCTestCase {
    var fixtures: URL { Bundle(for: PackageTests.self).url(forResource: "fixtures", withExtension: nil)! }
    let xplat = URL(fileURLWithPath: testOutputRoot + "/xplat")

    func tempStore() -> ProjectStore {
        ProjectStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("pkg-\(UUID().uuidString)"))
    }

    func folderNames(_ store: ProjectStore) -> [String] {
        (try? FileManager.default.contentsOfDirectory(atPath: store.root.path)) ?? []
    }

    func fixtureDoc(_ name: String) -> Document {
        guard case .success(let d) = ProjectStore(root: FileManager.default.temporaryDirectory).load(folder: fixtures.appendingPathComponent(name)) else { fatalError(name) }
        return d
    }

    func testSharedPackagesMatchExpectations() throws {
        let data = try Data(contentsOf: fixtures.appendingPathComponent("expectations.json"))
        let exp = (try JSONSerialization.jsonObject(with: data) as! [String: Any])["packages"] as! [String: [String: Any]]
        XCTAssertEqual(exp.count, 6)
        for (name, e) in exp.sorted(by: { $0.key < $1.key }) {
            let store = tempStore()
            // An existing project with the same name to exercise "Name (2)".
            var existing = Document(name: "Mixed styles", canvas: Canvas(width: 100, height: 100))
            existing.created = Date()
            var w: [String: [Stroke]] = [:]
            try store.save(existing, writtenStrokes: &w)
            let before = folderNames(store)
            let result = ProjectPackage.importPackage(at: fixtures.appendingPathComponent("packages/\(name)"), store: store)
            if e["result"] as! String == "ok" {
                guard case .success(let r) = result else { XCTFail("\(name): \(result)"); continue }
                XCTAssertNotEqual(r.projectID, existing.id)
                XCTAssertEqual(r.projectID, r.projectID.lowercased())
                XCTAssertNotNil(UUID(uuidString: r.projectID), name)
                guard case .success(let doc) = store.load(id: r.projectID) else { XCTFail(name); continue }
                let src = fixtureDoc(e["source"] as! String)
                XCTAssertEqual(doc.layers, src.layers, "\(name) layers identical")
                if src.name == "Mixed styles" { XCTAssertEqual(doc.name, "Mixed styles (2)") }
                let state = store.loadEditorState(id: r.projectID)
                XCTAssertEqual(state != nil, e["editorState"] as! Bool, name)
                if name.contains("12") {
                    XCTAssertEqual(state?.selectedLayerIds, ["t-styled"])
                    XCTAssertEqual(state?.activeTool, .text)
                    XCTAssertEqual(state?.textTab, .color)
                    XCTAssertEqual(state?.zoom, 1.5)
                    XCTAssertEqual(state?.validTextSelection(in: doc)?.1, NSRange(location: 4, length: 4))
                }
                let all = FileManager.default.enumerator(atPath: store.folder(for: r.projectID).path)?.allObjects as? [String] ?? []
                XCTAssertFalse(all.contains { $0.contains("__MACOSX") || $0.hasSuffix(".DS_Store") }, "junk skipped")
                XCTAssertTrue(FileManager.default.fileExists(atPath: store.folder(for: r.projectID).appendingPathComponent("thumb.png").path))
            } else {
                guard case .failure(let reason) = result else { XCTFail("\(name) should be rejected"); continue }
                let reasons = e["reasons"] as! [String]
                XCTAssertTrue(reasons.contains(reason.rawValue), "\(name): got \(reason)")
                XCTAssertEqual(folderNames(store).sorted(), before.sorted(), "\(name): nothing left behind")
                XCTAssertFalse(reason.message.isEmpty)
            }
            XCTAssertFalse(folderNames(store).contains { $0.hasPrefix(".import-") })
        }
    }

    func testExportImportRoundTripAndCrossPlatform() throws {
        // Put fixture 12 (with its editor state) into a store as a real project.
        let store = tempStore()
        let id = Document.newID()
        try FileManager.default.copyItem(at: fixtures.appendingPathComponent("12-mixed-styles"), to: store.folder(for: id))
        let url = try ProjectPackage.export(id: id, store: store)
        XCTAssertEqual(url.lastPathComponent, "Mixed styles.zip")
        // The package passes our own §13.2 scan and imports as a new project.
        let reader = try ZipReader(url: url)
        XCTAssertEqual(reader.entries.first?.name, "basicart-package.json")
        XCTAssertTrue(ProjectPackage.scan(reader.entries).isEmpty)
        guard case .success(let r) = ProjectPackage.importPackage(at: url, store: store) else { return XCTFail() }
        guard case .success(let a) = store.load(id: id), case .success(let b) = store.load(id: r.projectID) else { return XCTFail() }
        XCTAssertEqual(a.layers, b.layers)
        XCTAssertEqual(b.name, "Mixed styles (2)")
        XCTAssertEqual(store.loadEditorState(id: id), store.loadEditorState(id: r.projectID))
        // Cross-platform: hand ours to Android, read theirs.
        try FileManager.default.createDirectory(at: xplat.appendingPathComponent("ios-out"), withIntermediateDirectories: true)
        let out = xplat.appendingPathComponent("ios-out/pkg-12.zip")
        try? FileManager.default.removeItem(at: out)
        try FileManager.default.copyItem(at: url, to: out)
        let android = xplat.appendingPathComponent("android-out/pkg-12.zip")
        if FileManager.default.fileExists(atPath: android.path) {
            let s2 = tempStore()
            guard case .success(let ra) = ProjectPackage.importPackage(at: android, store: s2) else { return XCTFail("android pkg-12 rejected") }
            guard case .success(let d) = s2.load(id: ra.projectID) else { return XCTFail() }
            XCTAssertEqual(d.layers.map(\.id), a.layers.map(\.id))
            XCTAssertEqual(d.layers, a.layers, "Android package layers identical to fixture 12")
            print("XPLAT imported android pkg-12: \(d.name), editor state: \(s2.loadEditorState(id: ra.projectID) != nil)")
        }
    }

    func testZipWriterReaderRoundTrip() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("z-\(UUID().uuidString).zip")
        let w = try ZipWriter(url: url)
        let big = Data(repeating: 65, count: 200_000)
        let small = Data("hi".utf8)
        var random = Data(count: 50_000); random.withUnsafeMutableBytes { for i in 0..<$0.count { $0[i] = UInt8.random(in: 0...255) } }
        try w.add(name: "a/big.txt", data: big)
        try w.add(name: "small.txt", data: small)
        try w.add(name: "r.bin", data: random)
        try w.finish()
        let r = try ZipReader(url: url)
        XCTAssertEqual(r.entries.map(\.name), ["a/big.txt", "small.txt", "r.bin"])
        XCTAssertEqual(try r.data(r.entries[0]), big)
        XCTAssertEqual(r.entries[0].method, 8)
        XCTAssertEqual(try r.data(r.entries[1]), small)
        XCTAssertEqual(try r.data(r.entries[2]), random)
    }

    func testFileNameAndStagingCleanup() {
        XCTAssertEqual(ProjectPackage.fileName(for: "a/b:c?"), "a_b_c_.zip")
        XCTAssertEqual(ProjectPackage.fileName(for: "   "), "Basic Art project.zip")
        XCTAssertEqual(ProjectPackage.fileName(for: String(repeating: "x", count: 100)).count, 84)
        let store = tempStore()
        try? FileManager.default.createDirectory(at: store.root.appendingPathComponent(".import-abc"), withIntermediateDirectories: true)
        ProjectPackage.cleanupStaging(store: store)
        XCTAssertTrue(folderNames(store).isEmpty)
        XCTAssertTrue(store.listProjects().isEmpty)
    }

    func testEditorStateLenientParse() {
        let s = EditorState.decode(Data(#"{"selectedLayerIds":["a",3],"activeTool":"laser","textTab":"color","view":{"zoom":99,"centerX":1,"centerY":2},"textSelection":{"layerId":"a","start":5,"end":2}}"#.utf8))!
        XCTAssertEqual(s.selectedLayerIds, ["a"])
        XCTAssertNil(s.activeTool)
        XCTAssertEqual(s.textTab, .color)
        XCTAssertNil(s.zoom)
        XCTAssertNil(s.textSelection)
        XCTAssertNil(EditorState.decode(Data("nope".utf8)))
    }

    /// QA2 B1: masks on image, text and shape layers all survive export → import.
    func testMasksOnEveryLayerTypeRoundTrip() throws {
        let store = tempStore()
        var doc = Document(canvas: Canvas(width: 400, height: 300))
        let png = ImageEncoder.png(Renderer.makeContext(width: 8, height: 8)!.makeImage()!)!
        let ref = try store.storeAsset(png, ext: "png", projectID: doc.id)
        let m = [Stroke(brush: .eraser, size: 10, points: [CGPoint(x: 2, y: 3), CGPoint(x: 6, y: 7)])]
        var img = Layer(id: "img", name: "Photo", transform: Transform(x: 100, y: 100, scale: 10, rotation: 0),
                        content: .image(ImageProps(assetRef: ref, naturalWidth: 8, naturalHeight: 8)))
        img.mask = m
        var txt = Layer(id: "txt", name: "Text", transform: Transform(x: 200, y: 200), content: .text(TextProps(text: "Hi")))
        txt.mask = m
        var shp = Layer(id: "shp", name: "Shape", transform: Transform(x: 300, y: 100),
                        content: .shape(ShapeProps(shape: .ellipse, width: 80, height: 60)))
        shp.mask = m
        doc.layers = [img, txt, shp]
        var w: [String: [Stroke]] = [:]
        try store.save(doc, writtenStrokes: &w)
        let url = try ProjectPackage.export(id: doc.id, store: store)
        let names = try ZipReader(url: url).entries.map(\.name)
        for id in ["img", "txt", "shp"] { XCTAssertTrue(names.contains("strokes/\(id).mask.json"), "\(id) mask packaged") }
        guard case .success(let r) = ProjectPackage.importPackage(at: url, store: store),
              case .success(let back) = store.load(id: r.projectID) else { return XCTFail() }
        for id in ["img", "txt", "shp"] { XCTAssertEqual(back.layer(id)?.mask, m, "\(id) mask round-trips") }
    }

    func testExportTempFilesAreCleanedUp() throws {
        let fm = FileManager.default
        // An exported project zip goes away with its private folder once discarded.
        let store = tempStore()
        let id = Document.newID()
        try fm.copyItem(at: fixtures.appendingPathComponent("12-mixed-styles"), to: store.folder(for: id))
        let url = try ProjectPackage.export(id: id, store: store)
        XCTAssertTrue(fm.fileExists(atPath: url.path))
        ProjectPackage.discardExport(url)
        XCTAssertFalse(fm.fileExists(atPath: url.deletingLastPathComponent().path))
        // The launch sweep removes export/import leftovers and nothing else.
        let tmp = fm.temporaryDirectory.appendingPathComponent("sweep-\(UUID().uuidString)")
        for d in ["ProjectExport-A", "Export", "keep-dir"] {
            try fm.createDirectory(at: tmp.appendingPathComponent(d), withIntermediateDirectories: true)
        }
        for f in ["import-B.zip", "keep.zip"] { try Data([1]).write(to: tmp.appendingPathComponent(f)) }
        ProjectPackage.sweepExportTemp(in: tmp)
        XCTAssertEqual(Set(try fm.contentsOfDirectory(atPath: tmp.path)), ["keep-dir", "keep.zip"])
        try? fm.removeItem(at: tmp)
    }
}

import CoreGraphics
import Foundation

/// Why a project can't be opened.
enum LoadFailure: Error, Equatable {
    case newerVersion(name: String?)
    case corrupt(String)
}

private struct Corrupt: Error { let reason: String }

/// Strict field reader: missing → default, present with wrong type → corrupt.
private struct Reader {
    let obj: JSONObject
    let path: String

    init(_ v: JSONValue?, _ path: String) throws {
        guard let o = v?.objectValue else { throw Corrupt(reason: "\(path) is not an object") }
        self.obj = o
        self.path = path
    }

    init(object: JSONObject, path: String) { self.obj = object; self.path = path }

    func has(_ k: String) -> Bool { if let v = obj[k], v != .null { return true }; return false }

    private func value(_ k: String) -> JSONValue? {
        guard let v = obj[k], v != .null else { return nil }
        return v
    }

    func double(_ k: String) throws -> Double? {
        guard let v = value(k) else { return nil }
        guard let d = v.doubleValue, d.isFinite else { throw Corrupt(reason: "\(path).\(k) not a number") }
        return d
    }
    func double(_ k: String, _ def: Double) throws -> Double { try double(k) ?? def }
    func reqDouble(_ k: String) throws -> Double {
        guard let d = try double(k) else { throw Corrupt(reason: "\(path).\(k) missing") }
        return d
    }

    func int(_ k: String) throws -> Int? {
        guard let v = value(k) else { return nil }
        guard let i = v.intValue else { throw Corrupt(reason: "\(path).\(k) not an int") }
        return i
    }
    func int(_ k: String, _ def: Int) throws -> Int { try int(k) ?? def }
    func reqInt(_ k: String) throws -> Int {
        guard let i = try int(k) else { throw Corrupt(reason: "\(path).\(k) missing") }
        return i
    }

    func bool(_ k: String, _ def: Bool) throws -> Bool {
        guard let v = value(k) else { return def }
        guard let b = v.boolValue else { throw Corrupt(reason: "\(path).\(k) not a bool") }
        return b
    }

    func string(_ k: String) throws -> String? {
        guard let v = value(k) else { return nil }
        guard let s = v.stringValue else { throw Corrupt(reason: "\(path).\(k) not a string") }
        return s
    }
    func string(_ k: String, _ def: String) throws -> String { try string(k) ?? def }
    func reqString(_ k: String) throws -> String {
        guard let s = try string(k) else { throw Corrupt(reason: "\(path).\(k) missing") }
        return s
    }

    func color(_ k: String, _ def: RGBA) throws -> RGBA {
        guard let s = try string(k) else { return def }
        // A malformed colour string falls back to the field default (cross-platform decision);
        // a non-string value is still a wrong type (corrupt).
        return RGBA(hex: s) ?? def
    }

    /// Enum: unknown values fall back to the default (only type/shape/brush are fatal).
    func enumValue<E: RawRepresentable>(_ k: String, _ def: E) throws -> E where E.RawValue == String {
        guard let s = try string(k) else { return def }
        return E(rawValue: s) ?? def
    }

    func object(_ k: String) throws -> Reader? {
        guard let v = value(k) else { return nil }
        return try Reader(v, "\(path).\(k)")
    }

    func array(_ k: String) throws -> [JSONValue]? {
        guard let v = value(k) else { return nil }
        guard let a = v.arrayValue else { throw Corrupt(reason: "\(path).\(k) not an array") }
        return a
    }
}

private func clamp(_ v: Double, _ lo: Double, _ hi: Double) -> Double { min(hi, max(lo, v)) }
private func clamp(_ v: Int, _ lo: Int, _ hi: Int) -> Int { min(hi, max(lo, v)) }

private let idPattern = try! NSRegularExpression(pattern: "^[A-Za-z0-9_-]{1,64}$")
private let assetPattern = try! NSRegularExpression(pattern: "^[0-9a-f]{64}\\.(png|jpg|webp|gif|heic)$")

private func matches(_ re: NSRegularExpression, _ s: String) -> Bool {
    re.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil
}

enum ISODate {
    private static let withFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let plain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()
    static func parse(_ s: String) -> Date? { withFraction.date(from: s) ?? plain.date(from: s) }
    static func format(_ d: Date) -> String {
        let whole = d.timeIntervalSince1970.rounded(.down)
        if d.timeIntervalSince1970 - whole < 0.0005 { return plain.string(from: d) }
        return withFraction.string(from: d)
    }
}

enum ProjectCodec {

    // MARK: - Classification / reading

    /// Check order (§10.2): parse → formatVersion → validate → migrate → load.
    static func decode(_ data: Data, folderID: String?, fileDate: Date = Date()) -> Result<Document, LoadFailure> {
        let root: JSONValue
        do { root = try JSONValue.parse(data) } catch { return .failure(.corrupt("invalid JSON")) }
        guard let obj = root.objectValue else { return .failure(.corrupt("not an object")) }
        if let v = obj["formatVersion"], let ver = v.intValue, ver > Document.supportedFormatVersion {
            return .failure(.newerVersion(name: obj["name"]?.stringValue))
        }
        do {
            var doc = try readDocument(Reader(object: obj, path: "$"), folderID: folderID, fileDate: fileDate)
            doc.syncTextLimits()
            return .success(doc)
        } catch let c as Corrupt {
            return .failure(.corrupt(c.reason))
        } catch {
            return .failure(.corrupt("\(error)"))
        }
    }

    private static func readDocument(_ r: Reader, folderID: String?, fileDate: Date) throws -> Document {
        guard let version = try r.int("formatVersion") else { throw Corrupt(reason: "formatVersion missing") }
        guard version >= 1 else { throw Corrupt(reason: "formatVersion < 1") }
        // No migrations exist in v1.
        let storedID = try r.reqString("id")
        guard let cr = try r.object("canvas") else { throw Corrupt(reason: "canvas missing") }
        let cw = try cr.reqInt("width"), ch = try cr.reqInt("height")
        guard (Canvas.minSide...Canvas.maxSide).contains(cw), (Canvas.minSide...Canvas.maxSide).contains(ch) else {
            throw Corrupt(reason: "canvas size out of range")
        }
        let canvas = Canvas(width: cw, height: ch, background: try cr.color("background", .white))
        var doc = Document(id: folderID ?? storedID, canvas: canvas)
        var name = try r.string("name", "Untitled").trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty { name = "Untitled" }
        if name.count > 100 { name = String(name.prefix(100)) }
        doc.name = name
        doc.created = try r.string("created").flatMap(ISODate.parse) ?? fileDate
        doc.modified = try r.string("modified").flatMap(ISODate.parse) ?? fileDate
        doc.generator = try r.string("generator", "")
        guard let layersJSON = try r.array("layers") else { throw Corrupt(reason: "layers missing") }
        var seen = Set<String>()
        for (i, lj) in layersJSON.enumerated() {
            let layer = try readLayer(Reader(lj, "$.layers[\(i)]"), canvas: canvas)
            guard seen.insert(layer.id).inserted else { throw Corrupt(reason: "duplicate layer id \(layer.id)") }
            doc.layers.append(layer)
        }
        return doc
    }

    private static func readLayer(_ r: Reader, canvas: Canvas) throws -> Layer {
        let id = try r.reqString("id")
        guard matches(idPattern, id) else { throw Corrupt(reason: "bad layer id") }
        let typeStr = try r.reqString("type")
        guard let type = LayerType(rawValue: typeStr) else { throw Corrupt(reason: "unknown layer type \(typeStr)") }
        guard let tr = try r.object("transform") else { throw Corrupt(reason: "transform missing") }
        let transform = Transform(
            x: try tr.double("x", Double(canvas.width) / 2),
            y: try tr.double("y", Double(canvas.height) / 2),
            scale: clamp(try tr.double("scale", 1), 0.01, 100),
            rotation: Transform.normalizeDegrees(try tr.double("rotation", 0)))
        let content: LayerContent
        switch type {
        case .image: content = .image(try readImage(r))
        case .text: content = .text(try readText(r))
        case .drawing: content = .drawing(try readDrawing(r))
        case .shape: content = .shape(try readShape(r))
        }
        var name = try r.string("name", type.displayName)
        if name.count > 100 { name = String(name.prefix(100)) }
        return Layer(id: id, name: name,
                     visible: try r.bool("visible", true),
                     locked: try r.bool("locked", false),
                     opacity: clamp(try r.double("opacity", 1), 0, 1),
                     blendMode: "normal",
                     transform: transform, content: content)
    }

    private static func readImage(_ r: Reader) throws -> ImageProps {
        let ref = try r.reqString("assetRef")
        guard matches(assetPattern, ref) else { throw Corrupt(reason: "bad assetRef") }
        let nw = max(1, try r.reqInt("naturalWidth"))
        let nh = max(1, try r.reqInt("naturalHeight"))
        var p = ImageProps(assetRef: ref, naturalWidth: nw, naturalHeight: nh)
        if let c = try r.object("crop") {
            var x = clamp(try c.double("x", 0), 0, Double(nw - 1))
            var y = clamp(try c.double("y", 0), 0, Double(nh - 1))
            var w = try c.double("width", Double(nw))
            var h = try c.double("height", Double(nh))
            x = max(0, x); y = max(0, y)
            w = clamp(w, 1, Double(nw) - x)
            h = clamp(h, 1, Double(nh) - y)
            p.crop = CropRect(x: x, y: y, width: w, height: h)
        }
        p.rotate90 = ((try r.int("rotate90", 0) % 4) + 4) % 4
        p.flipH = try r.bool("flipH", false)
        p.flipV = try r.bool("flipV", false)
        if let a = try r.object("adjust") {
            p.adjust = ImageAdjust(brightness: clamp(try a.double("brightness", 0), -100, 100),
                                   contrast: clamp(try a.double("contrast", 0), -100, 100),
                                   saturation: clamp(try a.double("saturation", 0), -100, 100),
                                   warmth: clamp(try a.double("warmth", 0), -100, 100))
        }
        p.cornerRadius = max(0, try r.double("cornerRadius", 0))
        if let b = try r.object("border") {
            p.border = ImageBorder(enabled: try b.bool("enabled", false),
                                   width: max(0, try b.double("width", 0)),
                                   color: try b.color("color", .white))
        }
        return p
    }

    private static func readFlags(_ r: Reader) throws -> StyleFlags {
        StyleFlags(bold: try r.bool("bold", false), italic: try r.bool("italic", false),
                   underline: try r.bool("underline", false), strike: try r.bool("strike", false))
    }

    fileprivate static func readFill(_ r: Reader?) throws -> TextFill {
        var f = TextFill()
        guard let r else { return f }
        f.type = try r.enumValue("type", FillType.solid)
        f.color = try r.color("color", .black)
        f.angle = try r.double("angle", 90)
        if let stops = try r.array("stops") {
            var parsed: [GradientStop] = []
            for (i, s) in stops.enumerated() {
                let sr = try Reader(s, "\(r.path).stops[\(i)]")
                parsed.append(GradientStop(offset: clamp(try sr.double("offset", 0), 0, 1),
                                           color: try sr.color("color", .black)))
            }
            parsed.sort { $0.offset < $1.offset }
            if parsed.count > 3 { parsed = Array(parsed.prefix(3)) }
            if parsed.count >= 2 { f.stops = parsed }
            else if parsed.count == 1 { f.stops = [parsed[0], GradientStop(offset: 1, color: parsed[0].color)] }
        }
        return f
    }

    private static func readText(_ r: Reader) throws -> TextProps {
        var t = TextProps(text: try r.reqString("text"))
        t.fontId = try r.string("fontId", "inter")
        t.fontSize = clamp(try r.double("fontSize", 64), 4, 2000)
        t.weight = clamp(try r.int("weight", 400), 100, 900)
        t.layerFlags = try readFlags(r)
        t.align = try r.enumValue("align", TextAlign.center)
        t.letterSpacing = clamp(try r.double("letterSpacing", 0), -0.5, 2)
        t.lineHeight = clamp(try r.double("lineHeight", 1.2), 0.5, 4)
        t.textCase = try r.enumValue("textCase", TextCase.none)
        t.autoWidth = try r.bool("autoWidth", true)
        t.boxWidth = max(0, try r.double("boxWidth", 0))
        if !t.autoWidth && t.boxWidth < 1 { t.boxWidth = 1 }
        t.fill = try readFill(try r.object("fill"))
        if let o = try r.object("outline") {
            t.outline = TextOutline(enabled: try o.bool("enabled", false),
                                    style: try o.enumValue("style", OutlineStyle.solid),
                                    color: try o.color("color", .black),
                                    width: clamp(try o.double("width", 0.06), 0, 0.5),
                                    color2: try o.color("color2", .white),
                                    width2: clamp(try o.double("width2", 0.06), 0, 0.5),
                                    glowRadius: clamp(try o.double("glowRadius", 0.3), 0, 2),
                                    join: try o.enumValue("join", LineJoin.round))
        }
        if let s = try r.object("shadow") {
            t.shadow = TextShadow(enabled: try s.bool("enabled", false),
                                  color: try s.color("color", RGBA(hex: "#00000080")!),
                                  blur: clamp(try s.double("blur", 0.1), 0, 2),
                                  offsetX: clamp(try s.double("offsetX", 0.05), -2, 2),
                                  offsetY: clamp(try s.double("offsetY", 0.05), -2, 2))
        }
        if let b = try r.object("backgroundBox") {
            t.backgroundBox = TextBackgroundBox(enabled: try b.bool("enabled", false),
                                                color: try b.color("color", RGBA(hex: "#00000099")!),
                                                padding: max(0, try b.double("padding", 0.25)),
                                                cornerRadius: max(0, try b.double("cornerRadius", 0.15)))
        }
        t.curve = clamp(try r.double("curve", 0), -100, 100)
        t.skew = clamp(try r.double("skew", 0), -45, 45)
        var spans: [TextSpan] = []
        if let arr = try r.array("spans") {
            for (i, sj) in arr.enumerated() {
                let sr = try Reader(sj, "\(r.path).spans[\(i)]")
                var st = CharStyle()
                if sr.has("bold") { st.bold = try sr.bool("bold", false) }
                if sr.has("italic") { st.italic = try sr.bool("italic", false) }
                if sr.has("underline") { st.underline = try sr.bool("underline", false) }
                if sr.has("strike") { st.strike = try sr.bool("strike", false) }
                if let c = try sr.string("color") { st.color = RGBA(hex: c) } // malformed → absent
                st.fontId = try sr.string("fontId")
                st.weight = try sr.int("weight").map { clamp($0, 100, 900) }
                st.size = try sr.double("size").map { clamp($0, 4, 2000) }
                spans.append(TextSpan(start: try sr.reqInt("start"), end: try sr.reqInt("end"), style: st))
            }
        }
        t.spans = Spans.normalize(t, spans: spans)
        return t
    }

    private static func readDrawing(_ r: Reader) throws -> DrawingProps {
        DrawingProps(width: clamp(try r.reqInt("width"), 1, 8192),
                     height: clamp(try r.reqInt("height"), 1, 8192))
    }

    private static func readShape(_ r: Reader) throws -> ShapeProps {
        let s = try r.reqString("shape")
        guard let kind = ShapeKind(rawValue: s) else { throw Corrupt(reason: "unknown shape \(s)") }
        var p = ShapeProps(shape: kind, width: max(1, try r.reqDouble("width")), height: max(1, try r.reqDouble("height")))
        if let f = try r.object("fill") {
            p.fill = ShapeFill(enabled: try f.bool("enabled", true), color: try f.color("color", ShapeFill().color))
        }
        if let st = try r.object("stroke") {
            p.stroke = ShapeStroke(enabled: try st.bool("enabled", false), color: try st.color("color", .black),
                                   width: clamp(try st.double("width", 8), 0, 500),
                                   join: try st.enumValue("join", LineJoin.miter))
        }
        p.cornerRadius = max(0, try r.double("cornerRadius", 24))
        p.arrowHeads = try r.enumValue("arrowHeads", ArrowHeads.end)
        return p
    }

    // MARK: - Strokes file

    /// Returns nil when the file is invalid (caller treats the layer as empty).
    static func decodeStrokes(_ data: Data) -> [Stroke]? {
        guard let root = try? JSONValue.parse(data), let obj = root.objectValue else { return nil }
        do {
            let r = Reader(object: obj, path: "strokes")
            if let v = try r.int("formatVersion"), v > Document.supportedFormatVersion { return nil }
            guard let arr = try r.array("strokes") else { return nil }
            var out: [Stroke] = []
            out.reserveCapacity(arr.count)
            for (i, sj) in arr.enumerated() {
                let sr = try Reader(sj, "strokes[\(i)]")
                guard let brush = BrushType(rawValue: try sr.reqString("brush")) else { return nil }
                guard let pts = try sr.array("points"), pts.count >= 2, pts.count % 2 == 0 else { return nil }
                var points: [CGPoint] = []
                points.reserveCapacity(pts.count / 2)
                var k = 0
                while k < pts.count {
                    guard let x = pts[k].doubleValue, let y = pts[k + 1].doubleValue else { return nil }
                    points.append(CGPoint(x: x, y: y))
                    k += 2
                }
                var pressure: [Double]? = nil
                if let pr = try sr.array("pressure") {
                    let vals = pr.compactMap { $0.doubleValue }.map { clamp($0, 0, 1) }
                    if vals.count == points.count { pressure = vals }
                }
                out.append(Stroke(brush: brush,
                                  size: clamp(try sr.double("size", 12), 1, 200),
                                  color: try sr.color("color", .black),
                                  opacity: clamp(try sr.double("opacity", 1), 0, 1),
                                  points: points, pressure: pressure))
            }
            return out
        } catch {
            return nil
        }
    }

    /// Mask file (§9.1): a strokes file whose strokes are all erasers; nil if invalid.
    static func decodeMask(_ data: Data) -> [Stroke]? {
        guard let strokes = decodeStrokes(data), strokes.allSatisfy({ $0.brush == .eraser }) else { return nil }
        return strokes
    }

    static func encodeStrokes(layerID: String, strokes: [Stroke]) -> Data {
        var root = JSONObject()
        root["formatVersion"] = .int(1)
        root["layerId"] = .string(layerID)
        root["strokes"] = .array(strokes.map { s in
            var o = JSONObject()
            o["brush"] = .string(s.brush.rawValue)
            o["size"] = .num(s.size)
            o["color"] = .string(s.color.hex)
            o["opacity"] = .num(s.opacity)
            var flat: [JSONValue] = []
            flat.reserveCapacity(s.points.count * 2)
            for p in s.points { flat.append(.number(round2(p.x))); flat.append(.number(round2(p.y))) }
            o["points"] = .array(flat)
            if let pr = s.pressure { o["pressure"] = .array(pr.map { .number(round4($0)) }) }
            return .object(o)
        })
        return JSONValue.object(root).serialized()
    }

    // MARK: - Writing

    static func encode(_ doc: Document) -> Data { JSONValue.object(encodeObject(doc)).serialized() }

    static func encodeObject(_ doc: Document) -> JSONObject {
        var o = JSONObject()
        o["formatVersion"] = .int(Document.supportedFormatVersion)
        o["id"] = .string(doc.id)
        o["name"] = .string(doc.name)
        o["created"] = .string(ISODate.format(doc.created))
        o["modified"] = .string(ISODate.format(doc.modified))
        o["generator"] = .string(doc.generator)
        o["canvas"] = .object(JSONObject([("width", .int(doc.canvas.width)),
                                          ("height", .int(doc.canvas.height)),
                                          ("background", .string(doc.canvas.background.hex))]))
        o["layers"] = .array(doc.layers.map { .object(encodeLayer($0)) })
        return o
    }

    static func encodeLayer(_ l: Layer) -> JSONObject {
        var o = JSONObject()
        o["id"] = .string(l.id)
        o["type"] = .string(l.type.rawValue)
        o["name"] = .string(l.name)
        o["visible"] = .bool(l.visible)
        o["locked"] = .bool(l.locked)
        o["opacity"] = .num(l.opacity)
        o["blendMode"] = .string("normal")
        o["transform"] = .object(JSONObject([("x", .num(l.transform.x)), ("y", .num(l.transform.y)),
                                             ("scale", .num(l.transform.scale)),
                                             ("rotation", .num(Transform.normalizeDegrees(l.transform.rotation)))]))
        switch l.content {
        case .image(let p):
            o["assetRef"] = .string(p.assetRef)
            o["naturalWidth"] = .int(p.naturalWidth)
            o["naturalHeight"] = .int(p.naturalHeight)
            o["crop"] = .object(JSONObject([("x", .num(p.crop.x)), ("y", .num(p.crop.y)),
                                            ("width", .num(p.crop.width)), ("height", .num(p.crop.height))]))
            o["rotate90"] = .int(p.rotate90)
            o["flipH"] = .bool(p.flipH)
            o["flipV"] = .bool(p.flipV)
            o["adjust"] = .object(JSONObject([("brightness", .num(p.adjust.brightness)), ("contrast", .num(p.adjust.contrast)),
                                              ("saturation", .num(p.adjust.saturation)), ("warmth", .num(p.adjust.warmth))]))
            o["cornerRadius"] = .num(p.cornerRadius)
            o["border"] = .object(JSONObject([("enabled", .bool(p.border.enabled)), ("width", .num(p.border.width)),
                                              ("color", .string(p.border.color.hex))]))
        case .text(let t):
            o["text"] = .string(t.text)
            o["fontId"] = .string(t.fontId)
            o["weight"] = .int(t.weight)
            o["fontSize"] = .num(t.fontSize)
            o["bold"] = .bool(t.bold)
            o["italic"] = .bool(t.italic)
            o["underline"] = .bool(t.underline)
            o["strike"] = .bool(t.strike)
            o["align"] = .string(t.align.rawValue)
            o["letterSpacing"] = .num(t.letterSpacing)
            o["lineHeight"] = .num(t.lineHeight)
            o["textCase"] = .string(t.textCase.rawValue)
            o["autoWidth"] = .bool(t.autoWidth)
            o["boxWidth"] = .num(t.boxWidth)
            o["fill"] = .object(encodeFill(t.fill))
            o["outline"] = .object(JSONObject([("enabled", .bool(t.outline.enabled)), ("style", .string(t.outline.style.rawValue)),
                                               ("color", .string(t.outline.color.hex)), ("width", .num(t.outline.width)),
                                               ("color2", .string(t.outline.color2.hex)), ("width2", .num(t.outline.width2)),
                                               ("glowRadius", .num(t.outline.glowRadius)), ("join", .string(t.outline.join.rawValue))]))
            o["shadow"] = .object(JSONObject([("enabled", .bool(t.shadow.enabled)), ("color", .string(t.shadow.color.hex)),
                                              ("blur", .num(t.shadow.blur)), ("offsetX", .num(t.shadow.offsetX)),
                                              ("offsetY", .num(t.shadow.offsetY))]))
            o["backgroundBox"] = .object(JSONObject([("enabled", .bool(t.backgroundBox.enabled)),
                                                     ("color", .string(t.backgroundBox.color.hex)),
                                                     ("padding", .num(t.backgroundBox.padding)),
                                                     ("cornerRadius", .num(t.backgroundBox.cornerRadius))]))
            o["curve"] = .num(t.curve)
            o["skew"] = .num(t.skew)
            let spans = Spans.normalize(t, spans: t.spans)
            o["spans"] = .array(spans.map { s in
                var so = JSONObject([("start", .int(s.start)), ("end", .int(s.end))])
                let st = s.style
                if let v = st.bold { so["bold"] = .bool(v) }
                if let v = st.italic { so["italic"] = .bool(v) }
                if let v = st.underline { so["underline"] = .bool(v) }
                if let v = st.strike { so["strike"] = .bool(v) }
                if let v = st.color { so["color"] = .string(v.hex) }
                if let v = st.fontId { so["fontId"] = .string(v) }
                if let v = st.weight { so["weight"] = .int(v) }
                if let v = st.size { so["size"] = .num(v) }
                return .object(so)
            })
        case .drawing(let d):
            o["width"] = .int(d.width)
            o["height"] = .int(d.height)
        case .shape(let s):
            o["shape"] = .string(s.shape.rawValue)
            o["width"] = .num(s.width)
            o["height"] = .num(s.shape.isLinear ? max(s.stroke.width, 1) : s.height)
            o["fill"] = .object(JSONObject([("enabled", .bool(s.fill.enabled)), ("color", .string(s.fill.color.hex))]))
            o["stroke"] = .object(JSONObject([("enabled", .bool(s.stroke.enabled)), ("color", .string(s.stroke.color.hex)),
                                              ("width", .num(s.stroke.width)), ("join", .string(s.stroke.join.rawValue))]))
            o["cornerRadius"] = .num(s.cornerRadius)
            o["arrowHeads"] = .string(s.arrowHeads.rawValue)
        }
        return o
    }

    static func encodeFill(_ f: TextFill) -> JSONObject {
        var o = JSONObject()
        o["type"] = .string(f.type.rawValue)
        switch f.type {
        case .solid: o["color"] = .string(f.color.hex)
        case .linear:
            o["angle"] = .num(f.angle)
            o["stops"] = encodeStops(f.stops)
        case .radial:
            o["stops"] = encodeStops(f.stops)
        }
        return o
    }

    private static func encodeStops(_ stops: [GradientStop]) -> JSONValue {
        .array(stops.sorted { $0.offset < $1.offset }.map {
            .object(JSONObject([("offset", .num($0.offset)), ("color", .string($0.color.hex))]))
        })
    }

    /// A partial text layer (missing fields take §7.1 defaults), e.g. layout test cases.
    static func textProps(fromPartial o: JSONObject) -> TextProps? {
        try? readText(Reader(object: o, path: "partial"))
    }

    // MARK: - Partial text props (presets)

    /// Applies a preset `props` object (§12): reset style fields, then set given keys.
    static func applyPreset(_ props: JSONObject, to t: inout TextProps) {
        let keep = t
        t.resetStyle()
        // Reuse the strict reader on a synthesized text layer object.
        var o = JSONObject()
        o["text"] = .string(keep.text)
        for k in props.keys { o[k] = props[k] }
        if let parsed = try? readText(Reader(object: o, path: "preset")) {
            t = parsed
        }
        if props["fontId"] != nil && props["weight"] == nil { t.weight = 400 }
        t.text = keep.text
        t.fontSize = keep.fontSize
        t.autoWidth = keep.autoWidth
        t.boxWidth = keep.boxWidth
        // Presets drop span colour/font/weight; B/I/U/S and size are kept (§12).
        let kept = keep.spans.map { sp -> TextSpan in
            var sp = sp
            sp.style.color = nil; sp.style.fontId = nil; sp.style.weight = nil
            return sp
        }
        t.spans = Spans.normalize(t, spans: kept)
    }
}

import CoreGraphics
import CoreText
import Foundation
import UIKit

/// One glyph placed in local layer space (paths already flipped, sheared and curved).
struct PlacedGlyph {
    var path: CGPath?              // nil for bitmap glyphs (emoji)
    var boldStroke: CGFloat        // synthesized-bold stroke width (0.04 × run size), 0 if none
    var color: RGBA?               // span colour; nil = layer fill
    // For bitmap glyphs drawn via CTFontDrawGlyphs:
    var font: CTFont
    var glyph: CGGlyph
    var transform: CGAffineTransform // maps font space (y up, origin at glyph origin) to local space
    var sourceIndex: Int           // UTF-16 index in the stored text
}

struct TextLine {
    var range: Range<Int>          // display grapheme indices
    var width: CGFloat
    var x: CGFloat
    var top: CGFloat
    var height: CGFloat
    var baseline: CGFloat
    var isLastInParagraph: Bool
}

/// Glyph outlines (and decorations) sharing one fill colour and synthesized-bold stroke.
struct TextShapeGroup {
    var color: RGBA?               // nil = layer fill (solid or gradient)
    var boldStroke: CGFloat
    var path: CGPath
}

/// Result of laying out a text layer (§7.3, §7.6). Everything is in local px.
final class TextLayoutResult {
    let size: CGSize
    let lines: [TextLine]
    let glyphs: [PlacedGlyph]
    let groups: [TextShapeGroup]   // glyphs + underline/strike, grouped for painting
    let inkBounds: CGRect          // bounds of placed glyphs (for selection when curved)
    let fontSize: CGFloat

    init(size: CGSize, lines: [TextLine], glyphs: [PlacedGlyph], groups: [TextShapeGroup], inkBounds: CGRect, fontSize: CGFloat) {
        self.size = size; self.lines = lines; self.glyphs = glyphs; self.groups = groups
        self.inkBounds = inkBounds; self.fontSize = fontSize
    }

    var hasBitmapGlyphs: Bool { glyphs.contains { $0.path == nil } }
}

/// Lays out text layers exactly per FORMAT.md §7.3 (mixed fonts and sizes per run).
/// Results are cached by the geometry-relevant props.
final class TextLayoutEngine {
    static let shared = TextLayoutEngine()

    private var cache: [LayoutKey: TextLayoutResult] = [:]
    private var order: [LayoutKey] = []
    private let lock = NSLock()
    private let catalog = FontCatalog.shared

    struct LayoutKey: Hashable {
        var text: String, fontId: String, fontSize: Double, weight: Int
        var flags: StyleFlags, spans: [TextSpan], align: TextAlign
        var letterSpacing: Double, lineHeight: Double, textCase: TextCase
        var autoWidth: Bool, boxWidth: Double, curve: Double, solidFill: RGBA?, autoWidthLimit: Double
        init(_ t: TextProps) {
            text = t.text; fontId = t.fontId; fontSize = t.fontSize; weight = t.weight
            flags = t.layerFlags; spans = t.spans; align = t.align; letterSpacing = t.letterSpacing
            lineHeight = t.lineHeight; textCase = t.textCase; autoWidth = t.autoWidth
            boxWidth = t.autoWidth ? 0 : t.boxWidth; curve = t.curve
            solidFill = t.fill.type == .solid ? t.fill.color : nil
            autoWidthLimit = t.autoWidth ? t.autoWidthLimit : 0
        }
    }

    func layout(_ t: TextProps) -> TextLayoutResult {
        let key = LayoutKey(t)
        lock.lock()
        if let r = cache[key] { lock.unlock(); return r }
        lock.unlock()
        let r = compute(t)
        lock.lock()
        if cache[key] == nil {
            cache[key] = r
            order.append(key)
            if order.count > 96 { cache[order.removeFirst()] = nil }
        }
        lock.unlock()
        return r
    }

    // MARK: - Display graphemes (§7.3 steps 1–2: case per grapheme, keeping source style)

    struct DisplayGrapheme {
        var text: String
        var source: Int        // UTF-16 start in stored text
        var sourceEnd: Int     // UTF-16 end of the source grapheme in stored text
        var style: EffectiveStyle
        var isNewline: Bool
        var isWhitespace: Bool
        var flags: StyleFlags { style.flags }
    }

    /// §7.3 whitespace graphemes (NBSP, U+2007 and U+202F are not whitespace).
    static func isLayoutWhitespace(_ s: String) -> Bool {
        guard s.unicodeScalars.count == 1, let u = s.unicodeScalars.first?.value else { return false }
        return u == 0x20 || u == 0x09 || u == 0x1680 || (0x2000...0x2006).contains(u) || (0x2008...0x200A).contains(u)
            || u == 0x205F || u == 0x3000
    }

    static func displayGraphemes(_ t: TextProps) -> [DisplayGrapheme] {
        let eff = Spans.effective(t)
        var out: [DisplayGrapheme] = []
        var pos = 0
        var startOfWord = true
        for ch in t.text {
            let len = ch.utf16.count
            let s = String(ch)
            let isNL = ch == "\n" || ch == "\r\n" || ch == "\r"
            let ws = isLayoutWhitespace(s)
            var d: String
            switch t.textCase {
            case .none: d = s
            case .upper: d = s.uppercased()
            case .lower: d = s.lowercased()
            case .title: d = startOfWord ? s.uppercased() : s.lowercased()
            }
            if isNL { d = "\n" }
            let style = pos < eff.count ? eff[pos] : t.layerStyle
            let subs = isNL ? ["\n"] : d.map(String.init)
            for sub in subs.isEmpty ? [d] : subs {
                out.append(DisplayGrapheme(text: sub, source: pos, sourceEnd: pos + len, style: style,
                                           isNewline: isNL, isWhitespace: !isNL && isLayoutWhitespace(sub)))
            }
            startOfWord = ws || isNL
            pos += len
        }
        return out
    }

    // MARK: - Fonts and metrics per run

    private struct FaceKey: Hashable { var fontId: String; var weight: Int; var bold: Bool; var italic: Bool; var size: Double }
    private struct FaceInfo { var face: ResolvedFace; var font: CTFont }

    /// shared/fonts/metrics.json: the only source of vertical metrics (§7.3 step 7).
    static let fontMetrics: [String: (upm: Double, asc: Double, desc: Double)] = {
        guard let url = FontCatalog.shared.fontsRoot?.appendingPathComponent("metrics.json"),
              let data = try? Data(contentsOf: url),
              let files = (try? JSONValue.parse(data))?.objectValue?["files"]?.objectValue else { return [:] }
        var out: [String: (Double, Double, Double)] = [:]
        for k in files.keys {
            guard let o = files[k]?.objectValue, let upm = o["unitsPerEm"]?.doubleValue,
                  let a = o["ascender"]?.doubleValue, let d = o["descender"]?.doubleValue else { continue }
            out[k] = (upm, a, d)
        }
        return out
    }()

    // MARK: - Layout (§7.3, fully determined)

    private func compute(_ t: TextProps) -> TextLayoutResult {
        var faceCache: [FaceKey: FaceInfo] = [:]
        func face(_ s: EffectiveStyle) -> FaceInfo {
            let k = FaceKey(fontId: s.fontId, weight: s.weight, bold: s.flags.bold, italic: s.flags.italic, size: s.size)
            if let c = faceCache[k] { return c }
            let fam = catalog.font(id: s.fontId)
            let rf = FontCatalog.resolve(fam, weight: s.weight, bold: s.flags.bold, italic: s.flags.italic)
            var plain = rf; plain.synthItalic = false
            let info = FaceInfo(face: rf, font: catalog.ctFont(plain, size: CGFloat(s.size)))
            faceCache[k] = info
            return info
        }
        /// A and D from metrics.json for the run's metrics file (family at its weight, upright, not bold).
        func metrics(_ s: EffectiveStyle) -> (CGFloat, CGFloat) {
            let fam = catalog.font(id: s.fontId)
            let file = FontCatalog.resolve(fam, weight: s.weight, bold: false, italic: false).file
            let z = s.size
            if let m = TextLayoutEngine.fontMetrics[file.path] {
                return (CGFloat(m.asc / m.upm * z), CGFloat(-m.desc / m.upm * z))
            }
            let f = catalog.ctFont(ResolvedFace(file: file, synthBold: false, synthItalic: false), size: CGFloat(z))
            return (CTFontGetAscent(f), CTFontGetDescent(f))
        }

        let graphemes = TextLayoutEngine.displayGraphemes(t)
        let n = graphemes.count

        // Paragraphs (newline graphemes belong to no line).
        var paragraphs: [Range<Int>] = []
        var pstart = 0
        for (i, g) in graphemes.enumerated() where g.isNewline {
            paragraphs.append(pstart..<i)
            pstart = i + 1
        }
        paragraphs.append(pstart..<n)

        // §7.3 steps 3–4: shape each run on its own (kern on, liga/clig/dlig off, calt on;
        // never a kern attribute), measure grapheme advances and glyph positions.
        struct GlyphRef { var font: CTFont; var glyph: CGGlyph; var dx: CGFloat; var advance: CGFloat; var bitmap: Bool }
        var advance = [CGFloat](repeating: 0, count: n)
        var glyphsOf = [[GlyphRef]](repeating: [], count: n)
        var fontOf = [CTFont?](repeating: nil, count: n)

        func fontFor(_ i: Int) -> CTFont {
            let g = graphemes[i]
            var font = face(g.style).font
            let units = Array(g.text.utf16)
            var gl = [CGGlyph](repeating: 0, count: units.count)
            if !units.isEmpty && !g.isWhitespace && !CTFontGetGlyphsForCharacters(font, units, &gl, units.count) {
                // System fallback (emoji etc.): its own run; outside the parity contract.
                let system = CTFontCreateUIFontForLanguage(.system, CGFloat(g.style.size), nil) ?? font
                font = CTFontCreateForString(system, g.text as CFString, CFRange(location: 0, length: units.count))
            }
            return font
        }
        for i in 0..<n where !graphemes[i].isNewline { fontOf[i] = fontFor(i) }

        func runKey(_ i: Int) -> String {
            let st = graphemes[i].style
            let f = face(st).face
            let name = fontOf[i].map { CTFontCopyPostScriptName($0) as String } ?? ""
            return "\(f.file.path)|\(f.file.weight)|\(st.size)|\(f.synthBold)|\(f.synthItalic)|\(name)"
        }

        for p in paragraphs where !p.isEmpty {
            var i = p.lowerBound
            while i < p.upperBound {
                var j = i + 1
                let key = runKey(i)
                while j < p.upperBound && runKey(j) == key { j += 1 }
                // Shape [i, j) as one run.
                let s = NSMutableAttributedString()
                var starts: [Int] = []
                var u = 0
                for k in i..<j {
                    starts.append(u)
                    u += (graphemes[k].text as NSString).length
                    s.append(NSAttributedString(string: graphemes[k].text))
                }
                s.addAttributes([NSAttributedString.Key(kCTFontAttributeName as String): fontOf[i]!,
                                 NSAttributedString.Key(kCTLigatureAttributeName as String): 0],
                                range: NSRange(location: 0, length: s.length))
                let line = CTLineCreateWithAttributedString(s)
                let lineWidth = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
                var startX = [CGFloat](repeating: .nan, count: j - i)
                for run in CTLineGetGlyphRuns(line) as! [CTRun] {
                    let count = CTRunGetGlyphCount(run)
                    guard count > 0 else { continue }
                    let attrs = CTRunGetAttributes(run) as NSDictionary
                    let font = attrs[kCTFontAttributeName as String] as! CTFont
                    let isBitmap = CTFontGetSymbolicTraits(font).contains(.traitColorGlyphs)
                    var ids = [CGGlyph](repeating: 0, count: count)
                    var pos = [CGPoint](repeating: .zero, count: count)
                    var adv = [CGSize](repeating: .zero, count: count)
                    var idx = [CFIndex](repeating: 0, count: count)
                    CTRunGetGlyphs(run, CFRange(location: 0, length: count), &ids)
                    CTRunGetPositions(run, CFRange(location: 0, length: count), &pos)
                    CTRunGetAdvances(run, CFRange(location: 0, length: count), &adv)
                    CTRunGetStringIndices(run, CFRange(location: 0, length: count), &idx)
                    for k in 0..<count {
                        var lo = 0, hi = starts.count - 1
                        while lo < hi {
                            let mid = (lo + hi + 1) / 2
                            if starts[mid] <= idx[k] { lo = mid } else { hi = mid - 1 }
                        }
                        let gi = i + lo
                        if startX[lo].isNaN || pos[k].x < startX[lo] { startX[lo] = pos[k].x }
                        glyphsOf[gi].append(GlyphRef(font: font, glyph: ids[k], dx: pos[k].x, advance: adv[k].width, bitmap: isBitmap))
                    }
                }
                // Grapheme advance = distance to the next grapheme's first glyph (kerning
                // counts toward the left grapheme); the last runs to the run's end.
                var next = lineWidth
                for k in stride(from: j - i - 1, through: 0, by: -1) {
                    let sx = startX[k].isNaN ? next : startX[k]
                    advance[i + k] = max(0, next - sx)
                    for gIdx in glyphsOf[i + k].indices { glyphsOf[i + k][gIdx].dx -= sx }
                    next = sx
                    if face(graphemes[i + k].style).face.synthBold { advance[i + k] += 0.04 * CGFloat(graphemes[i + k].style.size) }
                }
                i = j
            }
        }

        func ls(_ i: Int) -> CGFloat { CGFloat(t.letterSpacing * graphemes[i].style.size) }
        /// Width of the line [a, b): pitches through the last non-whitespace grapheme; the
        /// line's last grapheme (b − 1) gets no letter spacing.
        func width(_ a: Int, _ b: Int) -> CGFloat {
            var e = b
            while e > a && graphemes[e - 1].isWhitespace { e -= 1 }
            guard e > a else { return 0 }
            var w: CGFloat = 0
            for k in a..<e { w += advance[k] + (k == b - 1 ? 0 : ls(k)) }
            return w
        }

        // §7.3 step 5: greedy wrapping with whitespace and hyphen break opportunities.
        let M: CGFloat = t.autoWidth ? CGFloat(t.autoWidthLimit) : CGFloat(max(1, t.boxWidth))
        func isLetterOrDigit(_ s: String) -> Bool {
            guard let u = s.unicodeScalars.first else { return false }
            switch u.properties.generalCategory {
            case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
                 .decimalNumber, .letterNumber, .otherNumber: return true
            default: return false
            }
        }
        var lineRanges: [(Range<Int>, Bool)] = []
        for p in paragraphs {
            if p.isEmpty { lineRanges.append((p, true)); continue }
            // Opportunities: indices where a new line may start.
            var opps: [Int] = []
            for k in (p.lowerBound + 1)..<max(p.lowerBound + 1, p.upperBound) {
                let prev = graphemes[k - 1], cur = graphemes[k]
                if prev.isWhitespace && !cur.isWhitespace { opps.append(k); continue }
                if ["-", "\u{2010}", "\u{2013}"].contains(prev.text), k - 2 >= p.lowerBound,
                   !graphemes[k - 2].isWhitespace, isLetterOrDigit(cur.text) {
                    opps.append(k)
                }
            }
            opps.append(p.upperBound)
            var s = p.lowerBound
            var produced: [Range<Int>] = []
            while s < p.upperBound {
                var best: Int? = nil
                for o in opps where o > s {
                    if width(s, o) <= M + 0.01 { best = o } else if best != nil { break }
                }
                if let o = best {
                    produced.append(s..<o)
                    s = o
                } else {
                    // First word wider than M: break between graphemes (at least one).
                    var e = s + 1
                    while e < p.upperBound && !graphemes[e].isWhitespace && width(s, e + 1) <= M + 0.01 { e += 1 }
                    produced.append(s..<e)
                    s = e
                }
            }
            for (k, r) in produced.enumerated() { lineRanges.append((r, k == produced.count - 1)) }
        }

        // §7.3 step 7: vertical metrics per line.
        var tops: [CGFloat] = [], heights: [CGFloat] = [], baselines: [CGFloat] = [], widths: [CGFloat] = []
        var y: CGFloat = 0
        for (r, _) in lineRanges {
            var S = 0.0, A: CGFloat = 0, D: CGFloat = 0
            for k in r {
                let st = graphemes[k].style
                S = max(S, st.size)
                let (a, d) = metrics(st)
                A = max(A, a); D = max(D, d)
            }
            if r.isEmpty {
                S = t.fontSize
                (A, D) = metrics(t.layerStyle)
            }
            let LH = CGFloat(t.lineHeight * S)
            tops.append(y); heights.append(LH)
            baselines.append(y + LH / 2 + (A - D) / 2)
            widths.append(width(r.lowerBound, r.upperBound))
            y += LH
        }
        let maxLine = widths.max() ?? 0
        let w = max(1, t.autoWidth ? maxLine : CGFloat(max(1, t.boxWidth)))
        let h = y
        let size = CGSize(width: w, height: h)

        // Curve (§7.6): sweep capped at 0.97 turn; R ≥ h.
        let curving = t.curve != 0 && maxLine > 0
        let phi = min(abs(t.curve) / 100 * 2 * .pi, 0.97 * 2 * .pi)
        let R = curving ? max(maxLine / CGFloat(phi), h) : 0
        let yRef = h / 2
        // §7.6 concentric lines: each line's angles use line 0's arc-length scale.
        func rho(_ b: CGFloat) -> CGFloat { t.curve > 0 ? R - (b - yRef) : R + (b - yRef) }
        let r0 = max(rho(baselines.first ?? yRef), 0.001)
        var lineIndex = 0
        func curveTransform(anchor: CGPoint) -> CGAffineTransform {
            guard curving else { return .identity }
            let Ri = R * max(rho(baselines[lineIndex]), 0.001) / r0
            let a = (anchor.x - w / 2) / max(Ri, widths[lineIndex] / (0.97 * 2 * .pi))
            let target: CGPoint
            let rot: CGFloat
            if t.curve > 0 {
                let r = max(0, R - (anchor.y - yRef))
                target = CGPoint(x: w / 2 + r * sin(a), y: yRef + R - r * cos(a))
                rot = a
            } else {
                let r = max(0, R + (anchor.y - yRef))
                target = CGPoint(x: w / 2 + r * sin(a), y: yRef - R + r * cos(a))
                rot = -a
            }
            return CGAffineTransform(translationX: -anchor.x, y: -anchor.y)
                .concatenating(CGAffineTransform(rotationAngle: rot))
                .concatenating(CGAffineTransform(translationX: target.x, y: target.y))
        }

        let shear = CGAffineTransform(a: 1, b: 0, c: tan(12 * .pi / 180), d: 1, tx: 0, ty: 0)
        var lines: [TextLine] = []
        var glyphs: [PlacedGlyph] = []
        var decoPaths: [RGBA?: CGMutablePath] = [:]

        for (li, (r, last)) in lineRanges.enumerated() {
            lineIndex = li
            let lw = widths[li], baseline = baselines[li]
            var x0: CGFloat
            switch t.align {
            case .left, .justify: x0 = 0
            case .right: x0 = w - lw
            case .center: x0 = (w - lw) / 2
            }
            lines.append(TextLine(range: r, width: lw, x: x0, top: tops[li], height: heights[li], baseline: baseline, isLastInParagraph: last))
            guard !r.isEmpty else { continue }
            // §7.3 step 8: justify extras per gap (whitespace runs between non-whitespace).
            var lastInk = r.upperBound
            while lastInk > r.lowerBound && graphemes[lastInk - 1].isWhitespace { lastInk -= 1 }
            var firstInk = r.lowerBound
            while firstInk < lastInk && graphemes[firstInk].isWhitespace { firstInk += 1 }
            var gapExtra = [CGFloat](repeating: 0, count: r.count)
            if t.align == .justify && !last {
                var gaps = 0
                var inWS = false
                for k in firstInk..<lastInk {
                    if graphemes[k].isWhitespace { if !inWS { gaps += 1; inWS = true } } else { inWS = false }
                }
                if gaps > 0 {
                    let per = (w - lw) / CGFloat(gaps)
                    var acc: CGFloat = 0
                    inWS = false
                    for k in r {
                        if k >= firstInk && k < lastInk && graphemes[k].isWhitespace { inWS = true }
                        else if inWS { inWS = false; acc += per }
                        gapExtra[k - r.lowerBound] = acc
                    }
                }
            }
            // Grapheme positions.
            var gx = [CGFloat](repeating: 0, count: r.count + 1)
            var cursor: CGFloat = 0
            for k in r {
                gx[k - r.lowerBound] = cursor
                cursor += advance[k] + (k == r.upperBound - 1 ? 0 : ls(k))
            }
            gx[r.count] = cursor
            func xAt(_ k: Int) -> CGFloat { x0 + gx[k - r.lowerBound] + gapExtra[min(k - r.lowerBound, r.count - 1)] }

            for k in r where !graphemes[k].isWhitespace {
                let g = graphemes[k]
                let info = face(g.style)
                for ref in glyphsOf[k] {
                    let px = xAt(k) + ref.dx
                    var m = CGAffineTransform(translationX: px, y: baseline).scaledBy(x: 1, y: -1)
                    if info.face.synthItalic && !ref.bitmap { m = shear.concatenating(m) }
                    let ct = curveTransform(anchor: CGPoint(x: px + ref.advance / 2, y: baseline))
                    m = m.concatenating(ct)
                    let path = ref.bitmap ? nil : withUnsafePointer(to: &m) { CTFontCreatePathForGlyph(ref.font, ref.glyph, $0) }
                    if !ref.bitmap && (path == nil || path!.isEmpty) { continue }
                    glyphs.append(PlacedGlyph(path: path,
                                              boldStroke: info.face.synthBold ? 0.04 * CGFloat(g.style.size) : 0,
                                              color: g.style.color, font: ref.font, glyph: ref.glyph,
                                              transform: CGAffineTransform(translationX: px, y: baseline).scaledBy(x: 1, y: -1).concatenating(ct),
                                              sourceIndex: g.source))
                }
            }
            // §7.3 step 9: decorations per run size, no gaps between adjacent runs.
            for k in r where k < lastInk {
                let g = graphemes[k]
                guard g.flags.underline || g.flags.strike else { continue }
                let xs = xAt(k)
                let xe = k + 1 < lastInk ? xAt(k + 1) : xs + advance[k]
                guard xe > xs else { continue }
                let ct = curveTransform(anchor: CGPoint(x: (xs + xe) / 2, y: baseline))
                let z = CGFloat(g.style.size)
                let th = 0.06 * z
                let path = decoPaths[g.style.color] ?? CGMutablePath()
                if g.flags.underline { path.addRect(CGRect(x: xs, y: baseline + 0.12 * z - th / 2, width: xe - xs, height: th), transform: ct) }
                if g.flags.strike { path.addRect(CGRect(x: xs, y: baseline - 0.30 * z - th / 2, width: xe - xs, height: th), transform: ct) }
                decoPaths[g.style.color] = path
            }
        }

        struct GroupKey: Hashable { var color: RGBA?; var bold: CGFloat }
        var groupPaths: [GroupKey: CGMutablePath] = [:]
        var groupOrder: [GroupKey] = []
        func path(_ k: GroupKey) -> CGMutablePath {
            if let p = groupPaths[k] { return p }
            let p = CGMutablePath(); groupPaths[k] = p; groupOrder.append(k); return p
        }
        for g in glyphs { if let gp = g.path { path(GroupKey(color: g.color, bold: g.boldStroke)).addPath(gp) } }
        for (c, p) in decoPaths.sorted(by: { ($0.key?.hex ?? "") < ($1.key?.hex ?? "") }) { path(GroupKey(color: c, bold: 0)).addPath(p) }
        let groups = groupOrder.map { TextShapeGroup(color: $0.color, boldStroke: $0.bold, path: groupPaths[$0]!) }

        var ink = CGRect.null
        for g in glyphs {
            if let p = g.path { ink = ink.union(p.boundingBoxOfPath) }
            else {
                var gl = g.glyph
                var rr = CGRect.zero
                CTFontGetBoundingRectsForGlyphs(g.font, .horizontal, &gl, &rr, 1)
                ink = ink.union(rr.applying(g.transform))
            }
        }
        for p in decoPaths.values where !p.isEmpty { ink = ink.union(p.boundingBoxOfPath) }
        if ink.isNull { ink = CGRect(origin: .zero, size: size) }
        return TextLayoutResult(size: size, lines: lines, glyphs: glyphs, groups: groups, inkBounds: ink, fontSize: CGFloat(t.fontSize))
    }
}

enum LayerGeometry {
    static func boxSize(of layer: Layer) -> CGSize {
        if let s = layer.storedBoxSize { return s }
        if case .text(let t) = layer.content { return TextLayoutEngine.shared.layout(t).size }
        return CGSize(width: 1, height: 1)
    }

    /// Local box → canvas transform.
    static func transform(of layer: Layer) -> CGAffineTransform {
        layer.transform.affine(boxSize: boxSize(of: layer))
    }

    /// Canvas-space corners of the box (TL, TR, BR, BL).
    static func corners(of layer: Layer) -> [CGPoint] {
        let s = boxSize(of: layer)
        let t = transform(of: layer)
        return [CGPoint(x: 0, y: 0), CGPoint(x: s.width, y: 0), CGPoint(x: s.width, y: s.height), CGPoint(x: 0, y: s.height)]
            .map { $0.applying(t) }
    }

    /// Hit test in canvas coordinates (inside the box, with optional slop in canvas px).
    static func contains(_ layer: Layer, point: CGPoint, slop: CGFloat = 0) -> Bool {
        let s = boxSize(of: layer)
        let inv = transform(of: layer).inverted()
        let p = point.applying(inv)
        let sl = slop / CGFloat(max(0.01, layer.transform.scale))
        var rect = CGRect(origin: .zero, size: s)
        if case .text(let t) = layer.content, t.curve != 0 {
            rect = rect.union(TextLayoutEngine.shared.layout(t).inkBounds)
        }
        if case .drawing(let d) = layer.content {
            // Drawing layers are hit only near their strokes (not their whole box).
            guard rect.insetBy(dx: -sl, dy: -sl).contains(p) else { return false }
            for s in d.strokes where s.brush != .eraser {
                let r = CGFloat(s.size) / 2 + sl
                for q in s.points where abs(q.x - p.x) <= r && abs(q.y - p.y) <= r {
                    if hypot(q.x - p.x, q.y - p.y) <= r { return true }
                }
            }
            return false
        }
        if case .shape(let sp) = layer.content, sp.shape.isLinear {
            rect = rect.insetBy(dx: 0, dy: -max(0, 16 - s.height / 2))
        }
        return rect.insetBy(dx: -sl, dy: -sl).contains(p)
    }
}

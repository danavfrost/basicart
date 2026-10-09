import Foundation

/// Character-styling span logic (FORMAT.md §7.9). All offsets are UTF-16 code units.
enum Spans {

    /// UTF-16 offsets of every extended-grapheme-cluster boundary, including 0 and length.
    static func graphemeBoundaries(_ text: String) -> [Int] {
        var result = [0]
        result.reserveCapacity(text.utf16.count + 1)
        var pos = 0
        for ch in text {
            pos += ch.utf16.count
            result.append(pos)
        }
        return result
    }

    /// Largest boundary ≤ offset.
    static func snapDown(_ offset: Int, _ b: [Int]) -> Int {
        var lo = 0, hi = b.count - 1
        if offset <= b[0] { return b[0] }
        if offset >= b[hi] { return b[hi] }
        while lo < hi {
            let mid = (lo + hi + 1) / 2
            if b[mid] <= offset { lo = mid } else { hi = mid - 1 }
        }
        return b[lo]
    }

    /// Smallest boundary ≥ offset.
    static func snapUp(_ offset: Int, _ b: [Int]) -> Int {
        var lo = 0, hi = b.count - 1
        if offset >= b[hi] { return b[hi] }
        if offset <= b[0] { return b[0] }
        while lo < hi {
            let mid = (lo + hi) / 2
            if b[mid] >= offset { hi = mid } else { lo = mid + 1 }
        }
        return b[lo]
    }

    /// Per-UTF-16-unit span style: nil = not covered (layer values).
    static func coverage(length: Int, spans: [TextSpan]) -> [CharStyle?] {
        var cov = [CharStyle?](repeating: nil, count: length)
        for s in spans {
            let a = max(0, min(length, s.start)), e = max(0, min(length, s.end))
            if a < e { for i in a..<e { cov[i] = s.style } }
        }
        return cov
    }

    /// Rebuilds normalized spans from coverage: strip layer-equal fields, drop empty
    /// styles, merge touching identical styles (sorted by construction).
    static func spans(from cov: [CharStyle?], props: TextProps) -> [TextSpan] {
        var out: [TextSpan] = []
        var i = 0
        while i < cov.count {
            guard let raw = cov[i] else { i += 1; continue }
            let st = props.stripped(raw)
            if st.isEmpty { i += 1; continue }
            var j = i + 1
            while j < cov.count, let n = cov[j], props.stripped(n) == st { j += 1 }
            out.append(TextSpan(start: i, end: j, style: st))
            i = j
        }
        return out
    }

    /// Reader repair + normalization (§7.9).
    static func normalize(_ props: TextProps, spans: [TextSpan]) -> [TextSpan] {
        let text = props.text
        let length = text.utf16.count
        guard length > 0, !spans.isEmpty else { return [] }
        let b = graphemeBoundaries(text)
        var fixed: [TextSpan] = []
        for s in spans {
            let a = snapDown(max(0, min(length, s.start)), b)
            let e = snapUp(max(0, min(length, s.end)), b)
            if a < e { fixed.append(TextSpan(start: a, end: e, style: s.style)) }
        }
        return Self.spans(from: coverage(length: length, spans: fixed), props: props)
    }

    static func normalize(_ props: inout TextProps) {
        props.spans = normalize(props, spans: props.spans)
    }

    /// Effective style for every UTF-16 unit.
    static func effective(_ props: TextProps) -> [EffectiveStyle] {
        coverage(length: props.text.utf16.count, spans: props.spans).map { props.effective($0) }
    }

    /// Snaps a selection to grapheme boundaries; nil when it is empty or the whole text.
    static func partialRange(_ props: TextProps, _ selection: Range<Int>?) -> Range<Int>? {
        let length = props.text.utf16.count
        guard let s = selection, !s.isEmpty, length > 0 else { return nil }
        let b = graphemeBoundaries(props.text)
        let a = snapDown(max(0, min(length, s.lowerBound)), b)
        let e = snapUp(max(0, min(length, s.upperBound)), b)
        if a >= e || (a == 0 && e == length) { return nil }
        return a..<e
    }

    /// Effective styles over a range (or the whole text).
    static func styles(_ props: TextProps, in range: Range<Int>?) -> [EffectiveStyle] {
        let eff = effective(props)
        if eff.isEmpty { return [props.layerStyle] }
        let r = (range ?? 0..<eff.count).clamped(to: 0..<eff.count)
        return r.isEmpty ? [props.layerStyle] : Array(eff[r])
    }

    static func rangeHasFlag(_ flag: StyleFlags.Flag, props: TextProps, range: Range<Int>?) -> Bool {
        styles(props, in: range).allSatisfy { $0.flags[flag] }
    }

    // MARK: Editing (then normalize)

    /// Sets character fields on a selection, or on the whole layer when `selection`
    /// is nil / empty / the whole text (layer field set, field removed from spans).
    static func set(in props: inout TextProps, selection: Range<Int>?,
                    span: (inout CharStyle) -> Void, layer: (inout TextProps) -> Void, clear: (inout CharStyle) -> Void) {
        if let r = partialRange(props, selection) {
            var cov = coverage(length: props.text.utf16.count, spans: props.spans)
            for i in r {
                var st = cov[i] ?? CharStyle()
                span(&st)
                cov[i] = st
            }
            props.spans = spans(from: cov, props: props)
        } else {
            layer(&props)
            props.spans = props.spans.map { var s = $0; clear(&s.style); return s }
            normalize(&props)
        }
    }

    /// B / I / U / S toggle (§7.9): false if every grapheme in the range is on, else true.
    static func toggle(_ flag: StyleFlags.Flag, in props: inout TextProps, selection: Range<Int>?) {
        let r = partialRange(props, selection)
        let newValue = !rangeHasFlag(flag, props: props, range: r)
        set(in: &props, selection: r, span: { $0[flag] = newValue },
            layer: { $0.layerFlags[flag] = newValue }, clear: { $0[flag] = nil })
    }

    static func setColor(_ c: RGBA, in props: inout TextProps, selection: Range<Int>?) {
        set(in: &props, selection: selection, span: { $0.color = c },
            layer: { $0.fill = TextFill(type: .solid, color: c, angle: $0.fill.angle, stops: $0.fill.stops) },
            clear: { $0.color = nil })
    }

    /// Gradient (or any whole-layer fill): layer fill, span colours removed.
    static func setLayerFill(_ f: TextFill, in props: inout TextProps) {
        props.fill = f
        props.spans = props.spans.map { var s = $0; s.style.color = nil; return s }
        normalize(&props)
    }

    static func setSize(_ size: Double, in props: inout TextProps, selection: Range<Int>?) {
        let v = round4(size.clamped(4, 2000))
        set(in: &props, selection: selection, span: { $0.size = v }, layer: { $0.fontSize = v }, clear: { $0.size = nil })
    }

    /// Font family on a range: fontId + weight nearest the range's current effective weight.
    static func setFont(_ fam: FontFamily, weight: Int? = nil, in props: inout TextProps, selection: Range<Int>?) {
        let r = partialRange(props, selection)
        let current = styles(props, in: r).first?.weight ?? props.weight
        let w = weight ?? fam.nearestUprightWeight(to: current)
        set(in: &props, selection: r, span: { $0.fontId = fam.id; $0.weight = w },
            layer: { $0.fontId = fam.id; $0.weight = w }, clear: { $0.fontId = nil; $0.weight = nil })
    }

    /// Replace UTF-16 range [s, e) of the stored text with `replacement` (delete then
    /// insert). `typing` (a full style chosen with a collapsed caret) restyles the inserted text.
    static func replace(in props: inout TextProps, range: Range<Int>, with replacement: String, typing: CharStyle? = nil) {
        let ns = props.text as NSString
        let s = max(0, min(ns.length, range.lowerBound))
        let e = max(s, min(ns.length, range.upperBound))
        var spans = props.spans
        if e > s {
            let d = e - s
            func mapDel(_ o: Int) -> Int { o < s ? o : (o < e ? s : o - d) }
            spans = spans.compactMap {
                let a = mapDel($0.start), b = mapDel($0.end)
                return a < b ? TextSpan(start: a, end: b, style: $0.style) : nil
            }
        }
        let k = (replacement as NSString).length
        let p = s
        if k > 0 {
            spans = spans.map { sp in
                var sp = sp
                if p == 0 {
                    if sp.start == 0 { sp.end += k } else { sp.start += k; sp.end += k }
                } else if sp.start < p && p <= sp.end {
                    sp.end += k
                } else if sp.start >= p {
                    sp.start += k; sp.end += k
                }
                return sp
            }
        }
        let newText = ns.replacingCharacters(in: NSRange(location: s, length: e - s), with: replacement)
        props.text = newText
        if let typing, k > 0 {
            var cov = coverage(length: (newText as NSString).length, spans: spans)
            for i in p..<(p + k) { cov[i] = typing }
            spans = Self.spans(from: cov, props: props)
        }
        props.spans = normalize(props, spans: spans)
    }

    /// Corner/pinch scale: span sizes × k (rounded to 4 dp).
    static func scaleSizes(_ props: inout TextProps, by k: Double) {
        props.spans = props.spans.map { s in
            var s = s
            if let z = s.style.size { s.style.size = round4((z * k).clamped(4, 2000)) }
            return s
        }
    }
}

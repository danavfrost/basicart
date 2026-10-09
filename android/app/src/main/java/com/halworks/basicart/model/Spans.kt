package com.halworks.basicart.model

/**
 * Per-range character styling (FORMAT.md §7.9). Every field is optional: absent = inherit
 * the layer value (for [color]: the layer fill).
 */
data class SpanStyle(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val color: Int? = null,
    val fontId: String? = null,
    val weight: Int? = null,
    val size: Double? = null,
) {
    val isEmpty get() = bold == null && italic == null && underline == null && strike == null &&
        color == null && fontId == null && weight == null && size == null

    fun flag(f: StyleFlag) = when (f) {
        StyleFlag.BOLD -> bold; StyleFlag.ITALIC -> italic
        StyleFlag.UNDERLINE -> underline; StyleFlag.STRIKE -> strike
    }

    fun withFlag(f: StyleFlag, v: Boolean?) = when (f) {
        StyleFlag.BOLD -> copy(bold = v); StyleFlag.ITALIC -> copy(italic = v)
        StyleFlag.UNDERLINE -> copy(underline = v); StyleFlag.STRIKE -> copy(strike = v)
    }

    companion object {
        /** Convenience: a span stating all four flags explicitly (the v1 shape). */
        fun ofFlags(f: StyleFlags) = SpanStyle(f.bold, f.italic, f.underline, f.strike)
    }
}

data class Span(val start: Int, val end: Int, val style: SpanStyle) {
    /** Convenience for flag-only spans. */
    constructor(start: Int, end: Int, flags: StyleFlags) : this(start, end, SpanStyle.ofFlags(flags))
}

/** The layer values spans are compared with (solidColor = null when the fill is a gradient). */
data class LayerStyle(
    val flags: StyleFlags,
    val solidColor: Int?,
    val fontId: String,
    val weight: Int,
    val size: Double,
)

fun TextLayer.layerStyle() = LayerStyle(flags, (fill as? Fill.Solid)?.color, fontId, weight, fontSize)

/** Fully resolved style of one grapheme. [color] null = the layer fill (solid or gradient). */
data class CharStyle(
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
    val color: Int?,
    val fontId: String,
    val weight: Int,
    val size: Double,
) {
    val flags get() = StyleFlags(bold, italic, underline, strike)
    fun get(f: StyleFlag) = flags.get(f)
}

fun LayerStyle.resolve(s: SpanStyle) = CharStyle(
    bold = s.bold ?: flags.bold, italic = s.italic ?: flags.italic,
    underline = s.underline ?: flags.underline, strike = s.strike ?: flags.strike,
    color = s.color, fontId = s.fontId ?: fontId,
    weight = (s.weight ?: weight).coerceIn(100, 900), size = (s.size ?: size).coerceIn(4.0, 2000.0),
)

object Spans {

    /** Deletes every field equal to the layer's value (normalization step 2). */
    fun reduce(s: SpanStyle, layer: LayerStyle) = SpanStyle(
        bold = s.bold?.takeIf { it != layer.flags.bold },
        italic = s.italic?.takeIf { it != layer.flags.italic },
        underline = s.underline?.takeIf { it != layer.flags.underline },
        strike = s.strike?.takeIf { it != layer.flags.strike },
        color = s.color?.takeIf { layer.solidColor == null || it != layer.solidColor },
        fontId = s.fontId?.takeIf { it != layer.fontId },
        weight = s.weight?.takeIf { it != layer.weight },
        size = s.size?.takeIf { it != layer.size },
    )

    /** Raw span style for each grapheme (later span wins an overlap). */
    fun perGrapheme(text: String, b: IntArray, spans: List<Span>): Array<SpanStyle> {
        val n = b.size - 1
        val per = Array(maxOf(n, 0)) { SpanStyle() }
        for (s in spans) {
            val st = Graphemes.floor(b, s.start.coerceIn(0, text.length))
            val en = Graphemes.ceil(b, s.end.coerceIn(0, text.length))
            if (en <= st) continue
            for (g in 0 until n) if (b[g] >= st && b[g + 1] <= en) per[g] = s.style
        }
        return per
    }

    /** Effective style of every grapheme. */
    fun effective(text: String, b: IntArray, layer: LayerStyle, spans: List<Span>): Array<CharStyle> =
        perGrapheme(text, b, spans).map { layer.resolve(it) }.toTypedArray()

    private fun rebuild(b: IntArray, per: Array<SpanStyle>, layer: LayerStyle): List<Span> {
        val reduced = per.map { reduce(it, layer) }
        val out = ArrayList<Span>()
        var g = 0
        while (g < reduced.size) {
            val f = reduced[g]
            var e = g + 1
            while (e < reduced.size && reduced[e] == f) e++
            if (!f.isEmpty) out.add(Span(b[g], b[e], f))
            g = e
        }
        return out
    }

    /** Normalized form (§7.9): repair, reduce against the layer, drop empty, merge touching equal spans. */
    fun normalize(text: String, layer: LayerStyle, spans: List<Span>): List<Span> {
        if (spans.isEmpty() || text.isEmpty()) return emptyList()
        val b = Graphemes.boundaries(text)
        return rebuild(b, perGrapheme(text, b, spans), layer)
    }

    fun normalize(t: TextLayer): TextLayer = t.copy(spans = normalize(t.text, t.layerStyle(), t.spans))

    /** Snaps a selection to grapheme boundaries. */
    fun snap(text: String, s: Int, e: Int): Pair<Int, Int> {
        val b = Graphemes.boundaries(text)
        return Graphemes.floor(b, minOf(s, e).coerceIn(0, text.length)) to Graphemes.ceil(b, maxOf(s, e).coerceIn(0, text.length))
    }

    /** Applies [edit] to the span style of every grapheme in [s, e); returns normalized spans. */
    fun setOnRange(text: String, layer: LayerStyle, spans: List<Span>, s: Int, e: Int, edit: (SpanStyle) -> SpanStyle): List<Span> {
        if (text.isEmpty()) return emptyList()
        val b = Graphemes.boundaries(text)
        val (st, en) = snap(text, s, e)
        val per = perGrapheme(text, b, spans)
        for (g in per.indices) if (b[g] >= st && b[g + 1] <= en) per[g] = edit(per[g])
        return rebuild(b, per, layer)
    }

    /** True if every grapheme in [s, e) effectively has [flag] (empty range → layer value). */
    fun allHave(text: String, layer: LayerStyle, spans: List<Span>, flag: StyleFlag, s: Int, e: Int): Boolean {
        if (text.isEmpty()) return layer.flags.get(flag)
        val b = Graphemes.boundaries(text)
        val eff = effective(text, b, layer, spans)
        val (st, en) = snap(text, s, e)
        var any = false
        for (g in eff.indices) if (b[g] >= st && b[g + 1] <= en) {
            any = true
            if (!eff[g].get(flag)) return false
        }
        return if (any) true else layer.flags.get(flag)
    }

    /** Effective styles of the graphemes in [s, e) (for showing selection state / "mixed"). */
    fun stylesIn(text: String, layer: LayerStyle, spans: List<Span>, s: Int, e: Int): List<CharStyle> {
        if (text.isEmpty()) return listOf(layer.resolve(SpanStyle()))
        val b = Graphemes.boundaries(text)
        val eff = effective(text, b, layer, spans)
        val (st, en) = snap(text, s, e)
        return eff.indices.filter { b[it] >= st && b[it + 1] <= en }.map { eff[it] }
    }

    /** Style at a collapsed caret: the grapheme before it (or after it at 0). */
    fun styleAt(text: String, layer: LayerStyle, spans: List<Span>, caret: Int): CharStyle {
        if (text.isEmpty()) return layer.resolve(SpanStyle())
        val b = Graphemes.boundaries(text)
        val eff = effective(text, b, layer, spans)
        var g = 0
        for (i in 0 until b.size - 1) if (b[i] < caret) g = i
        return eff.getOrElse(g) { layer.resolve(SpanStyle()) }
    }

    data class ToggleResult(val layerFlags: StyleFlags, val spans: List<Span>)

    /**
     * B/I/U/S. With a non-empty selection that isn't the whole text, only that range changes;
     * otherwise the layer flag is set and the field is removed from every span.
     */
    fun toggle(text: String, t: TextLayer, flag: StyleFlag, selStart: Int, selEnd: Int): ToggleResult {
        val layer = t.layerStyle()
        val s = minOf(selStart, selEnd).coerceIn(0, text.length)
        val e = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val whole = s == e || (s == 0 && e == text.length)
        if (whole) {
            val v = !allHave(text, layer, t.spans, flag, 0, text.length)
            val nl = t.flags.with(flag, v)
            val ns = t.spans.map { it.copy(style = it.style.withFlag(flag, null)) }
            return ToggleResult(nl, normalize(text, layer.copy(flags = nl), ns))
        }
        val v = !allHave(text, layer, t.spans, flag, s, e)
        return ToggleResult(t.flags, setOnRange(text, layer, t.spans, s, e) { it.withFlag(flag, v) })
    }

    /** Insert [k] code units at [p] (text already updated to [newText]). */
    fun insert(newText: String, layer: LayerStyle, spans: List<Span>, p: Int, k: Int): List<Span> {
        if (k <= 0) return normalize(newText, layer, spans)
        val out = spans.map { sp ->
            when {
                p == 0 && sp.start == 0 -> sp.copy(end = sp.end + k)
                sp.start >= p -> sp.copy(start = sp.start + k, end = sp.end + k)
                p <= sp.end -> sp.copy(end = sp.end + k) // start < p <= end
                else -> sp
            }
        }
        return normalize(newText, layer, out)
    }

    /** Delete [s, e) (text already updated to [newText]). */
    fun delete(newText: String, layer: LayerStyle, spans: List<Span>, s: Int, e: Int): List<Span> {
        val d = e - s
        if (d <= 0) return normalize(newText, layer, spans)
        fun map(o: Int) = when {
            o < s -> o
            o < e -> s
            else -> o - d
        }
        val out = spans.map { it.copy(start = map(it.start), end = map(it.end)) }.filter { it.end > it.start }
        return normalize(newText, layer, out)
    }

    /** Replace [s, e) of [oldText] by [insert]. */
    fun replace(oldText: String, layer: LayerStyle, spans: List<Span>, s: Int, e: Int, insert: String): List<Span> {
        val afterDelete = oldText.removeRange(s, e)
        val sp1 = delete(afterDelete, layer, spans, s, e)
        val newText = afterDelete.substring(0, s) + insert + afterDelete.substring(s)
        return insert(newText, layer, sp1, s, insert.length)
    }

    /** Diff-based edit (from the text field): finds the changed range and applies replace. */
    fun applyEdit(old: String, new: String, layer: LayerStyle, spans: List<Span>): List<Span> {
        if (old == new) return spans
        var pre = 0
        val minLen = minOf(old.length, new.length)
        while (pre < minLen && old[pre] == new[pre]) pre++
        var suf = 0
        while (suf < minLen - pre && old[old.length - 1 - suf] == new[new.length - 1 - suf]) suf++
        return replace(old, layer, spans, pre, old.length - suf, new.substring(pre, new.length - suf))
    }

    /** Corner/pinch scaling: span sizes × k (rounded to 4 dp). */
    fun scaleSizes(spans: List<Span>, k: Double): List<Span> = spans.map { sp ->
        sp.style.size?.let { sp.copy(style = sp.style.copy(size = (Math.round(it * k * 10000.0) / 10000.0).coerceIn(4.0, 2000.0))) } ?: sp
    }
}

/**
 * Character-styling edits on a text layer (FORMAT.md §7.9 editing rules): a value goes to the
 * selection [s, e), or — with no selection / the whole text — to the layer field (and the
 * field is removed from every span).
 */
object CharStyling {
    private fun whole(t: TextLayer, s: Int, e: Int) = s == e || (minOf(s, e) <= 0 && maxOf(s, e) >= t.text.length)

    fun setColor(t: TextLayer, s: Int, e: Int, color: Int): TextLayer =
        if (whole(t, s, e)) Spans.normalize(t.copy(fill = Fill.Solid(color), spans = t.spans.map { it.copy(style = it.style.copy(color = null)) }))
        else t.copy(spans = Spans.setOnRange(t.text, t.layerStyle(), t.spans, s, e) { it.copy(color = color) })

    /** Layer fill change (gradients are always layer-wide): span colors are removed. */
    fun setLayerFill(t: TextLayer, fill: Fill): TextLayer =
        Spans.normalize(t.copy(fill = fill, spans = t.spans.map { it.copy(style = it.style.copy(color = null)) }))

    fun setSize(t: TextLayer, s: Int, e: Int, size: Double): TextLayer {
        val z = size.coerceIn(4.0, 2000.0)
        return if (whole(t, s, e)) Spans.normalize(t.copy(fontSize = z, spans = t.spans.map { it.copy(style = it.style.copy(size = null)) }))
        else t.copy(spans = Spans.setOnRange(t.text, t.layerStyle(), t.spans, s, e) { it.copy(size = z) })
    }

    /** Font on a selection: fontId + weight (nearest the range's current effective weight). */
    fun setFont(t: TextLayer, s: Int, e: Int, fontId: String, weight: Int): TextLayer =
        if (whole(t, s, e)) Spans.normalize(t.copy(fontId = fontId, weight = weight, spans = t.spans.map { it.copy(style = it.style.copy(fontId = null, weight = null)) }))
        else t.copy(spans = Spans.setOnRange(t.text, t.layerStyle(), t.spans, s, e) { it.copy(fontId = fontId, weight = weight) })

    fun setWeight(t: TextLayer, s: Int, e: Int, weight: Int): TextLayer =
        if (whole(t, s, e)) Spans.normalize(t.copy(weight = weight, spans = t.spans.map { it.copy(style = it.style.copy(weight = null)) }))
        else t.copy(spans = Spans.setOnRange(t.text, t.layerStyle(), t.spans, s, e) { it.copy(weight = weight) })
}

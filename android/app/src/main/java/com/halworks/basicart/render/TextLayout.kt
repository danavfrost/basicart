package com.halworks.basicart.render

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.halworks.basicart.fonts.FontCatalog
import com.halworks.basicart.fonts.ResolvedFace
import com.halworks.basicart.model.CharStyle
import com.halworks.basicart.model.Graphemes
import com.halworks.basicart.model.Spans
import com.halworks.basicart.model.StyleFlags
import com.halworks.basicart.model.TextAlign
import com.halworks.basicart.model.TextCase
import com.halworks.basicart.model.TextLayer
import com.halworks.basicart.model.layerStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** One laid-out grapheme (straight layout, local px). */
class GlyphBox(
    val srcStart: Int,
    val srcEnd: Int,
    val display: String,
    val line: Int,
    var x: Float,
    val advance: Float,
    val style: CharStyle,
    val isSpace: Boolean,
    val isNewline: Boolean,
) {
    val flags: StyleFlags get() = style.flags
}

/** A glyph without an outline (color emoji): drawn with drawText in the fill pass. */
class ColorGlyph(val text: String, val typeface: android.graphics.Typeface, val size: Float, val x: Float, val baseline: Float, val matrix: Matrix?, val skewItalic: Boolean)

/** Line geometry: each line has its own height (largest size on it) and one shared baseline. */
class LineBox(val index: Int, val baseline: Float, val top: Float, val height: Float, val width: Float, val x: Float)

/**
 * Glyph outlines that share a fill: [color] null = the layer fill (solid or gradient).
 * [synthStroke] > 0 marks synthesized-bold glyphs (stroked by that width in the fill paint).
 */
class TextPiece(val color: Int?, val path: Path, val synthStroke: Float)

/**
 * Result of laying out a text layer (FORMAT.md §7.3–§7.9). Local px, box (0,0)–(w,h).
 * Glyph outlines are vector paths, so rendering at any scale is crisp.
 */
class TextLayoutResult(
    val w: Float,
    val h: Float,
    val pieces: List<TextPiece>,
    val glyphs: List<GlyphBox>,
    val lines: List<LineBox>,
    /** Bounds of the placed ink (curved text may extend beyond the box). */
    val inkBounds: RectF,
    val colorGlyphs: List<ColorGlyph> = emptyList(),
    /** Curved text: each glyph's placement (straight local → curved local); null when straight. */
    val glyphXf: List<Matrix?>? = null,
) {
    private fun mapped(i: Int, x: Float, y: Float): FloatArray {
        val a = floatArrayOf(x, y)
        glyphXf?.getOrNull(i)?.mapPoints(a)
        return a
    }

    /** Caret as a segment (top x,y, bottom x,y) in local px, following the curve. */
    fun caretSegment(offset: Int): FloatArray {
        val (x, top, bottom) = caret(offset)
        if (glyphXf == null || glyphs.isEmpty()) return floatArrayOf(x, top, x, bottom)
        // The glyph the caret sits before on the same line, else the one it follows.
        fun onLine(i: Int) = i >= 0 && glyphXf[i] != null && lines[glyphs[i].line].top == top
        var gi = glyphs.indexOfFirst { !it.isNewline && it.srcStart >= offset }
        if (!onLine(gi)) gi = glyphs.indexOfLast { !it.isNewline && it.srcEnd <= offset }
        if (!onLine(gi)) return floatArrayOf(x, top, x, bottom)
        val a = mapped(gi, x, top); val b = mapped(gi, x, bottom)
        return floatArrayOf(a[0], a[1], b[0], b[1])
    }

    /** Selection highlight as quads (8 floats each), one per glyph along a curve. */
    fun rangeQuads(s: Int, e: Int): List<FloatArray> {
        if (glyphXf == null) return rangeRects(s, e).map { floatArrayOf(it.left, it.top, it.right, it.top, it.right, it.bottom, it.left, it.bottom) }
        val out = ArrayList<FloatArray>()
        glyphs.forEachIndexed { i, g ->
            if (g.isNewline || g.srcStart < s || g.srcEnd > e) return@forEachIndexed
            val l = lines[g.line]
            val q = floatArrayOf(g.x, l.top, g.x + g.advance, l.top, g.x + g.advance, l.top + l.height, g.x, l.top + l.height)
            glyphXf[i]?.mapPoints(q)
            out.add(q)
        }
        return out
    }

    val maxSynthStroke get() = pieces.maxOfOrNull { it.synthStroke } ?: 0f

    private fun lineAtY(py: Float): Int {
        for (l in lines) if (py < l.top + l.height) return l.index
        return lines.lastIndex
    }

    /** Caret (x, top, bottom) for a source offset (straight layout). */
    fun caret(offset: Int): Triple<Float, Float, Float> {
        if (lines.isEmpty()) return Triple(w / 2f, 0f, h)
        fun tri(li: Int, x: Float) = lines[li].let { Triple(x, it.top, it.top + it.height) }
        if (glyphs.isEmpty()) return tri(0, lines[0].x)
        var lineIdx = 0
        var x = lines[0].x
        for (g in glyphs) {
            if (g.srcStart >= offset) return tri(g.line, g.x)
            lineIdx = if (g.isNewline) minOf(g.line + 1, lines.lastIndex) else g.line
            x = if (g.isNewline) lines[lineIdx].x else g.x + g.advance
        }
        return tri(lineIdx, x)
    }

    /** Source offset nearest local point (x, y) — for caret placement by tap (curve-aware). */
    fun offsetAt(px: Float, py: Float, textLength: Int): Int {
        val xf = glyphXf ?: return offsetAtStraight(px, py, textLength)
        var best = -1; var bestD = Float.MAX_VALUE
        val inv = Matrix(); val pt = FloatArray(2)
        glyphs.forEachIndexed { i, g ->
            val m = xf.getOrNull(i) ?: return@forEachIndexed
            if (g.isNewline) return@forEachIndexed
            val l = lines[g.line]
            val c = floatArrayOf(g.x + g.advance / 2f, l.top + l.height / 2f); m.mapPoints(c)
            val d = (c[0] - px) * (c[0] - px) + (c[1] - py) * (c[1] - py)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best < 0) return offsetAtStraight(px, py, textLength)
        val g = glyphs[best]
        xf[best]!!.invert(inv); pt[0] = px; pt[1] = py; inv.mapPoints(pt)
        return if (pt[0] < g.x + g.advance / 2f) g.srcStart else g.srcEnd
    }

    private fun offsetAtStraight(px: Float, py: Float, textLength: Int): Int {
        if (lines.isEmpty()) return 0
        val li = lineAtY(py)
        val inLine = glyphs.filter { it.line == li && !it.isNewline }
        if (inLine.isEmpty()) {
            val nl = glyphs.lastOrNull { it.isNewline && it.line == li - 1 }
            return nl?.srcEnd ?: glyphs.firstOrNull { it.line == li }?.srcStart ?: textLength
        }
        for (g in inLine) if (px < g.x + g.advance / 2f) return g.srcStart
        return inLine.last().srcEnd
    }

    /** Rectangles covering source range [s, e) (straight layout) for the selection highlight. */
    fun rangeRects(s: Int, e: Int): List<RectF> {
        val out = ArrayList<RectF>()
        for (l in lines) {
            val gs = glyphs.filter { it.line == l.index && !it.isNewline && it.srcStart >= s && it.srcEnd <= e }
            if (gs.isEmpty()) continue
            out.add(RectF(gs.first().x, l.top, gs.last().x + gs.last().advance, l.top + l.height))
        }
        return out
    }
}

/** OpenType features for every text run (FORMAT.md §7.3 step 3). */
const val LIGATURES_OFF = "'liga' 0, 'clig' 0, 'dlig' 0"

/** FORMAT.md §7.3 whitespace graphemes (NBSP, U+2007 and U+202F are not whitespace). */
fun isWhitespaceGrapheme(g: String): Boolean {
    if (g.isEmpty() || Character.charCount(g.codePointAt(0)) != g.length) return false
    val c = g.codePointAt(0)
    return c == 0x20 || c == 0x09 || c == 0x1680 || c in 0x2000..0x2006 || c in 0x2008..0x200A || c == 0x205F || c == 0x3000
}

class TextLayoutEngine(private val fonts: FontCatalog) {

    // Unhinted, fractional advances; kerning on (default), ligatures off (FORMAT.md §7.3 step 3).
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true; isLinearText = true; hinting = Paint.HINTING_OFF
        fontFeatureSettings = LIGATURES_OFF
    }

    /** [canvasWidth] bounds auto-width boxes (they wrap at 0.9 × canvas width, §7.3 step 5). */
    fun layout(layer: TextLayer, canvasWidth: Float): TextLayoutResult = synchronized(paint) { doLayout(layer, canvasWidth) }

    /** Shaping key: graphemes with equal keys are shaped together (one font file at one size). */
    private fun shapeKey(c: CharStyle) = "${c.fontId}|${c.weight}|${c.bold}|${c.italic}|${c.size}"

    private fun doLayout(t: TextLayer, canvasWidth: Float): TextLayoutResult {
        val text = t.text
        val b = Graphemes.boundaries(text)
        val layerStyle = t.layerStyle()
        val eff = Spans.effective(text, b, layerStyle, t.spans)
        val n = b.size - 1
        val base = layerStyle.resolve0()

        // --- 1. graphemes with display text (case is display-only) and their style (§7.3 step 1)
        class G(val s: Int, val e: Int, val disp: String, val st: CharStyle, val space: Boolean, val nl: Boolean) {
            var adv = 0f
            var face: ResolvedFace? = null
            val z get() = st.size.toFloat()
            val ls get() = (t.letterSpacing * st.size).toFloat()
        }
        val gs = ArrayList<G>(n)
        var prevBreak = true
        for (i in 0 until n) {
            val src = text.substring(b[i], b[i + 1])
            val nl = src == "\n" || src == "\r\n" || src == "\r"
            val ws = !nl && isWhitespaceGrapheme(src)
            val disp = when (t.textCase) {
                TextCase.NONE -> src
                TextCase.UPPER -> src.uppercase(Locale.ROOT)
                TextCase.LOWER -> src.lowercase(Locale.ROOT)
                TextCase.TITLE -> if (prevBreak) src.lowercase(Locale.ROOT).uppercase(Locale.ROOT) else src.lowercase(Locale.ROOT)
            }
            prevBreak = ws || nl
            gs.add(G(b[i], b[i + 1], disp, eff[i], ws, nl))
        }

        // --- 2. advances: each shaping run (same font file + wght + size + synth flags, one
        //        paragraph) shaped on its own; kern on, ligatures off (§7.3 steps 3–4)
        fun faceOf(c: CharStyle) = fonts.resolve(c.fontId, c.weight, c.bold, c.italic)
        for (g in gs) if (!g.nl) g.face = faceOf(g.st)
        fun runKey(g: G) = g.face!!.let { "${it.fileKey}|${g.st.size}|${it.synthBold}|${it.synthItalic}" }
        var i = 0
        while (i < gs.size) {
            if (gs[i].nl) { i++; continue }
            val key = runKey(gs[i])
            var j = i + 1
            while (j < gs.size && !gs[j].nl && runKey(gs[j]) == key) j++
            val face = gs[i].face!!
            paint.typeface = face.typeface
            paint.textSize = gs[i].z
            val sb = StringBuilder()
            val ends = IntArray(j - i)
            for (k in i until j) { sb.append(gs[k].disp); ends[k - i] = sb.length }
            val str = sb.toString()
            var prev = 0f
            for (k in i until j) {
                val adv = paint.getRunAdvance(str, 0, str.length, 0, str.length, false, ends[k - i])
                gs[k].adv = (adv - prev) + if (face.synthBold) 0.04f * gs[k].z else 0f
                prev = adv
            }
            i = j
        }

        // --- 3. line width: pitches (advance + letter spacing, none after the line's last
        //        grapheme) through the last non-whitespace grapheme (§7.3 step 4)
        data class L(val start: Int, val end: Int, val lastInPara: Boolean)
        val lines = ArrayList<L>()
        fun width(s: Int, e: Int): Float {
            var last = e - 1
            while (last >= s && gs[last].space) last--
            if (last < s) return 0f
            var w = 0f
            for (k in s..last) w += gs[k].adv + if (k < e - 1) gs[k].ls else 0f
            return w
        }
        // --- 4. greedy wrapping at the §7.3 step 5 break opportunities
        val scale = t.base.transform.scale.takeIf { it > 0 } ?: 1.0
        val maxW = if (t.autoWidth) (0.9 * canvasWidth / scale).toFloat() else max(1f, t.boxWidth.toFloat())
        fun isHyphen(g: G) = g.disp == "-" || g.disp == "‐" || g.disp == "–"
        fun letterOrDigit(g: G): Boolean {
            val cp = g.disp.codePointAt(0)
            return Character.isLetter(cp) || Character.getType(cp).let { it == Character.DECIMAL_DIGIT_NUMBER.toInt() || it == Character.LETTER_NUMBER.toInt() || it == Character.OTHER_NUMBER.toInt() }
        }
        /** Break opportunity before grapheme [k] (line may end at k), within paragraph [ps, pe). */
        fun canBreakAt(k: Int, ps: Int): Boolean {
            if (k <= ps) return false
            val before = gs[k - 1]; val at = gs[k]
            if (before.space && !at.space) return true
            if (isHyphen(before) && k - 2 >= ps && !gs[k - 2].space && letterOrDigit(at)) return true
            return false
        }
        val tol = 0.01f
        var p = 0
        while (true) {
            var pe = p
            while (pe < gs.size && !gs[pe].nl) pe++
            if (pe == p) {
                lines.add(L(p, pe, true))
            } else {
                var s0 = p
                while (s0 < pe) {
                    if (width(s0, pe) <= maxW + tol) { lines.add(L(s0, pe, true)); s0 = pe; break }
                    var best = -1
                    for (o in pe - 1 downTo s0 + 1) if (canBreakAt(o, p) && width(s0, o) <= maxW + tol) { best = o; break }
                    if (best < 0) {
                        // No opportunity fits: break between graphemes, as many as fit, at least one.
                        var e = s0 + 1
                        while (e < pe && width(s0, e + 1) <= maxW + tol) e++
                        best = e
                    }
                    lines.add(L(s0, best, false))
                    s0 = best
                }
            }
            if (pe >= gs.size) break
            p = pe + 1
            if (p >= gs.size) { lines.add(L(p, p, true)); break }
        }
        if (lines.isEmpty()) lines.add(L(0, 0, true))

        // --- 5. box and per-line vertical metrics from metrics.json (§7.3 steps 6–7)
        val lineWidths = lines.map { width(it.start, it.end) }
        val w = max(1f, if (t.autoWidth) (lineWidths.maxOrNull() ?: 0f) else max(1f, t.boxWidth.toFloat()))
        data class LM(val top: Float, val lh: Float, val baseline: Float)
        val lm = ArrayList<LM>()
        var top = 0f
        for (l in lines) {
            val styles = (l.start until l.end).map { gs[it].st }.ifEmpty { listOf(base) }
            val sMax = styles.maxOf { it.size }.toFloat()
            var a = 0f; var d = 0f
            for (st in styles.distinctBy { "${it.fontId}|${it.weight}|${it.size}" }) {
                val (ar, dr) = fonts.vMetrics(st.fontId, st.weight)
                a = max(a, ar * st.size.toFloat()); d = max(d, dr * st.size.toFloat())
            }
            val lh = (t.lineHeight * sMax).toFloat()
            lm.add(LM(top, lh, top + lh / 2f + (a - d) / 2f))
            top += lh
        }
        val h = max(1f, top)

        // --- 6a. positions (§7.3 step 8; justify on every line but a paragraph's last)
        val glyphs = ArrayList<GlyphBox>(gs.size)
        val lineBoxes = ArrayList<LineBox>()
        lines.forEachIndexed { li, l ->
            val lw = lineWidths[li]
            var extraPerGap = 0f
            var x0 = when (t.align) {
                TextAlign.LEFT, TextAlign.JUSTIFY -> 0f
                TextAlign.RIGHT -> w - lw
                TextAlign.CENTER -> (w - lw) / 2f
            }
            if (t.align == TextAlign.JUSTIFY && !l.lastInPara) {
                var first = l.start
                while (first < l.end && gs[first].space) first++
                var last = l.end - 1
                while (last >= first && gs[last].space) last--
                var gaps = 0
                for (k in first + 1..last) if (gs[k].space && !gs[k - 1].space) gaps++
                if (gaps > 0) extraPerGap = (w - lw) / gaps
            }
            var x = x0
            for (k in l.start until l.end) {
                val g = gs[k]
                glyphs.add(GlyphBox(g.s, g.e, g.disp, li, x, g.adv, g.st, g.space, false))
                x += g.adv + g.ls
                // extra space goes after each whitespace run that is followed by more text
                if (extraPerGap != 0f && g.space && k + 1 < l.end && !gs[k + 1].space) x += extraPerGap
            }
            lineBoxes.add(LineBox(li, lm[li].baseline, lm[li].top, lm[li].lh, lw, x0))
            if (l.end < gs.size && gs[l.end].nl && l.lastInPara) {
                val g = gs[l.end]
                glyphs.add(GlyphBox(g.s, g.e, g.disp, li, x, 0f, g.st, true, true))
            }
        }

        // --- 6. outlines, grouped by fill (span color or layer fill) and synthetic-bold width
        val groups = LinkedHashMap<Pair<Int?, Float>, Path>()
        fun dest(color: Int?, synth: Float) = groups.getOrPut(color to synth) { Path() }
        val curve = t.curve.toFloat()
        val anySpacing = t.letterSpacing != 0.0
        val perGlyphAll = anySpacing || curve != 0f || t.align == TextAlign.JUSTIFY
        val wmax = lineWidths.maxOrNull() ?: 0f
        val curved = curve != 0f && wmax > 0f
        // Arc capped below a full circle (≤ 349°) and radius ≥ the text block height, so short
        // words at strong curves never fold into a knot. (Proposed FORMAT §7.6 addition.)
        val phi = (abs(curve) / 100f * (2f * Math.PI.toFloat())).coerceAtMost(0.97f * 2f * Math.PI.toFloat())
        val radius = if (curved) max(wmax / phi, h) else 0f
        val yRef = h / 2f
        val tmp = Path()
        val m = Matrix()
        val italicShear = -tan(Math.toRadians(12.0)).toFloat()

        val maxSweep = 0.97f * 2f * Math.PI.toFloat()
        fun place(path: Path, anchorX: Float, baseline: Float, d: Path, line: Int): Matrix? {
            if (!curved) { d.addPath(path); return null }
            // Each line follows its own concentric arc (radius offset by its baseline, §7.6).
            // Every line keeps the first line's arc-length scale (r_i / ρ_i = r_0 / R), so a
            // single line is exactly FORMAT §7.6 (a = Δx / R) and inner lines neither bunch up
            // nor spread out.
            val b0 = lineBoxes.firstOrNull()?.baseline ?: baseline
            fun posR(bl: Float) = if (curve > 0) radius - (bl - yRef) else radius + (bl - yRef)
            val r0 = max(posR(b0), 1e-3f)
            val rLine = radius * max(posR(baseline), 1e-3f) / r0
            val lw = lineBoxes.getOrNull(line)?.width ?: wmax
            val a = (anchorX - w / 2f) / max(rLine, lw / maxSweep)
            m.reset()
            m.postTranslate(-anchorX, -baseline)
            val deg = Math.toDegrees(a.toDouble()).toFloat()
            val nx: Float
            val ny: Float
            if (curve > 0) {
                val r = max(0f, radius - (baseline - yRef))
                nx = w / 2f + r * sin(a); ny = yRef + radius - r * cos(a)
                m.postRotate(deg)
            } else {
                val r = max(0f, radius + (baseline - yRef))
                nx = w / 2f + r * sin(a); ny = yRef - radius + r * cos(a)
                m.postRotate(-deg)
            }
            m.postTranslate(nx, ny)
            d.addPath(path, m)
            return Matrix(m)
        }
        val colorGlyphs = ArrayList<ColorGlyph>()
        fun maybeEmoji(str: String): Boolean = str.codePoints().anyMatch { it >= 0x1F000 || it in 0x2600..0x27BF || it == 0xFE0F || it in 0x2B00..0x2BFF }

        var k = 0
        while (k < glyphs.size) {
            val g = glyphs[k]
            if (g.isNewline) { k++; continue }
            val baseline = lineBoxes[g.line].baseline
            val key = shapeKey(g.style)
            var e = k + 1
            while (e < glyphs.size && !glyphs[e].isNewline && glyphs[e].line == g.line &&
                shapeKey(glyphs[e].style) == key && glyphs[e].style.color == g.style.color) e++
            val face = faceOf(g.style)
            val z = g.style.size.toFloat()
            val synth = if (face.synthBold) 0.04f * z else 0f
            paint.typeface = face.typeface
            paint.textSize = z
            val d = dest(g.style.color, synth)
            var segEmoji = false
            for (q in k until e) if (maybeEmoji(glyphs[q].display)) { segEmoji = true; break }
            if (!perGlyphAll && !face.synthBold && !segEmoji) {
                val sb = StringBuilder()
                for (q in k until e) sb.append(glyphs[q].display)
                val str = sb.toString()
                tmp.reset()
                paint.getTextPath(str, 0, str.length, g.x, baseline, tmp)
                if (face.synthItalic) { m.reset(); m.setSkew(italicShear, 0f, 0f, baseline); tmp.transform(m) }
                d.addPath(tmp)
            } else {
                for (q in k until e) {
                    val gq = glyphs[q]
                    if (gq.isSpace) continue
                    tmp.reset()
                    paint.getTextPath(gq.display, 0, gq.display.length, gq.x + synth / 2f, baseline, tmp)
                    if (tmp.isEmpty) {
                        val mm = if (curved) place(Path(), gq.x + gq.advance / 2f, baseline, Path(), gq.line) else null
                        colorGlyphs.add(ColorGlyph(gq.display, face.typeface, z, gq.x, baseline, mm, face.synthItalic))
                        continue
                    }
                    if (face.synthItalic) { m.reset(); m.setSkew(italicShear, 0f, 0f, baseline); tmp.transform(m) }
                    place(tmp, gq.x + gq.advance / 2f, baseline, d, gq.line)
                }
            }
            k = e
        }

        // --- 7. underline / strike per run, at the run's size and in the run's fill
        fun decorate(on: (CharStyle) -> Boolean, offsetEm: Float) {
            var q = 0
            while (q < glyphs.size) {
                val g = glyphs[q]
                if (g.isNewline || !on(g.style)) { q++; continue }
                // a run = same line, identical effective style
                var e = q + 1
                while (e < glyphs.size && !glyphs[e].isNewline && glyphs[e].line == g.line && glyphs[e].style == g.style) e++
                var last = e - 1
                val nextDecorated = e < glyphs.size && !glyphs[e].isNewline && glyphs[e].line == g.line && on(glyphs[e].style)
                if (!nextDecorated) while (last > q && glyphs[last].isSpace) last--
                val z = g.style.size.toFloat()
                val thick = 0.06f * z
                val baseline = lineBoxes[g.line].baseline
                val cy = baseline + offsetEm * z
                val d = dest(g.style.color, 0f)
                if (!curved) {
                    val x2 = glyphs[last].x + glyphs[last].advance + if (nextDecorated) glyphs[last].style.size.toFloat() * t.letterSpacing.toFloat() else 0f
                    if (x2 > g.x) d.addRect(g.x, cy - thick / 2f, x2, cy + thick / 2f, Path.Direction.CW)
                } else {
                    for (r in q..last) {
                        val gr = glyphs[r]
                        val x2 = gr.x + gr.advance + if (r < last || nextDecorated) (gr.style.size * t.letterSpacing).toFloat() else 0f
                        tmp.reset()
                        tmp.addRect(gr.x, cy - thick / 2f, x2, cy + thick / 2f, Path.Direction.CW)
                        place(tmp, gr.x + gr.advance / 2f, baseline, d, gr.line)
                    }
                }
                q = e
            }
        }
        decorate({ it.underline }, 0.12f)
        decorate({ it.strike }, -0.30f)

        val pieces = groups.map { (key, path) -> TextPiece(key.first, path, key.second) }
        val ink = RectF()
        var first = true
        val tb = RectF()
        for (pc in pieces) {
            if (pc.path.isEmpty) continue
            pc.path.computeBounds(tb, true)
            if (first) { ink.set(tb); first = false } else ink.union(tb)
        }
        for (cg in colorGlyphs) ink.union(cg.x, cg.baseline - cg.size, cg.x + cg.size * 1.3f, cg.baseline + cg.size * 0.3f)
        if (first && colorGlyphs.isEmpty()) ink.set(0f, 0f, w, h)
        if (!curved) ink.union(0f, 0f, w, h)

        val glyphXf = if (!curved) null else glyphs.map { g ->
            if (g.isNewline) null else place(Path(), g.x + g.advance / 2f, lineBoxes[g.line].baseline, Path(), g.line)
        }
        return TextLayoutResult(w, h, pieces, glyphs, lineBoxes, ink, colorGlyphs, glyphXf)
    }

    private fun com.halworks.basicart.model.LayerStyle.resolve0() =
        CharStyle(flags.bold, flags.italic, flags.underline, flags.strike, null, fontId, weight.coerceIn(100, 900), size.coerceIn(4.0, 2000.0))
}

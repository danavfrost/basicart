package com.halworks.basicart.render

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import com.halworks.basicart.fonts.FontCatalog
import com.halworks.basicart.model.Adjust
import com.halworks.basicart.model.ArrowHeads
import com.halworks.basicart.model.Brush
import com.halworks.basicart.model.Document
import com.halworks.basicart.model.DrawingLayer
import com.halworks.basicart.model.Fill
import com.halworks.basicart.model.ImageLayer
import com.halworks.basicart.model.Join
import com.halworks.basicart.model.Layer
import com.halworks.basicart.model.LayerBase
import com.halworks.basicart.model.OutlineStyle
import com.halworks.basicart.model.ShapeKind
import com.halworks.basicart.model.ShapeLayer
import com.halworks.basicart.model.Stroke
import com.halworks.basicart.model.TextLayer
import com.halworks.basicart.model.Transform
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A decoded image (possibly a downsampled proxy). [scale] = bitmap px per natural px. */
class ImageSource(val bitmap: Bitmap, val scale: Float)

fun interface ImageProvider {
    /** Bitmap for [assetRef] with at least [minScale] bitmap px per natural px when possible; null = missing. */
    fun get(assetRef: String, minScale: Float): ImageSource?
}

/**
 * The single renderer for screen caches, thumbnails and export (FORMAT.md). Draws from the
 * model at any scale; text and shapes are vectors at output resolution.
 */
class Renderer(val fonts: FontCatalog, private val images: ImageProvider) {
    val textEngine = TextLayoutEngine(fonts)
    private val layoutCache = object : LruCache<Pair<TextLayer, Float>, TextLayoutResult>(64) {}
    private val keyBase = LayerBase("k", "", transform = Transform(0.0, 0.0))

    /**
     * Width of the canvas this renderer draws (one renderer per project). Auto-width text wraps
     * at 0.9 × this (FORMAT.md §7.3 step 5). Updated whenever a document with text is drawn or
     * loaded into the editor.
     */
    @Volatile var canvasWidth: Float = 1080f

    fun layout(t: TextLayer): TextLayoutResult {
        // Only the scale of the transform affects layout (auto-width limit = 0.9·W / scale).
        val key = t.copy(base = keyBase.copy(transform = Transform(0.0, 0.0, t.base.transform.scale))) to canvasWidth
        layoutCache.get(key)?.let { return it }
        return textEngine.layout(t, canvasWidth).also { layoutCache.put(key, it) }
    }

    fun useCanvas(doc: Document) {
        if (doc.layers.any { it is TextLayer }) canvasWidth = doc.canvas.width.toFloat()
    }

    /** Layer box size w × h in local px (§4). */
    fun box(l: Layer): Pair<Float, Float> = when (l) {
        is TextLayer -> layout(l).let { it.w to it.h }
        is ImageLayer -> l.boxWidth.toFloat() to l.boxHeight.toFloat()
        is ShapeLayer -> l.width.toFloat() to l.height.toFloat()
        is DrawingLayer -> l.width.toFloat() to l.height.toFloat()
    }

    /** local → canvas matrix (§4). */
    fun layerMatrix(l: Layer, out: Matrix = Matrix()): Matrix {
        val (w, h) = box(l)
        val t = l.base.transform
        out.reset()
        out.postTranslate(-w / 2f, -h / 2f)
        out.postScale(t.scale.toFloat(), t.scale.toFloat())
        out.postRotate(t.rotation.toFloat())
        out.postTranslate(t.x.toFloat(), t.y.toFloat())
        return out
    }

    /** Local-space bounds of everything the layer can paint (box + effects). */
    fun contentBounds(l: Layer): RectF {
        val (w, h) = box(l)
        return when (l) {
            is DrawingLayer -> RectF(0f, 0f, w, h)
            is ImageLayer -> RectF(0f, 0f, w, h)
            is ShapeLayer -> {
                val sw = l.stroke.width.toFloat()
                val m = if (l.shape.isLinear) max(sw * 2f, max(12f, 4f * sw) * 0.6f + sw) else if (l.stroke.enabled) sw * 2f else 1f
                RectF(-m, -m, w + m, h + m)
            }
            is TextLayer -> textBounds(l)
        }
    }

    private fun textBounds(t: TextLayer): RectF {
        val lay = layout(t)
        val fs = t.fontSize.toFloat()
        val r = RectF(lay.inkBounds)
        r.union(0f, 0f, lay.w, lay.h)
        var m = lay.maxSynthStroke
        if (t.outline.enabled) {
            m += when (t.outline.style) {
                OutlineStyle.SOLID -> t.outline.width.toFloat() * fs
                OutlineStyle.DOUBLE -> (t.outline.width + t.outline.width2).toFloat() * fs
                OutlineStyle.GLOW -> t.outline.width.toFloat() * fs + 1.5f * t.outline.glowRadius.toFloat() * fs + 2f
            }
        }
        r.inset(-m - 2f, -m - 2f)
        if (t.shadow.enabled) {
            val sr = RectF(r)
            val bl = 1.5f * t.shadow.blur.toFloat() * fs + 2f
            sr.offset(t.shadow.offsetX.toFloat() * fs, t.shadow.offsetY.toFloat() * fs)
            sr.inset(-bl, -bl)
            r.union(sr)
        }
        if (t.backgroundBox.enabled && t.curve == 0.0) {
            val p = t.backgroundBox.padding.toFloat() * fs
            r.union(-p - 1f, -p - 1f, lay.w + p + 1f, lay.h + p + 1f)
        }
        if (t.skew != 0.0) skewMatrix(t, lay.h).mapRect(r)
        return r
    }

    private fun skewMatrix(t: TextLayer, h: Float) = Matrix().apply {
        setSkew(-tan(Math.toRadians(t.skew)).toFloat(), 0f, 0f, h / 2f)
    }

    // ================================================================ document

    /** Draws the whole document onto [canvas] at [scale] output px per canvas px. */
    fun drawDocument(canvas: Canvas, doc: Document, scale: Float, drawBackground: Boolean = true) {
        useCanvas(doc)
        canvas.save()
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, doc.canvas.width.toFloat(), doc.canvas.height.toFloat())
        if (drawBackground && Color.alpha(doc.canvas.background) != 0) {
            canvas.drawColor(doc.canvas.background)
        }
        for (l in doc.layers) if (l.base.visible) drawLayer(canvas, l, scale)
        canvas.restore()
    }

    fun renderBitmap(doc: Document, scale: Float, outW: Int, outH: Int): Bitmap {
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawDocument(c, doc, scale)
        return bmp
    }

    /** Draws one layer in canvas coordinates; [outScale] = output px per canvas px. */
    fun drawLayer(canvas: Canvas, l: Layer, outScale: Float) {
        val m = layerMatrix(l)
        canvas.save()
        canvas.concat(m)
        val devScale = outScale * l.base.transform.scale.toFloat()
        val op = l.base.opacity.toFloat()
        // Isolated group when it matters: group opacity, or an eraser mask that must only cut this layer.
        val isolate = op < 1f || l.base.mask.isNotEmpty()
        if (isolate) canvas.saveLayerAlpha(contentBounds(l), (op * 255f + 0.5f).toInt())
        drawContent(canvas, l, devScale)
        if (isolate) canvas.restore()
        canvas.restore()
    }

    /** Draws layer content in local space at full opacity. [devScale] = device px per local px. */
    fun drawContent(canvas: Canvas, l: Layer, devScale: Float) {
        when (l) {
            is ImageLayer -> drawImage(canvas, l, devScale)
            is ShapeLayer -> drawShape(canvas, l)
            is DrawingLayer -> drawDrawing(canvas, l)
            is TextLayer -> drawText(canvas, l)
        }
        // Eraser mask (destination-out into the already-isolated layer content).
        for (s in l.base.mask) drawStroke(canvas, s.copy(brush = Brush.ERASER))
    }

    // ================================================================ image

    private fun drawImage(canvas: Canvas, l: ImageLayer, devScale: Float) {
        val w = l.boxWidth.toFloat()
        val h = l.boxHeight.toFloat()
        val radius = min(l.cornerRadius.toFloat(), min(w, h) / 2f)
        val clip = Path().apply { addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        val src = images.get(l.assetRef, devScale)
        if (src == null) {
            drawPlaceholder(canvas, w, h)
        } else {
            val m = imageMatrix(l, src.scale)
            val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            bmpPaint.colorFilter = if (l.adjust.isIdentity) null else ColorMatrixColorFilter(adjustMatrix(l.adjust))
            canvas.drawBitmap(src.bitmap, m, bmpPaint)
        }
        canvas.restore()
        if (l.border.enabled && l.border.width > 0) {
            val bw = l.border.width.toFloat()
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = bw; color = l.border.color
            }
            val r2 = max(0f, radius - bw / 2f)
            canvas.drawRoundRect(bw / 2f, bw / 2f, w - bw / 2f, h - bw / 2f, r2, r2, p)
        }
    }

    private fun drawPlaceholder(canvas: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD0D0D0.toInt() }
        canvas.drawRect(0f, 0f, w, h, p)
        p.color = 0xFF9E9E9E.toInt()
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, min(w, h) / 60f)
        canvas.drawLine(0f, 0f, w, h, p)
        canvas.drawLine(w, 0f, 0f, h, p)
    }

    /** Maps bitmap px → box px: crop, rotate90 (cw), flipH, flipV (§6 pipeline). */
    fun imageMatrix(l: ImageLayer, srcScale: Float): Matrix {
        val m = Matrix()
        m.postScale(1f / srcScale, 1f / srcScale) // bitmap → natural px
        m.postTranslate(-l.crop.x.toFloat(), -l.crop.y.toFloat())
        val cw = l.crop.width.toFloat()
        val ch = l.crop.height.toFloat()
        when (l.rotate90 % 4) {
            1 -> { m.postRotate(90f); m.postTranslate(ch, 0f) }
            2 -> { m.postRotate(180f); m.postTranslate(cw, ch) }
            3 -> { m.postRotate(270f); m.postTranslate(0f, cw) }
        }
        val w = l.boxWidth.toFloat()
        val h = l.boxHeight.toFloat()
        if (l.flipH) { m.postScale(-1f, 1f); m.postTranslate(w, 0f) }
        if (l.flipV) { m.postScale(1f, -1f); m.postTranslate(0f, h) }
        return m
    }

    // ================================================================ shape

    private fun drawShape(canvas: Canvas, l: ShapeLayer) {
        val w = l.width.toFloat()
        val h = l.height.toFloat()
        val sw = l.stroke.width.toFloat()
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sw
            color = l.stroke.color
            strokeJoin = if (l.stroke.join == Join.ROUND) Paint.Join.ROUND else Paint.Join.MITER
            strokeMiter = 4f
        }
        when (l.shape) {
            ShapeKind.RECT, ShapeKind.ROUND_RECT, ShapeKind.ELLIPSE -> {
                val path = Path()
                when (l.shape) {
                    ShapeKind.RECT -> path.addRect(0f, 0f, w, h, Path.Direction.CW)
                    ShapeKind.ROUND_RECT -> {
                        val r = min(l.cornerRadius.toFloat(), min(w, h) / 2f)
                        path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
                    }
                    else -> path.addOval(0f, 0f, w, h, Path.Direction.CW)
                }
                if (l.fill.enabled) canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.fill.color })
                if (l.stroke.enabled && sw > 0f) canvas.drawPath(path, strokePaint)
            }
            ShapeKind.LINE -> {
                strokePaint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(0f, h / 2f, w, h / 2f, strokePaint)
            }
            ShapeKind.ARROW -> {
                val both = l.arrowHeads == ArrowHeads.BOTH
                val atEnd = l.arrowHeads != ArrowHeads.START
                val atStart = l.arrowHeads != ArrowHeads.END
                val headLen = min(max(12f, 4f * sw), if (both) w / 2f else w)
                val headW = 0.9f * headLen
                strokePaint.strokeCap = Paint.Cap.BUTT
                val x1 = if (atStart) headLen else 0f
                val x2 = if (atEnd) w - headLen else w
                if (x2 > x1) canvas.drawLine(x1, h / 2f, x2, h / 2f, strokePaint)
                val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.stroke.color }
                val cy = h / 2f
                if (atEnd) canvas.drawPath(Path().apply {
                    moveTo(w, cy); lineTo(w - headLen, cy - headW / 2f); lineTo(w - headLen, cy + headW / 2f); close()
                }, hp)
                if (atStart) canvas.drawPath(Path().apply {
                    moveTo(0f, cy); lineTo(headLen, cy - headW / 2f); lineTo(headLen, cy + headW / 2f); close()
                }, hp)
            }
        }
    }

    // ================================================================ drawing

    private fun drawDrawing(canvas: Canvas, l: DrawingLayer) {
        val w = l.width.toFloat()
        val h = l.height.toFloat()
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        canvas.saveLayer(0f, 0f, w, h, null) // isolated buffer so the eraser only affects this layer
        for (s in l.strokes) drawStroke(canvas, s)
        canvas.restore()
        canvas.restore()
    }


    /** Rasterizes one stroke as a unit then composites it at its effective opacity (§9). */
    fun drawStroke(canvas: Canvas, s: Stroke) {
        val alphaFactor = when (s.brush) {
            Brush.MARKER, Brush.PENCIL -> 0.85f
            Brush.HIGHLIGHTER -> 0.40f
            else -> 1f
        }
        val eff = (s.opacity.toFloat() * alphaFactor).coerceIn(0f, 1f)
        val b = strokeBounds(s)
        if (s.brush == Brush.ERASER) {
            val eraserLayerPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT); alpha = (eff * 255f + 0.5f).toInt() }
            canvas.saveLayer(b, eraserLayerPaint)
        } else {
            canvas.saveLayerAlpha(b, (eff * 255f + 0.5f).toInt())
        }
        val color = if (s.brush == Brush.ERASER) Color.BLACK else s.color
        strokeShape(canvas, s, color)
        if (s.brush == Brush.PENCIL) applyGrain(canvas, b)
        canvas.restore()
    }

    private fun strokeBounds(s: Stroke): RectF {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until s.pointCount) {
            val x = s.points[2 * i]; val y = s.points[2 * i + 1]
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        val m = s.size.toFloat() * 1.5f + 4f
        return RectF(minX - m, minY - m, maxX + m, maxY + m)
    }

    /** The smoothed centerline path (§9 path rule). */
    fun strokePath(s: Stroke): Path {
        val p = Path()
        val n = s.pointCount
        val pts = s.points
        p.moveTo(pts[0], pts[1])
        if (n == 1) return p
        for (i in 1..n - 2) {
            val x = pts[2 * i]; val y = pts[2 * i + 1]
            val nx = pts[2 * i + 2]; val ny = pts[2 * i + 3]
            p.quadTo(x, y, (x + nx) / 2f, (y + ny) / 2f)
        }
        p.lineTo(pts[2 * n - 2], pts[2 * n - 1])
        return p
    }

    /**
     * Pencil grain (FORMAT.md §9.2): the stroke's alpha is multiplied (destination-in) by the
     * fixed 32×32 tile, repeated in local space at 1 local px per cell, nearest-neighbour.
     */
    private fun applyGrain(canvas: Canvas, bounds: RectF) {
        val p = Paint().apply {
            isFilterBitmap = false
            isAntiAlias = false
            shader = android.graphics.BitmapShader(grainTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(bounds, p)
    }

    private val grainTile: Bitmap by lazy {
        val n = com.halworks.basicart.model.PencilGrain.SIZE
        val a = com.halworks.basicart.model.PencilGrain.alpha
        val px = IntArray(n * n) { i -> (a[i].toInt() and 0xFF) shl 24 }
        Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    private fun strokeShape(canvas: Canvas, s: Stroke, color: Int, blurred: Boolean = true) {
        val size = s.size.toFloat()
        if (s.brush == Brush.AIRBRUSH && blurred) {
            // Soft brush: rasterize the core then blur it with σ = size·0.25 (FORMAT.md §9).
            val b = strokeBounds(s)
            drawBlurredSilhouette(canvas, b, color, size * 0.25f, 0f, 0f) { c -> strokeShape(c, s, Color.BLACK, blurred = false) }
            return
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val n = s.pointCount
        if (n == 1) {
            val tableW = when (s.brush) { Brush.AIRBRUSH -> size * 0.5f; Brush.PENCIL -> size * 0.6f; else -> size }
            val r = tableW / 2f * (s.pressure?.let { 0.25f + 0.75f * it[0] } ?: 1f)
            canvas.drawCircle(s.points[0], s.points[1], r, paint)
            return
        }
        when (s.brush) {
            Brush.CALLIGRAPHY -> {
                paint.style = Paint.Style.FILL
                canvas.drawPath(calligraphyPath(s), paint)
                return
            }
            else -> {}
        }
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = when (s.brush) {
            Brush.MARKER -> Paint.Cap.SQUARE
            Brush.HIGHLIGHTER -> Paint.Cap.BUTT
            else -> Paint.Cap.ROUND
        }
        // Pencil is a finer line than the pen at the same size.
        val baseW = when (s.brush) { Brush.AIRBRUSH -> size * 0.5f; Brush.PENCIL -> size * 0.6f; else -> size }
        val pr = s.pressure
        if (pr == null) {
            paint.strokeWidth = baseW
            canvas.drawPath(strokePath(s), paint)
        } else {
            paint.style = Paint.Style.FILL
            canvas.drawPath(variableWidthPath(s, baseW), paint)
        }
    }

    /**
     * Smooth variable-width outline for pressure strokes: samples the smoothed centerline
     * densely, interpolates width = size·(0.25 + 0.75·p) along it, and unions round discs
     * with the swept quads between them (no visible steps at any zoom).
     */
    fun variableWidthPath(s: Stroke, baseW: Float): Path {
        val n = s.pointCount
        val pr = s.pressure!!
        // cumulative chord length of the input points → pressure by arc position
        val cum = FloatArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(s.points[2 * i] - s.points[2 * i - 2], s.points[2 * i + 1] - s.points[2 * i - 1])
        val chordTotal = cum[n - 1]
        fun widthAt(frac: Float): Float {
            if (chordTotal <= 0f) return baseW * (0.25f + 0.75f * pr[0])
            val d = frac * chordTotal
            var i = 1
            while (i < n - 1 && cum[i] < d) i++
            val seg = (cum[i] - cum[i - 1]).coerceAtLeast(1e-6f)
            val t = ((d - cum[i - 1]) / seg).coerceIn(0f, 1f)
            val p = pr[i - 1] + (pr[i] - pr[i - 1]) * t
            return baseW * (0.25f + 0.75f * p)
        }
        val out = Path()
        out.fillType = Path.FillType.WINDING
        val pm = PathMeasure(strokePath(s), false)
        val len = pm.length
        if (len <= 0f) { out.addCircle(s.points[0], s.points[1], widthAt(0f) / 2f, Path.Direction.CW); return out }
        val minW = baseW * 0.25f
        val step = max(0.35f, min(minW / 3f, 4f))
        val pos = FloatArray(2); val tan = FloatArray(2)
        var px = 0f; var py = 0f; var pw = 0f; var pnx = 0f; var pny = 0f
        var d = 0f
        var first = true
        while (true) {
            val dd = min(d, len)
            pm.getPosTan(dd, pos, tan)
            val w = widthAt(dd / len) / 2f
            val nx = -tan[1]; val ny = tan[0]
            out.addCircle(pos[0], pos[1], w, Path.Direction.CW)
            if (!first) {
                // quad between consecutive discs keeps the edge continuous
                // Same orientation as the CW discs so the non-zero winding fill is a clean union.
                val xs = floatArrayOf(px + pnx * pw, pos[0] + nx * w, pos[0] - nx * w, px - pnx * pw)
                val ys = floatArrayOf(py + pny * pw, pos[1] + ny * w, pos[1] - ny * w, py - pny * pw)
                var area = 0f
                for (q in 0..3) { val r = (q + 1) % 4; area += xs[q] * ys[r] - xs[r] * ys[q] }
                val order = if (area >= 0f) intArrayOf(0, 1, 2, 3) else intArrayOf(3, 2, 1, 0)
                out.moveTo(xs[order[0]], ys[order[0]])
                for (q in 1..3) out.lineTo(xs[order[q]], ys[order[q]])
                out.close()
            }
            px = pos[0]; py = pos[1]; pw = w; pnx = nx; pny = ny
            first = false
            if (dd >= len) break
            d += step
        }
        return out
    }

    private fun calligraphyPath(s: Stroke): Path {
        val center = strokePath(s)
        val pm = PathMeasure(center, false)
        val out = Path()
        val size = s.size.toFloat()
        val nib = Math.toRadians(45.0)
        val nx = cos(nib).toFloat(); val ny = sin(nib).toFloat()
        val pos = FloatArray(2)
        val prev = FloatArray(2)
        do {
            val len = pm.length
            if (len <= 0f) continue
            val step = max(1f, min(4f, size / 6f))
            pm.getPosTan(0f, prev, null)
            var d = step
            while (true) {
                val dd = min(d, len)
                pm.getPosTan(dd, pos, null)
                val dx = pos[0] - prev[0]; val dy = pos[1] - prev[1]
                if (hypot(dx, dy) > 0.01f) {
                    val beta = atan2(dy, dx)
                    val sn = abs(sin(beta - nib)).toFloat()
                    val (ox, oy) = if (sn >= 0.15f) (nx * size / 2f) to (ny * size / 2f) else {
                        val l = hypot(dx, dy)
                        (-dy / l * size * 0.15f / 2f) to (dx / l * size * 0.15f / 2f)
                    }
                    out.moveTo(prev[0] + ox, prev[1] + oy)
                    out.lineTo(pos[0] + ox, pos[1] + oy)
                    out.lineTo(pos[0] - ox, pos[1] - oy)
                    out.lineTo(prev[0] - ox, prev[1] - oy)
                    out.close()
                }
                prev[0] = pos[0]; prev[1] = pos[1]
                if (dd >= len) break
                d += step
            }
        } while (pm.nextContour())
        out.fillType = Path.FillType.WINDING
        return out
    }

    // ================================================================ text

    /** σ in local px → BlurMaskFilter (Skia: σ ≈ 0.57735·r + 0.5, respects the CTM). */
    private fun blur(p: Paint, sigma: Float) {
        p.maskFilter = if (sigma < 0.5f) null else BlurMaskFilter((sigma - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL)
    }

    private fun fillShader(fill: Fill, w: Float, h: Float): Shader? = when (fill) {
        is Fill.Solid -> null
        is Fill.Linear -> {
            val a = Math.toRadians(fill.angle)
            val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
            val len = abs(w * dx) + abs(h * dy)
            val cx = w / 2f; val cy = h / 2f
            LinearGradient(
                cx - dx * len / 2f, cy - dy * len / 2f, cx + dx * len / 2f, cy + dy * len / 2f,
                fill.stops.map { it.color }.toIntArray(), fill.stops.map { it.offset.toFloat() }.toFloatArray(),
                Shader.TileMode.CLAMP,
            )
        }
        is Fill.Radial -> RadialGradient(
            w / 2f, h / 2f, max(0.5f, sqrt(w * w + h * h) / 2f),
            fill.stops.map { it.color }.toIntArray(), fill.stops.map { it.offset.toFloat() }.toFloatArray(),
            Shader.TileMode.CLAMP,
        )
    }

    private fun drawText(canvas: Canvas, t: TextLayer) {
        val lay = layout(t)
        val fs = t.fontSize.toFloat()
        canvas.save()
        if (t.skew != 0.0) canvas.concat(skewMatrix(t, lay.h))
        // 1. background box
        if (t.backgroundBox.enabled && t.curve == 0.0) {
            val p = t.backgroundBox.padding.toFloat() * fs
            val rect = RectF(-p, -p, lay.w + p, lay.h + p)
            val r = min(t.backgroundBox.cornerRadius.toFloat() * fs, min(rect.width(), rect.height()) / 2f)
            canvas.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.backgroundBox.color })
        }
        // 2. shadow from the body's silhouette, then 3–5 body
        if (t.shadow.enabled && Color.alpha(t.shadow.color) > 0) drawTextWithShadow(canvas, t, lay)
        else drawTextBody(canvas, t, lay)
        canvas.restore()
    }

    private fun drawTextBody(canvas: Canvas, t: TextLayer, lay: TextLayoutResult) {
        // Outline/glow/shadow are layer-wide, in em of the layer fontSize (FORMAT.md §7.2).
        val fs = t.fontSize.toFloat()
        val o = t.outline
        val join = if (o.join == Join.ROUND) Paint.Join.ROUND else Paint.Join.MITER
        fun strokeAll(width: Float, color: Int) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeJoin = join; strokeMiter = 4f; this.color = color
                strokeCap = if (join == Paint.Join.ROUND) Paint.Cap.ROUND else Paint.Cap.BUTT
            }
            for (pc in lay.pieces) {
                val sw = width + pc.synthStroke
                if (sw > 0f) { p.strokeWidth = sw; canvas.drawPath(pc.path, p) }
            }
        }
        if (o.enabled) {
            when (o.style) {
                OutlineStyle.DOUBLE -> {
                    strokeAll(2f * (o.width + o.width2).toFloat() * fs, o.color2)
                    strokeAll(2f * o.width.toFloat() * fs, o.color)
                }
                OutlineStyle.SOLID -> strokeAll(2f * o.width.toFloat() * fs, o.color)
                OutlineStyle.GLOW -> {
                    // Fill + 2·width stroke as one silhouette, Gaussian-blurred (σ = glowRadius/2).
                    val local = RectF(lay.inkBounds)
                    val ext = 2f * o.width.toFloat() * fs + lay.maxSynthStroke + 3f * o.glowRadius.toFloat() * fs / 2f + 2f
                    local.inset(-ext, -ext)
                    drawBlurredSilhouette(canvas, local, o.color, o.glowRadius.toFloat() * fs / 2f, 0f, 0f) { c ->
                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.FILL_AND_STROKE; strokeJoin = join; strokeMiter = 4f; color = Color.BLACK
                        }
                        for (pc in lay.pieces) { p.strokeWidth = 2f * o.width.toFloat() * fs + pc.synthStroke; c.drawPath(pc.path, p) }
                    }
                }
            }
        }
        // 5. fill: span colors override the layer fill (solid or gradient); gradient keeps the layer box.
        val layerShader = (t.fill as? Fill.Solid)?.let { null } ?: fillShader(t.fill, lay.w, lay.h)
        for (pc in lay.pieces) {
            val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = if (pc.synthStroke > 0f) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
                strokeWidth = pc.synthStroke; strokeJoin = Paint.Join.ROUND
                when {
                    pc.color != null -> color = pc.color
                    t.fill is Fill.Solid -> color = (t.fill as Fill.Solid).color
                    else -> { color = Color.BLACK; shader = layerShader }
                }
            }
            canvas.drawPath(pc.path, fp)
        }
        if (lay.colorGlyphs.isNotEmpty()) {
            val ep = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            for (g in lay.colorGlyphs) {
                canvas.save()
                g.matrix?.let { canvas.concat(it) }
                if (g.skewItalic) { canvas.translate(0f, g.baseline); canvas.skew(-tan(Math.toRadians(12.0)).toFloat(), 0f); canvas.translate(0f, -g.baseline) }
                ep.typeface = g.typeface
                ep.textSize = g.size
                canvas.drawText(g.text, g.x, g.baseline, ep)
                canvas.restore()
            }
        }
    }

    /**
     * Draws [draw]'s alpha silhouette (local coords, within [localBounds]) tinted with [color]
     * and Gaussian-blurred by [sigmaLocal], offset by ([dxLocal], [dyLocal]). Rasterized in
     * device space so the blur is exact and never clipped, at any scale.
     */
    @Suppress("DEPRECATION")
    fun drawBlurredSilhouette(canvas: Canvas, localBounds: RectF, color: Int, sigmaLocal: Float, dxLocal: Float, dyLocal: Float, draw: (Canvas) -> Unit) {
        val total = canvas.matrix
        val scale = total.mapRadius(1f)
        val sigmaDev = sigmaLocal * scale
        val off = floatArrayOf(dxLocal, dyLocal)
        total.mapVectors(off)
        val dev = RectF(localBounds)
        total.mapRect(dev)
        val margin = 3f * sigmaDev + 2f
        val vis = RectF(-margin - kotlin.math.abs(off[0]), -margin - kotlin.math.abs(off[1]), canvas.width + margin + kotlin.math.abs(off[0]), canvas.height + margin + kotlin.math.abs(off[1]))
        if (!dev.intersect(vis)) return
        val left = kotlin.math.floor(dev.left).toInt(); val top = kotlin.math.floor(dev.top).toInt()
        val bw = kotlin.math.ceil(dev.right).toInt() - left; val bh = kotlin.math.ceil(dev.bottom).toInt() - top
        if (bw <= 0 || bh <= 0 || bw.toLong() * bh > 80_000_000L) return
        val mask = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8) } catch (e: OutOfMemoryError) { return }
        val mc = Canvas(mask)
        val m = Matrix(total); m.postTranslate(-left.toFloat(), -top.toFloat())
        mc.setMatrix(m)
        draw(mc)
        canvas.save()
        canvas.setMatrix(Matrix())
        val sp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.color = color }
        if (sigmaDev >= 0.5f) sp.maskFilter = BlurMaskFilter((sigmaDev - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL)
        // Blurred mask drawn with a margin so the blur has room to spread.
        canvas.drawBitmap(mask, left + off[0], top + off[1], sp)
        canvas.restore()
        mask.recycle()
    }

    private fun drawTextWithShadow(canvas: Canvas, t: TextLayer, lay: TextLayoutResult) {
        val fs = t.fontSize.toFloat()
        val sh = t.shadow
        val sigma = sh.blur.toFloat() * fs / 2f
        // Silhouette of steps 3–5 (outline/glow + fill), tinted, blurred, offset (FORMAT.md §7.4).
        val local = textBounds(t.copy(shadow = t.shadow.copy(enabled = false), skew = 0.0, backgroundBox = t.backgroundBox.copy(enabled = false)))
        local.inset(-3f * sigma - 2f, -3f * sigma - 2f)
        drawBlurredSilhouette(canvas, local, sh.color, sigma, sh.offsetX.toFloat() * fs, sh.offsetY.toFloat() * fs) { c -> drawTextBody(c, t, lay) }
        drawTextBody(canvas, t, lay)
    }

    companion object {
        /** FORMAT.md §6 adjustment math as one 4×5 matrix (offsets in 0–255 units). */
        fun adjustMatrix(a: Adjust): ColorMatrix {
            val s = (a.saturation / 100.0).toFloat()
            val c = (a.contrast / 100.0).toFloat()
            val b = (a.brightness / 100.0).toFloat()
            val wm = (a.warmth / 100.0).toFloat()
            val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
            // saturation
            val sat = floatArrayOf(
                (1 + s) - s * lr, -s * lg, -s * lb, 0f, 0f,
                -s * lr, (1 + s) - s * lg, -s * lb, 0f, 0f,
                -s * lr, -s * lg, (1 + s) - s * lb, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
            val k = 1 + c
            val off = (-0.5f * c + 0.4f * b) * 255f
            val out = FloatArray(20)
            for (row in 0..3) for (col in 0..4) {
                out[row * 5 + col] = if (row < 3) sat[row * 5 + col] * k else sat[row * 5 + col]
            }
            for (row in 0..2) out[row * 5 + 4] = off
            out[0 * 5 + 4] += 0.1f * wm * 255f
            out[2 * 5 + 4] -= 0.1f * wm * 255f
            return ColorMatrix(out)
        }
    }
}

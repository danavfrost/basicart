package com.halworks.basicart.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.halworks.basicart.model.DrawingLayer
import com.halworks.basicart.model.Layer
import com.halworks.basicart.model.LayerBase
import com.halworks.basicart.model.Stroke
import com.halworks.basicart.model.Transform
import com.halworks.basicart.render.Renderer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Per-layer raster caches for the on-screen canvas. Each layer's content is rendered by
 * the shared [Renderer] in its own local space at the current zoom, then composited with
 * its transform — so moving/rotating never re-renders, and screen == export geometry.
 */
class LayerCache(private val renderer: Renderer) {
    private class Entry(var key: Layer, var k: Float, val bounds: RectF, var bmp: Bitmap)

    private val entries = HashMap<String, Entry>()
    private val keyBase = LayerBase("k", "", transform = Transform(0.0, 0.0))
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private var scratch: Bitmap? = null

    /** Everything that affects the layer's own pixels (not its placement, name or flags). */
    private fun contentKey(l: Layer) = l.withBase(keyBase.copy(mask = l.base.mask))

    private val maxPixels = 9_000_000f

    private fun ensure(l: Layer, zoom: Float, interacting: Boolean): Entry? {
        val key = contentKey(l)
        val bounds = renderer.contentBounds(l)
        if (bounds.isEmpty) return null
        var k = zoom * l.base.transform.scale.toFloat()
        val px = bounds.width() * bounds.height() * k * k
        if (px > maxPixels) k *= sqrt(maxPixels / px)
        val e = entries[l.base.id]
        if (e != null && e.key == key) {
            val ratio = k / e.k
            if (interacting || (ratio in 0.8f..1.05f)) return e
        }
        val w = ceil(bounds.width() * k).toInt().coerceAtLeast(1)
        val h = ceil(bounds.height() * k).toInt().coerceAtLeast(1)
        val bmp = try {
            e?.bmp?.takeIf { it.width == w && it.height == h }?.also { it.eraseColor(0) }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) { return e }
        val c = Canvas(bmp)
        c.scale(k, k)
        c.translate(-bounds.left, -bounds.top)
        try { renderer.drawContent(c, l, k) } catch (t: Throwable) { android.util.Log.w("LayerCache", "render failed", t) }
        if (e != null && e.bmp !== bmp) e.bmp.recycle()
        val ne = Entry(key, k, bounds, bmp)
        entries[l.base.id] = ne
        return ne
    }

    private fun screenMatrix(l: Layer, e: Entry, zoom: Float, panX: Float, panY: Float): Matrix {
        renderer.layerMatrix(l, m)
        m.preTranslate(e.bounds.left, e.bounds.top)
        m.preScale(1f / e.k, 1f / e.k)
        m.postScale(zoom, zoom)
        m.postTranslate(panX, panY)
        return m
    }

    fun draw(c: Canvas, l: Layer, zoom: Float, panX: Float, panY: Float, interacting: Boolean, alphaMul: Float = 1f) {
        val e = ensure(l, zoom, interacting) ?: return
        paint.alpha = (l.base.opacity.toFloat() * alphaMul * 255f + 0.5f).toInt().coerceIn(0, 255)
        c.drawBitmap(e.bmp, screenMatrix(l, e, zoom, panX, panY), paint)
    }

    /** Draws a drawing layer with an in-progress stroke (exact brush/eraser preview). */
    fun drawWithStroke(c: Canvas, l: Layer, s: Stroke, zoom: Float, panX: Float, panY: Float) {
        val e = ensure(l, zoom, true) ?: return
        val sc = scratch?.takeIf { it.width == e.bmp.width && it.height == e.bmp.height }
            ?: Bitmap.createBitmap(e.bmp.width, e.bmp.height, Bitmap.Config.ARGB_8888).also { scratch?.recycle(); scratch = it }
        sc.eraseColor(0)
        val sc2 = Canvas(sc)
        sc2.drawBitmap(e.bmp, 0f, 0f, null)
        sc2.scale(e.k, e.k)
        sc2.translate(-e.bounds.left, -e.bounds.top)
        if (l is DrawingLayer) sc2.clipRect(0f, 0f, l.width.toFloat(), l.height.toFloat())
        renderer.drawStroke(sc2, s)
        paint.alpha = (l.base.opacity.toFloat() * 255f + 0.5f).toInt()
        c.drawBitmap(sc, screenMatrix(l, e, zoom, panX, panY), paint)
    }

    /** After a stroke is committed, reuse the preview raster instead of re-rendering every stroke. */
    fun adoptStroke(updated: Layer) {
        val e = entries[updated.base.id] ?: return
        val sc = scratch ?: return
        if (sc.width != e.bmp.width || sc.height != e.bmp.height) return
        scratch = e.bmp
        e.bmp = sc
        e.key = contentKey(updated)
    }

    fun retain(ids: Set<String>) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val n = it.next()
            if (n.key !in ids) { n.value.bmp.recycle(); it.remove() }
        }
    }

    fun clear() {
        entries.values.forEach { it.bmp.recycle() }
        entries.clear()
        scratch?.recycle(); scratch = null
    }

    /** Layer thumbnail for the layers panel. */
    fun thumbnail(l: Layer, size: Int): Bitmap {
        val b = renderer.contentBounds(l)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val s = size / maxOf(b.width(), b.height(), 1f)
        val c = Canvas(bmp)
        c.translate((size - b.width() * s) / 2f, (size - b.height() * s) / 2f)
        c.scale(s, s)
        c.translate(-b.left, -b.top)
        try { renderer.drawContent(c, l, s) } catch (t: Throwable) { }
        return bmp
    }

    @Suppress("unused") private fun f(x: Float) = floor(x)
}

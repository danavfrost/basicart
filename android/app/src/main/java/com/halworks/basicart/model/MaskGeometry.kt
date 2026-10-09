package com.halworks.basicart.model

/**
 * Keeps eraser masks attached to content (FORMAT.md §9.1). Pure math, unit-tested.
 */
object MaskGeometry {
    /** natural (oriented) px → displayed box px for [l] (crop, rotate90 cw, flipH, flipV). */
    fun naturalToBox(l: ImageLayer, x: Double, y: Double): Pair<Double, Double> {
        val cw = l.crop.width; val ch = l.crop.height
        var px = x - l.crop.x; var py = y - l.crop.y
        when (Math.floorMod(l.rotate90, 4)) {
            1 -> { val nx = ch - py; val ny = px; px = nx; py = ny }
            2 -> { px = cw - px; py = ch - py }
            3 -> { val nx = py; val ny = cw - px; px = nx; py = ny }
        }
        if (l.flipH) px = l.boxWidth - px
        if (l.flipV) py = l.boxHeight - py
        return px to py
    }

    /** Inverse of [naturalToBox]. */
    fun boxToNatural(l: ImageLayer, x: Double, y: Double): Pair<Double, Double> {
        val cw = l.crop.width; val ch = l.crop.height
        var px = if (l.flipH) l.boxWidth - x else x
        var py = if (l.flipV) l.boxHeight - y else y
        when (Math.floorMod(l.rotate90, 4)) {
            1 -> { val nx = py; val ny = ch - px; px = nx; py = ny }
            2 -> { px = cw - px; py = ch - py }
            3 -> { val nx = cw - py; val ny = px; px = nx; py = ny }
        }
        return (px + l.crop.x) to (py + l.crop.y)
    }

    private fun mapPoints(mask: List<Stroke>, f: (Double, Double) -> Pair<Double, Double>, sizeK: Double = 1.0): List<Stroke> =
        mask.map { s ->
            val src = s.points.toArray()
            val out = FloatArray(src.size)
            for (i in 0 until src.size / 2) {
                val (x, y) = f(src[2 * i].toDouble(), src[2 * i + 1].toDouble())
                out[2 * i] = x.toFloat(); out[2 * i + 1] = y.toFloat()
            }
            s.copy(points = FloatList(out), size = (s.size * sizeK).coerceIn(1.0, 200.0 * maxOf(1.0, sizeK)))
        }

    /** Image crop/rotate/flip changed: box(old) → natural → box(new). Size unchanged. */
    fun remapImage(old: ImageLayer, new: ImageLayer): ImageLayer {
        if (old.base.mask.isEmpty()) return new
        if (old.crop == new.crop && old.rotate90 == new.rotate90 && old.flipH == new.flipH && old.flipV == new.flipV) return new
        val mapped = mapPoints(old.base.mask, { x, y -> boxToNatural(old, x, y).let { (nx, ny) -> naturalToBox(new, nx, ny) } })
        return new.copy(base = new.base.copy(mask = mapped))
    }

    /**
     * Text corner/pinch scaling by k: points scale about the box center and `size` × k.
     * [oldCenter] is the box center before the gesture, [newCenter] after (equal to k·oldCenter
     * for exact scaling; using the measured center keeps the mask glued to the glyphs).
     */
    fun scaleText(mask: List<Stroke>, k: Double, oldCx: Double, oldCy: Double, newCx: Double, newCy: Double): List<Stroke> =
        mapPoints(mask, { x, y -> (newCx + k * (x - oldCx)) to (newCy + k * (y - oldCy)) }, k)
}

package com.halworks.basicart.model

import java.io.ByteArrayOutputStream

/**
 * Still-image GIF89a encoder: median-cut palette (≤256 colors, one entry reserved for
 * transparency when needed), Floyd–Steinberg dithering, LZW compression.
 * Input: ARGB pixels (straight alpha). Alpha < 128 becomes transparent.
 */
object GifEncoder {

    fun encode(argb: IntArray, width: Int, height: Int, dither: Boolean = true, progress: (Float) -> Unit = {}): ByteArray {
        require(argb.size == width * height && width in 1..65535 && height in 1..65535)
        val hasTransparency = argb.any { (it ushr 24) < 128 }
        val maxColors = if (hasTransparency) 255 else 256
        val palette = medianCut(argb, maxColors)
        progress(0.2f)
        val indices = mapPixels(argb, width, height, palette, hasTransparency, dither) { progress(0.2f + 0.5f * it) }
        val transparentIndex = if (hasTransparency) palette.size else -1
        val colorCount = palette.size + if (hasTransparency) 1 else 0
        var bits = 1
        while ((1 shl bits) < colorCount) bits++
        val tableSize = 1 shl bits

        val out = ByteArrayOutputStream(width * height / 2 + 1024)
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(out, width); writeShort(out, height)
        out.write(0x80 or ((bits - 1) shl 4) or (bits - 1)) // global table, color resolution, size
        out.write(0); out.write(0)
        for (i in 0 until tableSize) {
            val c = if (i < palette.size) palette[i] else 0
            out.write((c shr 16) and 0xFF); out.write((c shr 8) and 0xFF); out.write(c and 0xFF)
        }
        if (hasTransparency) {
            out.write(0x21); out.write(0xF9); out.write(4)
            out.write(0x01) // transparent color flag, no disposal
            writeShort(out, 0)
            out.write(transparentIndex); out.write(0)
        }
        out.write(0x2C); writeShort(out, 0); writeShort(out, 0); writeShort(out, width); writeShort(out, height); out.write(0)
        val minCode = maxOf(2, bits)
        out.write(minCode)
        lzw(indices, minCode, out)
        progress(1f)
        out.write(0) // block terminator
        out.write(0x3B)
        return out.toByteArray()
    }

    private fun writeShort(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }

    // ----------------------------------------------------------- palette

    private class Box(val colors: IntArray, val counts: IntArray, val from: Int, val to: Int) {
        var rMin = 0; var rMax = 0; var gMin = 0; var gMax = 0; var bMin = 0; var bMax = 0
        var total = 0L
        init {
            rMin = 255; gMin = 255; bMin = 255
            for (i in from until to) {
                val c = colors[i]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (b < bMin) bMin = b; if (b > bMax) bMax = b
                total += counts[i]
            }
        }
        val size get() = to - from
        val longest get() = maxOf(rMax - rMin, gMax - gMin, bMax - bMin)
    }

    /** Median cut over a 5-bit-per-channel histogram of opaque pixels. */
    fun medianCut(argb: IntArray, maxColors: Int): IntArray {
        val hist = IntArray(32768)
        for (p in argb) {
            if ((p ushr 24) < 128) continue
            hist[((p shr 9) and 0x7C00) or ((p shr 6) and 0x3E0) or ((p shr 3) and 0x1F)]++
        }
        var n = 0
        for (h in hist) if (h > 0) n++
        if (n == 0) return intArrayOf(0)
        val colors = IntArray(n); val counts = IntArray(n)
        var k = 0
        for (i in hist.indices) if (hist[i] > 0) {
            val r = (i shr 10) and 31; val g = (i shr 5) and 31; val b = i and 31
            colors[k] = ((r shl 3 or (r shr 2)) shl 16) or ((g shl 3 or (g shr 2)) shl 8) or (b shl 3 or (b shr 2))
            counts[k] = hist[i]; k++
        }
        if (n <= maxColors) return colors.copyOf()
        val boxes = ArrayList<Box>()
        boxes.add(Box(colors, counts, 0, n))
        while (boxes.size < maxColors) {
            val idx = boxes.indices.filter { boxes[it].size > 1 }.maxByOrNull { boxes[it].longest.toLong() * 4 + boxes[it].total / 1_000_000 } ?: break
            val bx = boxes.removeAt(idx)
            val shift = when (bx.longest) { bx.rMax - bx.rMin -> 16; bx.gMax - bx.gMin -> 8; else -> 0 }
            // sort the box's slice by the longest channel
            val slice = (bx.from until bx.to).sortedBy { (colors[it] shr shift) and 0xFF }
            val c2 = slice.map { colors[it] }; val n2 = slice.map { counts[it] }
            for (j in slice.indices) { colors[bx.from + j] = c2[j]; counts[bx.from + j] = n2[j] }
            var acc = 0L; var mid = bx.from
            val half = bx.total / 2
            while (mid < bx.to - 1 && acc + counts[mid] <= half) { acc += counts[mid]; mid++ }
            if (mid == bx.from) mid++
            boxes.add(Box(colors, counts, bx.from, mid))
            boxes.add(Box(colors, counts, mid, bx.to))
        }
        return IntArray(boxes.size) { i ->
            val b = boxes[i]
            var r = 0L; var g = 0L; var bl = 0L
            for (j in b.from until b.to) {
                val c = colors[j]; val w = counts[j].toLong()
                r += ((c shr 16) and 0xFF) * w; g += ((c shr 8) and 0xFF) * w; bl += (c and 0xFF) * w
            }
            val t = maxOf(1L, b.total)
            ((r / t).toInt() shl 16) or ((g / t).toInt() shl 8) or (bl / t).toInt()
        }
    }

    private fun mapPixels(argb: IntArray, w: Int, h: Int, pal: IntArray, transp: Boolean, dither: Boolean, progress: (Float) -> Unit): ByteArray {
        val out = ByteArray(argb.size)
        val cache = IntArray(32768) { -1 }
        fun nearest(r: Int, g: Int, b: Int): Int {
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            val c = cache[key]
            if (c >= 0) return c
            var best = 0; var bd = Int.MAX_VALUE
            for (i in pal.indices) {
                val p = pal[i]
                val dr = ((p shr 16) and 0xFF) - r; val dg = ((p shr 8) and 0xFF) - g; val db = (p and 0xFF) - b
                val d = dr * dr * 2 + dg * dg * 4 + db * db * 3
                if (d < bd) { bd = d; best = i }
            }
            cache[key] = best
            return best
        }
        // error buffers for current and next row (r,g,b)
        var cur = IntArray((w + 2) * 3)
        var nxt = IntArray((w + 2) * 3)
        val tIndex = pal.size
        for (y in 0 until h) {
            java.util.Arrays.fill(nxt, 0)
            for (x in 0 until w) {
                val i = y * w + x
                val p = argb[i]
                if (transp && (p ushr 24) < 128) { out[i] = tIndex.toByte(); continue }
                var r = (p shr 16) and 0xFF; var g = (p shr 8) and 0xFF; var b = p and 0xFF
                if (dither) {
                    r = (r + cur[(x + 1) * 3] / 16).coerceIn(0, 255)
                    g = (g + cur[(x + 1) * 3 + 1] / 16).coerceIn(0, 255)
                    b = (b + cur[(x + 1) * 3 + 2] / 16).coerceIn(0, 255)
                }
                val idx = nearest(r, g, b)
                out[i] = idx.toByte()
                if (dither) {
                    val q = pal[idx]
                    val er = r - ((q shr 16) and 0xFF); val eg = g - ((q shr 8) and 0xFF); val eb = b - (q and 0xFF)
                    fun add(buf: IntArray, xx: Int, f: Int) {
                        val o = (xx + 1) * 3
                        buf[o] += er * f; buf[o + 1] += eg * f; buf[o + 2] += eb * f
                    }
                    add(cur, x + 1, 7); add(nxt, x - 1, 3); add(nxt, x, 5); add(nxt, x + 1, 1)
                }
            }
            val t = cur; cur = nxt; nxt = t
            if (y % 64 == 0) progress(y.toFloat() / h)
        }
        return out
    }

    // ----------------------------------------------------------- LZW

    private fun lzw(data: ByteArray, minCodeSize: Int, out: ByteArrayOutputStream) {
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        var codeSize = minCodeSize + 1
        var next = eoi + 1
        // dictionary: key = prefix code shl 8 | byte → code
        val dict = HashMap<Int, Int>(8192)
        val block = ByteArray(255); var blockLen = 0
        var bitBuf = 0L; var bitCount = 0
        fun flushBlock() { if (blockLen > 0) { out.write(blockLen); out.write(block, 0, blockLen); blockLen = 0 } }
        fun emit(code: Int) {
            bitBuf = bitBuf or (code.toLong() shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                block[blockLen++] = (bitBuf and 0xFF).toByte()
                if (blockLen == 255) flushBlock()
                bitBuf = bitBuf ushr 8; bitCount -= 8
            }
        }
        emit(clear)
        if (data.isEmpty()) { emit(eoi); if (bitCount > 0) { block[blockLen++] = (bitBuf and 0xFF).toByte() }; flushBlock(); return }
        var prefix = data[0].toInt() and 0xFF
        for (i in 1 until data.size) {
            val b = data[i].toInt() and 0xFF
            val key = (prefix shl 8) or b
            val found = dict[key]
            if (found != null) { prefix = found; continue }
            emit(prefix)
            if (next < 4096) {
                dict[key] = next++
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                emit(clear)
                dict.clear(); codeSize = minCodeSize + 1; next = eoi + 1
            }
            prefix = b
        }
        emit(prefix)
        emit(eoi)
        if (bitCount > 0) { block[blockLen++] = (bitBuf and 0xFF).toByte(); if (blockLen == 255) flushBlock() }
        flushBlock()
    }
}

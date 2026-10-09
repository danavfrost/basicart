package com.halworks.basicart.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Decodes our own GIFs with an independent LZW decoder to verify validity. */
class GifEncoderTest {
    private class Decoded(val w: Int, val h: Int, val palette: IntArray, val transparent: Int, val indices: IntArray)

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun decode(b: ByteArray): Decoded {
        assertEquals("GIF89a", String(b, 0, 6, Charsets.US_ASCII))
        val w = u16(b, 6); val h = u16(b, 8)
        val flags = b[10].toInt() and 0xFF
        val size = 1 shl ((flags and 7) + 1)
        val pal = IntArray(size) { i -> ((b[13 + i * 3].toInt() and 0xFF) shl 16) or ((b[14 + i * 3].toInt() and 0xFF) shl 8) or (b[15 + i * 3].toInt() and 0xFF) }
        var p = 13 + size * 3
        var transparent = -1
        if (b[p].toInt() and 0xFF == 0x21) {
            assertEquals(0xF9, b[p + 1].toInt() and 0xFF)
            if (b[p + 3].toInt() and 1 == 1) transparent = b[p + 6].toInt() and 0xFF
            p += 8
        }
        assertEquals(0x2C, b[p].toInt() and 0xFF)
        assertEquals(w, u16(b, p + 5)); assertEquals(h, u16(b, p + 7))
        p += 10
        val minCode = b[p++].toInt()
        val data = java.io.ByteArrayOutputStream()
        while (true) { val n = b[p++].toInt() and 0xFF; if (n == 0) break; data.write(b, p, n); p += n }
        assertEquals(0x3B, b[p].toInt() and 0xFF)
        val bytes = data.toByteArray()
        // LZW decode
        val clear = 1 shl minCode; val eoi = clear + 1
        val out = IntArray(w * h); var o = 0
        var codeSize = minCode + 1
        val dict = ArrayList<IntArray>()
        fun reset() { dict.clear(); for (i in 0 until clear) dict.add(intArrayOf(i)); dict.add(IntArray(0)); dict.add(IntArray(0)); codeSize = minCode + 1 }
        reset()
        var bitPos = 0
        var prev: IntArray? = null
        while (true) {
            var code = 0
            for (k in 0 until codeSize) {
                val byte = bytes[(bitPos + k) / 8].toInt() and 0xFF
                if ((byte shr ((bitPos + k) % 8)) and 1 == 1) code = code or (1 shl k)
            }
            bitPos += codeSize
            if (code == clear) { reset(); prev = null; continue }
            if (code == eoi) break
            val entry = when {
                code < dict.size -> dict[code]
                prev != null -> prev + prev[0]
                else -> error("bad code")
            }
            for (v in entry) out[o++] = v
            if (prev != null && dict.size < 4096) dict.add(prev + entry[0])
            if (dict.size == (1 shl codeSize) && codeSize < 12) codeSize++
            prev = entry
        }
        assertEquals(w * h, o)
        return Decoded(w, h, pal, transparent, out)
    }

    @Test fun smallExactImageRoundTrips() {
        val w = 7; val h = 5
        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
        val px = IntArray(w * h) { colors[it % 4] }
        val d = decode(GifEncoder.encode(px, w, h, dither = false))
        assertEquals(w, d.w); assertEquals(h, d.h); assertEquals(-1, d.transparent)
        for (i in px.indices) {
            val q = d.palette[d.indices[i]]
            val c = px[i] and 0xFFFFFF
            // 5-bit histogram → colors are within 8 levels per channel
            for (s in listOf(16, 8, 0)) assertTrue(kotlin.math.abs(((q shr s) and 0xFF) - ((c shr s) and 0xFF)) <= 8)
        }
    }

    @Test fun largeNoisyImageWithTransparencyDecodes() {
        val w = 300; val h = 211
        val rnd = Random(42)
        val px = IntArray(w * h) { i ->
            if (i % 17 == 0) 0 else (0xFF shl 24) or (rnd.nextInt(256) shl 16) or (((i / w) * 255 / h) shl 8) or ((i % w) * 255 / w)
        }
        val d = decode(GifEncoder.encode(px, w, h))
        assertEquals(w, d.w); assertEquals(h, d.h)
        assertTrue(d.transparent >= 0)
        for (i in px.indices) if ((px[i] ushr 24) == 0) assertEquals(d.transparent, d.indices[i])
        assertTrue(d.indices.filterIndexed { i, _ -> (px[i] ushr 24) != 0 }.none { it == d.transparent })
    }

    @Test fun singleColorAndTinyImages() {
        val d = decode(GifEncoder.encode(IntArray(1) { 0xFF123456.toInt() }, 1, 1))
        assertEquals(1, d.w)
        val d2 = decode(GifEncoder.encode(IntArray(64 * 64) { 0 }, 64, 64))
        assertTrue(d2.indices.all { it == d2.transparent })
    }
}

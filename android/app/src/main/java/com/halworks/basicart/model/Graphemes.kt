package com.halworks.basicart.model

/**
 * Extended-grapheme-cluster segmentation that behaves identically on the JVM (unit tests)
 * and on every Android version. It covers what matters for an editor: surrogate pairs,
 * combining marks, variation selectors, emoji modifiers, ZWJ sequences, tag sequences,
 * regional-indicator flags, keycaps and CR LF.
 */
object Graphemes {

    /** Sorted boundary offsets including 0 and text.length. */
    fun boundaries(text: String): IntArray {
        val out = ArrayList<Int>(text.length + 1)
        out.add(0)
        var i = 0
        var riCount = 0
        var prevCp = -1
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            if (i > 0 && isBoundary(prevCp, cp, riCount)) out.add(i)
            riCount = if (isRegionalIndicator(cp)) {
                if (isRegionalIndicator(prevCp)) riCount + 1 else 1
            } else 0
            prevCp = cp
            i += len
        }
        if (text.isNotEmpty()) out.add(text.length)
        return out.toIntArray()
    }

    private fun isBoundary(prev: Int, cp: Int, riCount: Int): Boolean {
        if (prev == '\r'.code && cp == '\n'.code) return false
        if (prev == '\n'.code || prev == '\r'.code || cp == '\n'.code || cp == '\r'.code) return true
        if (prev == 0x200D) return false // after ZWJ
        if (isExtend(cp)) return false
        if (isRegionalIndicator(prev) && isRegionalIndicator(cp)) return riCount % 2 == 0
        return true
    }

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private fun isExtend(cp: Int): Boolean {
        if (cp == 0x200D || cp == 0x200C) return true
        if (cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF) return true
        if (cp in 0x1F3FB..0x1F3FF) return true // skin tones
        if (cp in 0xE0020..0xE007F) return true // tags
        if (cp == 0x20E3) return true // keycap
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() ||
            t == Character.ENCLOSING_MARK.toInt() ||
            t == Character.COMBINING_SPACING_MARK.toInt()
    }

    /** Largest boundary ≤ offset. */
    fun floor(b: IntArray, offset: Int): Int {
        var r = 0
        for (x in b) if (x <= offset) r = x else break
        return r
    }

    /** Smallest boundary ≥ offset. */
    fun ceil(b: IntArray, offset: Int): Int {
        for (x in b) if (x >= offset) return x
        return b.last()
    }
}

package com.halworks.basicart.model

import java.util.Locale

object Colors {
    /** Parses "#RRGGBBAA" or "#RRGGBB" (any case) to an ARGB int, or null when invalid. */
    fun parse(s: String): Int? {
        if (!s.startsWith("#")) return null
        val hex = s.substring(1)
        if (hex.length != 6 && hex.length != 8) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val v = hex.toLong(16)
        return if (hex.length == 6) (0xFF000000L or v).toInt()
        else {
            val rgb = (v ushr 8) and 0xFFFFFF
            val a = v and 0xFF
            ((a shl 24) or rgb).toInt()
        }
    }

    /** ARGB int to "#RRGGBBAA" uppercase. */
    fun format(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val rgb = argb and 0xFFFFFF
        return String.format(Locale.ROOT, "#%06X%02X", rgb, a)
    }

    /** "#RRGGBB" (no alpha) for display in the hex field. */
    fun formatRgb(argb: Int) = String.format(Locale.ROOT, "%06X", argb and 0xFFFFFF)

    fun alpha(argb: Int) = (argb ushr 24) and 0xFF
    fun withAlpha(argb: Int, a: Int) = (argb and 0xFFFFFF) or (a.coerceIn(0, 255) shl 24)
}

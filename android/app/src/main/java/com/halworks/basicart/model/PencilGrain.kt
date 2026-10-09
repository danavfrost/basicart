package com.halworks.basicart.model

/** Pencil grain tile (FORMAT.md §9.2): 32×32 alpha values, bit-exact on both platforms. */
object PencilGrain {
    const val SIZE = 32

    val alpha: ByteArray by lazy {
        val out = ByteArray(SIZE * SIZE)
        var seed = 1L
        for (i in out.indices) {
            seed = (seed * 1103515245L + 12345L) and 0x7FFFFFFFL
            val v = ((seed shr 16) and 0xFF).toInt()
            out[i] = ((11475 + 55 * v) / 100).toByte() // integer division → 114..255
        }
        out
    }
}

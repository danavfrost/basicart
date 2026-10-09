package com.halworks.basicart.model

data class FontFileSpec(val weight: Int, val italic: Boolean, val styleName: String, val path: String)

data class FontChoice(val file: FontFileSpec, val synthBold: Boolean, val synthItalic: Boolean)

/** Font-file selection for a run (FORMAT.md §7.5). */
object FontSelect {
    fun select(files: List<FontFileSpec>, weightIn: Int, bold: Boolean, italic: Boolean): FontChoice {
        val w = weightIn.coerceIn(100, 900)
        val italics = files.filter { it.italic }
        val useItalicFiles = italic && italics.isNotEmpty()
        val pool = if (useItalicFiles) italics else files.filter { !it.italic }.ifEmpty { files }
        val synthItalic = italic && !useItalicFiles
        // Step 2: nearest W, ties → lighter.
        val regular = pool.minWith(compareBy<FontFileSpec>({ kotlin.math.abs(it.weight - w) }, { it.weight }))
        if (!bold) return FontChoice(regular, false, synthItalic)
        val t = maxOf(700, w)
        val cands = pool.filter { it.weight >= 600 && it.weight >= w }
        if (cands.isEmpty()) return FontChoice(regular, true, synthItalic)
        // Nearest T, ties → heavier.
        val b = cands.minWith(compareBy<FontFileSpec>({ kotlin.math.abs(it.weight - t) }, { -it.weight }))
        return FontChoice(b, false, synthItalic)
    }

    /** Weight of [files]' non-italic entry nearest [old] (family switch rule, §11). */
    fun nearestWeight(files: List<FontFileSpec>, old: Int): Int {
        val pool = files.filter { !it.italic }.ifEmpty { files }
        return pool.minWith(compareBy<FontFileSpec>({ kotlin.math.abs(it.weight - old) }, { it.weight })).weight
    }
}

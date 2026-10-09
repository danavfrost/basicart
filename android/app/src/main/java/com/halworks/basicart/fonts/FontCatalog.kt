package com.halworks.basicart.fonts

import android.content.res.AssetManager
import android.graphics.Typeface
import android.util.Log
import com.halworks.basicart.model.FontChoice
import com.halworks.basicart.model.FontFileSpec
import com.halworks.basicart.model.FontSelect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

data class FontGroup(val id: String, val name: String, val sampleFontId: String)

data class FontFamily(
    val id: String,
    val family: String,
    val category: String,
    val group: String,
    val files: List<FontFileSpec>,
    val licenseName: String,
    val licenseFile: String,
    val copyright: String,
    val author: String,
) {
    /** Non-italic styles for the weight picker, lightest first. */
    val uprightStyles get() = files.filter { !it.italic }.sortedBy { it.weight }
    /** Weights offered in the weight picker: Regular comes from tapping the family, Bold from B. */
    val extraStyles get() = uprightStyles.filter { it.weight != 400 && it.weight != 700 }
    val hasExtraWeights get() = extraStyles.isNotEmpty()
    val defaultWeight get() = FontSelect.nearestWeight(files, 400)
}

/** [fileKey] identifies the font file + `wght` value: graphemes with equal keys (and size/synth flags) shape as one run. */
class ResolvedFace(val typeface: Typeface, val synthBold: Boolean, val synthItalic: Boolean, val fileKey: String = "")

/** Vertical metrics of one font file from shared/fonts/metrics.json (FORMAT.md §7.3 step 7). */
class FileMetrics(val unitsPerEm: Float, val ascender: Float, val descender: Float, val wght: FloatArray?)

/**
 * The bundled font catalog (shared/fonts/fonts.json packaged at the assets root).
 * Metadata parses once (~170 KB) on first use; typefaces load lazily per (file, weight)
 * and are cached, so startup never touches the font files.
 */
class FontCatalog(private val assets: AssetManager) {
    val groups: List<FontGroup>
    val fonts: List<FontFamily>
    private val byId: Map<String, FontFamily>

    init {
        val root = Json.parseToJsonElement(assets.open("fonts.json").use { it.readBytes().decodeToString() }).jsonObject
        groups = root["groups"]!!.jsonArray.map {
            val o = it.jsonObject
            FontGroup(o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["sampleFontId"]!!.jsonPrimitive.content)
        }
        fonts = root["fonts"]!!.jsonArray.map {
            val o = it.jsonObject
            val lic = o["license"]!!.jsonObject
            FontFamily(
                id = o["id"]!!.jsonPrimitive.content,
                family = o["family"]!!.jsonPrimitive.content,
                category = o["category"]!!.jsonPrimitive.content,
                group = o["group"]!!.jsonPrimitive.content,
                files = o["files"]!!.jsonArray.map { f ->
                    val fo = f.jsonObject
                    FontFileSpec(
                        fo["weight"]!!.jsonPrimitive.int, fo["italic"]!!.jsonPrimitive.boolean,
                        fo["styleName"]!!.jsonPrimitive.content, fo["path"]!!.jsonPrimitive.content,
                    )
                },
                licenseName = lic["name"]!!.jsonPrimitive.content,
                licenseFile = lic["file"]!!.jsonPrimitive.content,
                copyright = o["copyright"]?.jsonPrimitive?.content ?: "",
                author = o["author"]?.jsonPrimitive?.content ?: "",
            )
        }
        byId = fonts.associateBy { it.id }
    }

    /** shared/fonts/metrics.json: the only source of vertical metrics and of `wght` axis ranges. */
    val metrics: Map<String, FileMetrics> by lazy {
        try {
            val root = Json.parseToJsonElement(assets.open("metrics.json").use { it.readBytes().decodeToString() }).jsonObject
            root["files"]!!.jsonObject.mapValues { (_, v) ->
                val o = v.jsonObject
                val ax = (o["wght"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content.toFloat() }?.toFloatArray()
                FileMetrics(o["unitsPerEm"]!!.jsonPrimitive.content.toFloat(), o["ascender"]!!.jsonPrimitive.content.toFloat(),
                    o["descender"]!!.jsonPrimitive.content.toFloat(), ax)
            }
        } catch (e: Exception) {
            Log.w("FontCatalog", "metrics.json unavailable", e); emptyMap()
        }
    }

    private val vCache = ConcurrentHashMap<String, Pair<Float, Float>>()

    /**
     * Ascent and descent per 1 px of size (positive) for a run of [fontId] at [weight]: from the
     * file §7.5 step 2 picks (non-italic, not bold), hhea ascender/descender over unitsPerEm.
     */
    fun vMetrics(fontId: String, weight: Int): Pair<Float, Float> = vCache.getOrPut("$fontId|$weight") {
        val fam = family(fontId)
        val file = FontSelect.select(fam.files, weight.coerceIn(100, 900), false, false).file
        val m = metrics[file.path]
        if (m == null || m.unitsPerEm <= 0f) 0.9f to 0.25f else m.ascender / m.unitsPerEm to -m.descender / m.unitsPerEm
    }

    /** The `wght` value a file entry is used at (clamped to the axis), or null for static files. */
    private fun wghtFor(file: FontFileSpec): Float? {
        val m = metrics[file.path]
        if (m != null) return m.wght?.let { ax -> file.weight.toFloat().coerceIn(ax[0], ax[ax.size - 1]) }
        return if (isVariable(file.path)) file.weight.toFloat() else null
    }

    fun family(id: String): FontFamily = byId[id] ?: byId["inter"] ?: fonts.first()
    fun has(id: String) = byId.containsKey(id)
    fun inGroup(groupId: String) = fonts.filter { it.group == groupId }

    /** Files used by several weight entries are variable fonts even if not named so. */
    private val sharedPaths: Set<String> by lazy {
        fonts.flatMap { f -> f.files.groupBy { it.path }.filter { it.value.size > 1 }.keys }.toSet()
    }

    private val typefaces = ConcurrentHashMap<String, Typeface>()
    private val resolved = ConcurrentHashMap<String, ResolvedFace>()

    /** Resolves the face for a run (§7.5). Unknown ids resolve against `inter`. */
    fun resolve(fontId: String, weight: Int, bold: Boolean, italic: Boolean): ResolvedFace {
        val key = "$fontId|$weight|$bold|$italic"
        resolved[key]?.let { return it }
        val fam = family(fontId)
        val choice: FontChoice = FontSelect.select(fam.files, weight, bold, italic)
        val tf = typeface(choice.file)
        val fk = choice.file.path + "@" + (wghtFor(choice.file) ?: "")
        return ResolvedFace(tf, choice.synthBold, choice.synthItalic, fk).also { resolved[key] = it }
    }

    /** Typeface for one file entry, with the 'wght' axis set for variable fonts. */
    fun typeface(file: FontFileSpec): Typeface {
        val key = "${file.path}@${file.weight}"
        typefaces[key]?.let { return it }
        val tf = try {
            val b = Typeface.Builder(assets, file.path)
            wghtFor(file)?.let { b.setFontVariationSettings("'wght' $it") }
            b.setWeight(file.weight).setItalic(file.italic)
            b.build() ?: Typeface.DEFAULT
        } catch (e: Exception) {
            Log.w("FontCatalog", "Failed to load ${file.path}", e)
            Typeface.DEFAULT
        }
        typefaces[key] = tf
        return tf
    }

    /** Typeface for previewing a family name in the picker (its default upright style). */
    fun previewTypeface(fontId: String, weight: Int? = null): Typeface {
        val fam = family(fontId)
        val w = weight ?: fam.defaultWeight
        return typeface(FontSelect.select(fam.files, w, false, false).file)
    }

    private fun isVariable(path: String): Boolean {
        val n = path.substringAfterLast('/').lowercase()
        return n.contains("variable") || n.contains("[wght") || path in sharedPaths
    }
}

package com.halworks.basicart.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.round

sealed interface LoadResult {
    data class Ok(val doc: Document) : LoadResult
    /** formatVersion > SUPPORTED: not opened; [name] if it could be read. */
    data class NewerVersion(val version: Int, val name: String?) : LoadResult
    data class Corrupt(val reason: String) : LoadResult
}

class CorruptException(msg: String) : Exception(msg)

/**
 * Reads and writes project.json and strokes files (FORMAT.md §3–§10).
 * Strict about types (no coercion), lenient about ranges (clamped).
 */
object ProjectCodec {
    const val SUPPORTED = FORMAT_VERSION
    private val ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val ASSET_RE = Regex("^[0-9a-f]{64}\\.(png|jpg|webp|gif|heic)$")

    @OptIn(ExperimentalSerializationApi::class)
    private val writer = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val reader = Json

    // ================================================================ reading

    /** Classifies and loads [text]. [folderId] wins over the stored id (§2). Strokes are attached later. */
    fun load(text: String, folderId: String? = null): LoadResult {
        val root = try {
            reader.parseToJsonElement(text)
        } catch (e: Exception) {
            return LoadResult.Corrupt("Invalid JSON")
        }
        if (root !is JsonObject) return LoadResult.Corrupt("Not an object")
        val fv = root["formatVersion"]
        val version = (fv as? JsonPrimitive)?.takeIf { !it.isString }?.let { intOrNull(it) }
            ?: return LoadResult.Corrupt("Missing formatVersion")
        if (version > SUPPORTED) {
            val name = (root["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return LoadResult.NewerVersion(version, name)
        }
        if (version < 1) return LoadResult.Corrupt("Bad formatVersion")
        return try {
            // No migrations exist in v1 (§10.1).
            LoadResult.Ok(readDocument(root, folderId))
        } catch (e: CorruptException) {
            LoadResult.Corrupt(e.message ?: "Corrupt")
        } catch (e: Exception) {
            LoadResult.Corrupt("Unreadable: ${e.javaClass.simpleName}")
        }
    }

    private fun readDocument(o: JsonObject, folderId: String?): Document {
        val storedId = reqString(o, "id")
        val id = folderId ?: storedId
        val name = optString(o, "name")?.trim()?.take(100)?.ifEmpty { null } ?: "Untitled"
        val created = optString(o, "created") ?: ""
        val modified = optString(o, "modified") ?: ""
        val generator = optString(o, "generator") ?: ""
        val canvasO = reqObject(o, "canvas")
        val w = reqInt(canvasO, "width")
        val h = reqInt(canvasO, "height")
        if (w !in 16..8192 || h !in 16..8192) throw CorruptException("Canvas size out of range")
        val bg = optColor(canvasO, "background") ?: WHITE
        val canvas = CanvasSpec(w, h, bg)
        val arr = o["layers"] as? JsonArray ?: throw CorruptException("layers missing")
        val ids = HashSet<String>()
        val layers = arr.map { el ->
            val lo = el as? JsonObject ?: throw CorruptException("Layer is not an object")
            val layer = readLayer(lo, canvas)
            if (!ids.add(layer.id)) throw CorruptException("Duplicate layer id ${layer.id}")
            layer
        }
        return Document(id, name, created, modified, generator, canvas, layers)
    }

    private fun readLayer(o: JsonObject, canvas: CanvasSpec): Layer {
        val id = reqString(o, "id")
        if (!ID_RE.matches(id)) throw CorruptException("Bad layer id")
        val type = reqString(o, "type")
        val t = reqObject(o, "transform")
        val transform = Transform(
            x = optNum(t, "x") ?: (canvas.width / 2.0),
            y = optNum(t, "y") ?: (canvas.height / 2.0),
            scale = (optNum(t, "scale") ?: 1.0).coerceIn(0.01, 100.0),
            rotation = normAngle(optNum(t, "rotation") ?: 0.0),
        )
        val defaultName = when (type) {
            "image" -> "Image"; "text" -> "Text"; "drawing" -> "Drawing"; "shape" -> "Shape"; else -> type
        }
        val base = LayerBase(
            id = id,
            name = (optString(o, "name") ?: defaultName).take(100),
            visible = optBool(o, "visible") ?: true,
            locked = optBool(o, "locked") ?: false,
            opacity = (optNum(o, "opacity") ?: 1.0).coerceIn(0.0, 1.0),
            blendMode = "normal",
            transform = transform,
        )
        return when (type) {
            "image" -> readImage(o, base)
            "text" -> readText(o, base)
            "shape" -> readShape(o, base)
            "drawing" -> {
                val w = reqInt(o, "width").coerceIn(1, 8192)
                val h = reqInt(o, "height").coerceIn(1, 8192)
                DrawingLayer(base, w, h, emptyList(), StrokesState.MISSING)
            }
            else -> throw CorruptException("Unknown layer type $type")
        }
    }

    private fun readImage(o: JsonObject, base: LayerBase): ImageLayer {
        val assetRef = reqString(o, "assetRef")
        if (!ASSET_RE.matches(assetRef)) throw CorruptException("Bad assetRef")
        val nw = reqInt(o, "naturalWidth")
        val nh = reqInt(o, "naturalHeight")
        if (nw < 1 || nh < 1) throw CorruptException("Bad natural size")
        val crop = optObject(o, "crop")?.let { c ->
            val x = (optNum(c, "x") ?: 0.0).coerceIn(0.0, nw - 1.0)
            val y = (optNum(c, "y") ?: 0.0).coerceIn(0.0, nh - 1.0)
            val cw = (optNum(c, "width") ?: (nw - x)).coerceIn(1.0, nw - x)
            val ch = (optNum(c, "height") ?: (nh - y)).coerceIn(1.0, nh - y)
            CropRect(x, y, cw, ch)
        } ?: CropRect(0.0, 0.0, nw.toDouble(), nh.toDouble())
        val adj = optObject(o, "adjust")?.let { a ->
            fun v(k: String) = (optNum(a, k) ?: 0.0).coerceIn(-100.0, 100.0)
            Adjust(v("brightness"), v("contrast"), v("saturation"), v("warmth"))
        } ?: Adjust()
        val border = optObject(o, "border")?.let { b ->
            Border(
                enabled = optBool(b, "enabled") ?: false,
                width = (optNum(b, "width") ?: 0.0).coerceAtLeast(0.0),
                color = optColor(b, "color") ?: WHITE,
            )
        } ?: Border()
        return ImageLayer(
            base = base, assetRef = assetRef, naturalWidth = nw, naturalHeight = nh, crop = crop,
            rotate90 = Math.floorMod(optInt(o, "rotate90") ?: 0, 4),
            flipH = optBool(o, "flipH") ?: false,
            flipV = optBool(o, "flipV") ?: false,
            adjust = adj,
            cornerRadius = (optNum(o, "cornerRadius") ?: 0.0).coerceAtLeast(0.0),
            border = border,
        )
    }

    fun readText(o: JsonObject, base: LayerBase): TextLayer = Spans.normalize(readTextRaw(o, base))

    private fun readTextRaw(o: JsonObject, base: LayerBase): TextLayer {
        val text = reqString(o, "text")
        val flags = StyleFlags(
            bold = optBool(o, "bold") ?: false,
            italic = optBool(o, "italic") ?: false,
            underline = optBool(o, "underline") ?: false,
            strike = optBool(o, "strike") ?: false,
        )
        val spans = (o["spans"].nonNull() as? JsonArray ?: if (o["spans"].nonNull() == null) JsonArray(emptyList()) else throw CorruptException("spans"))
            .mapNotNull { e ->
                // Spans are repaired, never corrupt: bad entries/fields are ignored (FORMAT.md §7.9).
                val so = e as? JsonObject ?: return@mapNotNull null
                val st = (so["start"] as? JsonPrimitive)?.let { intOrNull(it) } ?: return@mapNotNull null
                val en = (so["end"] as? JsonPrimitive)?.let { intOrNull(it) } ?: return@mapNotNull null
                fun b(k: String) = (so[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let { if (it == "true") true else if (it == "false") false else null }
                fun n(k: String) = (so[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
                fun str(k: String) = (so[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                Span(
                    st, en,
                    SpanStyle(
                        bold = b("bold"), italic = b("italic"), underline = b("underline"), strike = b("strike"),
                        color = str("color")?.let { Colors.parse(it) },
                        fontId = str("fontId"),
                        weight = n("weight")?.toInt()?.coerceIn(100, 900),
                        size = n("size")?.coerceIn(4.0, 2000.0),
                    ),
                )
            }
        val autoWidth = optBool(o, "autoWidth") ?: true
        var boxWidth = (optNum(o, "boxWidth") ?: 0.0).coerceAtLeast(0.0)
        if (!autoWidth && boxWidth < 1.0) boxWidth = 1.0
        return TextLayer(
            base = base,
            text = text,
            fontId = optString(o, "fontId") ?: "inter",
            fontSize = (optNum(o, "fontSize") ?: 64.0).coerceIn(4.0, 2000.0),
            weight = (optInt(o, "weight") ?: 400).coerceIn(100, 900),
            flags = flags,
            spans = spans,
            align = enumOr(optString(o, "align"), TextAlign.entries, { it.json }, TextAlign.CENTER),
            letterSpacing = (optNum(o, "letterSpacing") ?: 0.0).coerceIn(-0.5, 2.0),
            lineHeight = (optNum(o, "lineHeight") ?: 1.2).coerceIn(0.5, 4.0),
            textCase = enumOr(optString(o, "textCase"), TextCase.entries, { it.json }, TextCase.NONE),
            autoWidth = autoWidth,
            boxWidth = boxWidth,
            fill = optObject(o, "fill")?.let { readFill(it) } ?: Fill.Solid(BLACK),
            outline = optObject(o, "outline")?.let { readOutline(it) } ?: Outline(),
            shadow = optObject(o, "shadow")?.let { readShadow(it) } ?: Shadow(),
            backgroundBox = optObject(o, "backgroundBox")?.let { readBox(it) } ?: BackgroundBox(),
            curve = (optNum(o, "curve") ?: 0.0).coerceIn(-100.0, 100.0),
            skew = (optNum(o, "skew") ?: 0.0).coerceIn(-45.0, 45.0),
        )
    }

    fun readFill(o: JsonObject): Fill {
        fun stops(): List<GradientStop> {
            val arr = o["stops"].nonNull() as? JsonArray ?: return listOf(GradientStop(0.0, BLACK), GradientStop(1.0, WHITE))
            val s = arr.mapNotNull { e ->
                val so = e as? JsonObject ?: throw CorruptException("stop")
                GradientStop((optNum(so, "offset") ?: 0.0).coerceIn(0.0, 1.0), optColor(so, "color") ?: BLACK)
            }.sortedBy { it.offset }.take(3)
            return if (s.size >= 2) s else if (s.size == 1) listOf(s[0], s[0].copy(offset = 1.0)) else
                listOf(GradientStop(0.0, BLACK), GradientStop(1.0, WHITE))
        }
        return when (optString(o, "type")) {
            "linear" -> Fill.Linear(normAngle(optNum(o, "angle") ?: 0.0), stops())
            "radial" -> Fill.Radial(stops())
            else -> Fill.Solid(optColor(o, "color") ?: BLACK)
        }
    }

    fun readOutline(o: JsonObject) = Outline(
        enabled = optBool(o, "enabled") ?: false,
        style = enumOr(optString(o, "style"), OutlineStyle.entries, { it.json }, OutlineStyle.SOLID),
        color = optColor(o, "color") ?: BLACK,
        width = (optNum(o, "width") ?: 0.06).coerceIn(0.0, 0.5),
        color2 = optColor(o, "color2") ?: WHITE,
        width2 = (optNum(o, "width2") ?: 0.06).coerceIn(0.0, 0.5),
        glowRadius = (optNum(o, "glowRadius") ?: 0.3).coerceIn(0.0, 2.0),
        join = enumOr(optString(o, "join"), Join.entries, { it.json }, Join.ROUND),
    )

    fun readShadow(o: JsonObject) = Shadow(
        enabled = optBool(o, "enabled") ?: false,
        color = optColor(o, "color") ?: 0x80000000.toInt(),
        blur = (optNum(o, "blur") ?: 0.1).coerceIn(0.0, 2.0),
        offsetX = (optNum(o, "offsetX") ?: 0.05).coerceIn(-2.0, 2.0),
        offsetY = (optNum(o, "offsetY") ?: 0.05).coerceIn(-2.0, 2.0),
    )

    fun readBox(o: JsonObject) = BackgroundBox(
        enabled = optBool(o, "enabled") ?: false,
        color = optColor(o, "color") ?: 0x99000000.toInt(),
        padding = (optNum(o, "padding") ?: 0.25).coerceAtLeast(0.0),
        cornerRadius = (optNum(o, "cornerRadius") ?: 0.15).coerceAtLeast(0.0),
    )

    private fun readShape(o: JsonObject, base: LayerBase): ShapeLayer {
        val kindS = reqString(o, "shape")
        val kind = ShapeKind.entries.firstOrNull { it.json == kindS } ?: throw CorruptException("Unknown shape $kindS")
        val w = reqNum(o, "width").coerceAtLeast(1.0)
        val h = reqNum(o, "height").coerceAtLeast(1.0)
        val fill = optObject(o, "fill")?.let {
            ShapeFill(optBool(it, "enabled") ?: true, optColor(it, "color") ?: 0xFF3478F6.toInt())
        } ?: ShapeFill()
        val stroke = optObject(o, "stroke")?.let {
            ShapeStroke(
                enabled = optBool(it, "enabled") ?: false,
                color = optColor(it, "color") ?: BLACK,
                width = (optNum(it, "width") ?: 8.0).coerceIn(0.0, 500.0),
                join = enumOr(optString(it, "join"), Join.entries, { j -> j.json }, Join.MITER),
            )
        } ?: ShapeStroke()
        return ShapeLayer(
            base, kind, w, h, fill, stroke,
            cornerRadius = (optNum(o, "cornerRadius") ?: 24.0).coerceAtLeast(0.0),
            arrowHeads = enumOr(optString(o, "arrowHeads"), ArrowHeads.entries, { it.json }, ArrowHeads.END),
        )
    }

    sealed interface StrokesResult {
        data class Ok(val strokes: List<Stroke>) : StrokesResult
        data object Invalid : StrokesResult
    }

    /** Parses a strokes file. Any problem → Invalid (layer renders empty, §10.3). */
    fun readStrokes(text: String): StrokesResult = try {
        val o = reader.parseToJsonElement(text) as? JsonObject ?: throw CorruptException("not object")
        val arr = o["strokes"] as? JsonArray ?: throw CorruptException("strokes")
        StrokesResult.Ok(arr.map { e ->
            val so = e as? JsonObject ?: throw CorruptException("stroke")
            val brush = Brush.fromJson(reqString(so, "brush")) ?: throw CorruptException("brush")
            val pts = so["points"] as? JsonArray ?: throw CorruptException("points")
            if (pts.size < 2 || pts.size % 2 != 0) throw CorruptException("points length")
            val fa = FloatArray(pts.size) { i -> numOf(pts[i]).toFloat() }
            val pr = (so["pressure"].nonNull() as? JsonArray)?.let { p ->
                FloatArray(p.size) { i -> numOf(p[i]).toFloat().coerceIn(0f, 1f) }
            }?.takeIf { it.size == fa.size / 2 }
            Stroke(
                brush = brush,
                size = (optNum(so, "size") ?: 12.0).coerceIn(1.0, 200.0),
                color = optColor(so, "color") ?: BLACK,
                opacity = (optNum(so, "opacity") ?: 1.0).coerceIn(0.0, 1.0),
                points = FloatList(fa),
                pressure = pr?.let { FloatList(it) },
            )
        })
    } catch (e: Exception) {
        StrokesResult.Invalid
    }

    // ---------------------------------------------------------------- helpers

    private fun JsonElement?.nonNull(): JsonElement? = if (this == null || this is JsonNull) null else this

    private fun intOrNull(p: JsonPrimitive): Int? {
        if (p.isString) return null
        val d = p.content.toDoubleOrNull() ?: return null
        if (d != Math.floor(d) || d.isInfinite()) return null
        if (d > Int.MAX_VALUE || d < Int.MIN_VALUE) return null
        return d.toInt()
    }

    private fun numOf(e: JsonElement?): Double {
        val p = e as? JsonPrimitive ?: throw CorruptException("number expected")
        if (p.isString || p is JsonNull) throw CorruptException("number expected")
        return p.content.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw CorruptException("number expected")
    }

    private fun reqString(o: JsonObject, k: String): String {
        val p = o[k] as? JsonPrimitive ?: throw CorruptException("$k missing")
        if (!p.isString) throw CorruptException("$k not a string")
        return p.content
    }

    private fun optString(o: JsonObject, k: String): String? {
        val e = o[k].nonNull() ?: return null
        val p = e as? JsonPrimitive ?: throw CorruptException("$k wrong type")
        if (!p.isString) throw CorruptException("$k not a string")
        return p.content
    }

    private fun reqInt(o: JsonObject, k: String): Int {
        val p = o[k] as? JsonPrimitive ?: throw CorruptException("$k missing")
        if (p is JsonNull) throw CorruptException("$k missing")
        return intOrNull(p) ?: throw CorruptException("$k not an int")
    }

    private fun optInt(o: JsonObject, k: String): Int? {
        val e = o[k].nonNull() ?: return null
        val p = e as? JsonPrimitive ?: throw CorruptException("$k wrong type")
        return intOrNull(p) ?: throw CorruptException("$k not an int")
    }

    private fun reqNum(o: JsonObject, k: String): Double {
        if (o[k].nonNull() == null) throw CorruptException("$k missing")
        return numOf(o[k])
    }

    private fun optNum(o: JsonObject, k: String): Double? = o[k].nonNull()?.let { numOf(it) }

    private fun optBool(o: JsonObject, k: String): Boolean? {
        val e = o[k].nonNull() ?: return null
        val p = e as? JsonPrimitive ?: throw CorruptException("$k wrong type")
        if (p.isString) throw CorruptException("$k not a bool")
        return when (p.content) {
            "true" -> true; "false" -> false
            else -> throw CorruptException("$k not a bool")
        }
    }

    private fun reqObject(o: JsonObject, k: String): JsonObject =
        o[k] as? JsonObject ?: throw CorruptException("$k missing or not an object")

    private fun optObject(o: JsonObject, k: String): JsonObject? {
        val e = o[k].nonNull() ?: return null
        return e as? JsonObject ?: throw CorruptException("$k not an object")
    }

    /** Invalid color strings fall back to the field default (only a non-string is a type error). */
    private fun optColor(o: JsonObject, k: String): Int? = optString(o, k)?.let { Colors.parse(it) }

    private fun <E> enumOr(s: String?, all: List<E>, key: (E) -> String, def: E): E =
        if (s == null) def else all.firstOrNull { key(it) == s } ?: def

    fun normAngle(a: Double): Double {
        var r = a % 360.0
        if (r < 0) r += 360.0
        if (r >= 360.0) r = 0.0
        return r
    }

    // ================================================================ writing

    private fun num(v: Double, places: Int = 4): JsonPrimitive {
        val f = if (places == 4) 10000.0 else 100.0
        var r = round(v * f) / f
        if (r == 0.0) r = 0.0 // drop negative zero
        return if (abs(r) < 1e15 && r == Math.floor(r)) JsonPrimitive(r.toLong()) else JsonPrimitive(r)
    }

    private fun color(c: Int) = JsonPrimitive(Colors.format(c))

    fun write(doc: Document, generator: String): String = writer.encodeToString(JsonElement.serializer(), toJson(doc, generator))

    fun toJson(doc: Document, generator: String): JsonObject = buildJsonObject {
        put("formatVersion", SUPPORTED)
        put("id", doc.id)
        put("name", doc.name)
        put("created", doc.created)
        put("modified", doc.modified)
        put("generator", generator)
        put("canvas", buildJsonObject {
            put("width", doc.canvas.width)
            put("height", doc.canvas.height)
            put("background", color(doc.canvas.background))
        })
        put("layers", buildJsonArray { doc.layers.forEach { add(layerJson(it)) } })
    }

    fun layerJson(l: Layer): JsonObject = buildJsonObject {
        val b = l.base
        put("id", b.id)
        put("type", l.typeName)
        put("name", b.name)
        put("visible", b.visible)
        put("locked", b.locked)
        put("opacity", num(b.opacity))
        put("blendMode", "normal")
        put("transform", buildJsonObject {
            put("x", num(b.transform.x))
            put("y", num(b.transform.y))
            put("scale", num(b.transform.scale))
            put("rotation", num(normAngle(b.transform.rotation)))
        })
        when (l) {
            is ImageLayer -> {
                put("assetRef", l.assetRef)
                put("naturalWidth", l.naturalWidth)
                put("naturalHeight", l.naturalHeight)
                put("crop", buildJsonObject {
                    put("x", num(l.crop.x)); put("y", num(l.crop.y))
                    put("width", num(l.crop.width)); put("height", num(l.crop.height))
                })
                put("rotate90", l.rotate90)
                put("flipH", l.flipH)
                put("flipV", l.flipV)
                put("adjust", buildJsonObject {
                    put("brightness", num(l.adjust.brightness)); put("contrast", num(l.adjust.contrast))
                    put("saturation", num(l.adjust.saturation)); put("warmth", num(l.adjust.warmth))
                })
                put("cornerRadius", num(l.cornerRadius))
                put("border", buildJsonObject {
                    put("enabled", l.border.enabled); put("width", num(l.border.width)); put("color", color(l.border.color))
                })
            }
            is TextLayer -> {
                put("text", l.text)
                put("fontId", l.fontId)
                put("weight", l.weight)
                put("fontSize", num(l.fontSize))
                put("bold", l.flags.bold)
                put("italic", l.flags.italic)
                put("underline", l.flags.underline)
                put("strike", l.flags.strike)
                put("align", l.align.json)
                put("letterSpacing", num(l.letterSpacing))
                put("lineHeight", num(l.lineHeight))
                put("textCase", l.textCase.json)
                put("autoWidth", l.autoWidth)
                put("boxWidth", num(l.boxWidth))
                put("fill", fillJson(l.fill))
                put("outline", outlineJson(l.outline))
                put("shadow", shadowJson(l.shadow))
                put("backgroundBox", boxJson(l.backgroundBox))
                put("curve", num(l.curve))
                put("skew", num(l.skew))
                put("spans", buildJsonArray {
                    Spans.normalize(l.text, l.layerStyle(), l.spans).forEach { s ->
                        val st = s.style
                        add(buildJsonObject {
                            put("start", s.start); put("end", s.end)
                            st.bold?.let { put("bold", it) }; st.italic?.let { put("italic", it) }
                            st.underline?.let { put("underline", it) }; st.strike?.let { put("strike", it) }
                            st.color?.let { put("color", color(it)) }
                            st.fontId?.let { put("fontId", it) }
                            st.weight?.let { put("weight", it) }
                            st.size?.let { put("size", num(it)) }
                        })
                    }
                })
            }
            is ShapeLayer -> {
                put("shape", l.shape.json)
                put("width", num(l.width))
                put("height", num(if (l.shape.isLinear) maxOf(l.stroke.width, 1.0) else l.height))
                put("fill", buildJsonObject { put("enabled", l.fill.enabled); put("color", color(l.fill.color)) })
                put("stroke", buildJsonObject {
                    put("enabled", l.stroke.enabled); put("color", color(l.stroke.color))
                    put("width", num(l.stroke.width)); put("join", l.stroke.join.json)
                })
                put("cornerRadius", num(l.cornerRadius))
                put("arrowHeads", l.arrowHeads.json)
            }
            is DrawingLayer -> {
                put("width", l.width)
                put("height", l.height)
            }
        }
    }

    fun fillJson(f: Fill): JsonObject = buildJsonObject {
        when (f) {
            is Fill.Solid -> { put("type", "solid"); put("color", color(f.color)) }
            is Fill.Linear -> { put("type", "linear"); put("angle", num(normAngle(f.angle))); put("stops", stopsJson(f.stops)) }
            is Fill.Radial -> { put("type", "radial"); put("stops", stopsJson(f.stops)) }
        }
    }

    private fun stopsJson(s: List<GradientStop>) = buildJsonArray {
        s.sortedBy { it.offset }.forEach { add(buildJsonObject { put("offset", num(it.offset)); put("color", color(it.color)) }) }
    }

    fun outlineJson(o: Outline) = buildJsonObject {
        put("enabled", o.enabled); put("style", o.style.json); put("color", color(o.color))
        put("width", num(o.width)); put("color2", color(o.color2)); put("width2", num(o.width2))
        put("glowRadius", num(o.glowRadius)); put("join", o.join.json)
    }

    fun shadowJson(s: Shadow) = buildJsonObject {
        put("enabled", s.enabled); put("color", color(s.color)); put("blur", num(s.blur))
        put("offsetX", num(s.offsetX)); put("offsetY", num(s.offsetY))
    }

    fun boxJson(b: BackgroundBox) = buildJsonObject {
        put("enabled", b.enabled); put("color", color(b.color)); put("padding", num(b.padding))
        put("cornerRadius", num(b.cornerRadius))
    }

    fun writeStrokes(layer: DrawingLayer): String = writeStrokeFile(layer.id, layer.strokes)

    /** `strokes/<id>.mask.json` (FORMAT.md §9.1): every stroke is an eraser. */
    fun writeMask(layer: Layer): String = writeStrokeFile(layer.id, layer.base.mask.map { it.copy(brush = Brush.ERASER) })

    /** Mask file: valid only if every stroke is an eraser. */
    fun readMask(text: String): StrokesResult = when (val r = readStrokes(text)) {
        is StrokesResult.Ok -> if (r.strokes.all { it.brush == Brush.ERASER }) r else StrokesResult.Invalid
        StrokesResult.Invalid -> r
    }

    private fun writeStrokeFile(layerId: String, strokes: List<Stroke>): String = reader.encodeToString(JsonElement.serializer(), buildJsonObject {
        put("formatVersion", SUPPORTED)
        put("layerId", layerId)
        put("strokes", buildJsonArray {
            strokes.forEach { s ->
                add(buildJsonObject {
                    put("brush", s.brush.json)
                    put("size", num(s.size))
                    put("color", color(s.color))
                    put("opacity", num(s.opacity))
                    put("points", buildJsonArray { for (i in 0 until s.points.size) add(num(s.points[i].toDouble(), 2)) })
                    s.pressure?.let { p -> put("pressure", buildJsonArray { for (i in 0 until p.size) add(num(p[i].toDouble())) }) }
                })
            }
        })
    })
}

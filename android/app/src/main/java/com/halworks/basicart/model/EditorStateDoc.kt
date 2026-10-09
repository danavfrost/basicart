package com.halworks.basicart.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `editor-state.json` (FORMAT.md §10.7): where the user left off. Every field optional;
 * invalid values are dropped individually; never an error.
 */
data class EditorStateDoc(
    val selectedLayerIds: List<String> = emptyList(),
    val activeTool: String? = null,
    val textTab: String? = null,
    /** Zoom as a multiple of fit-to-screen (1 = fit), 0.1–32. */
    val zoom: Double? = null,
    val centerX: Double? = null,
    val centerY: Double? = null,
    val textSelection: TextSel? = null,
) {
    data class TextSel(val layerId: String, val start: Int, val end: Int)

    companion object {
        val TOOLS = listOf("select", "text", "image", "draw", "shapes", "adjust", "canvas")
        val TABS = listOf("font", "style", "color", "outline", "shadow", "effects")

        /** Parses leniently; anything invalid is ignored on its own. */
        fun parse(text: String?): EditorStateDoc {
            if (text == null) return EditorStateDoc()
            val o = try { Json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null } ?: return EditorStateDoc()
            fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun num(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
            val ids = (o["selectedLayerIds"] as? JsonArray)?.mapNotNull { str(it) }?.filter { Regex("^[A-Za-z0-9_-]{1,64}$").matches(it) } ?: emptyList()
            val tool = str(o["activeTool"])?.takeIf { it in TOOLS }
            val tab = str(o["textTab"])?.takeIf { it in TABS }
            val v = o["view"] as? JsonObject
            val zoom = num(v?.get("zoom"))?.takeIf { it in 0.1..32.0 }
            val cx = num(v?.get("centerX")); val cy = num(v?.get("centerY"))
            val ts = (o["textSelection"] as? JsonObject)?.let { s ->
                val id = str(s["layerId"]) ?: return@let null
                val a = num(s["start"])?.takeIf { it == Math.floor(it) }?.toInt() ?: return@let null
                val b = num(s["end"])?.takeIf { it == Math.floor(it) }?.toInt() ?: return@let null
                if (a < 0 || b < a) null else TextSel(id, a, b)
            }
            return EditorStateDoc(ids, tool, tab, zoom, if (zoom != null) cx else null, if (zoom != null) cy else null, ts)
        }
    }

    fun write(): String = Json.encodeToString(JsonElement.serializer(), buildJsonObject {
        put("selectedLayerIds", buildJsonArray { selectedLayerIds.forEach { add(JsonPrimitive(it)) } })
        activeTool?.let { put("activeTool", it) }
        textTab?.let { put("textTab", it) }
        if (zoom != null && centerX != null && centerY != null) put("view", buildJsonObject {
            put("zoom", Math.round(zoom * 10000) / 10000.0); put("centerX", Math.round(centerX * 100) / 100.0); put("centerY", Math.round(centerY * 100) / 100.0)
        })
        textSelection?.let { s -> put("textSelection", buildJsonObject { put("layerId", s.layerId); put("start", s.start); put("end", s.end) }) }
    })

    /** Applies document-dependent validity: unknown layers dropped, text selection checked. */
    fun validFor(doc: Document): EditorStateDoc {
        val ids = selectedLayerIds.filter { doc.layer(it) != null }
        val ts = textSelection?.takeIf { s ->
            val t = doc.layer(s.layerId) as? TextLayer ?: return@takeIf false
            val b = Graphemes.boundaries(t.text)
            s.end <= t.text.length && s.start in b && s.end in b
        }
        return copy(selectedLayerIds = ids, textSelection = ts)
    }
}

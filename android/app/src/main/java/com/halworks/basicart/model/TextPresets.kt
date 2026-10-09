package com.halworks.basicart.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class TextPreset(val id: String, val name: String, val props: JsonObject)

/** shared/presets/text-presets.json (FORMAT.md §12). */
object TextPresets {
    fun parse(text: String): List<TextPreset> =
        Json.parseToJsonElement(text).jsonObject["presets"]!!.jsonArray.map {
            val o = it.jsonObject
            TextPreset(
                o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content,
                o["props"]!!.jsonObject,
            )
        }

    /**
     * Reset every style field to its default, then apply [preset]'s props (objects replace
     * whole). text, fontSize, autoWidth, boxWidth, spans and common fields are kept.
     */
    fun apply(layer: TextLayer, preset: TextPreset): TextLayer {
        val reset = layer.withDefaultStyle()
        val json = ProjectCodec.layerJson(reset)
        val keep = setOf("text", "fontSize", "autoWidth", "boxWidth", "spans", "id", "type", "name",
            "visible", "locked", "opacity", "blendMode", "transform")
        val merged = JsonObject(json + preset.props.filterKeys { it !in keep })
        val out = ProjectCodec.readText(merged, layer.base)
        // Presets clear span color/font/weight; span B/I/U/S and size are kept (FORMAT.md §12).
        val kept = layer.spans.map { it.copy(style = it.style.copy(color = null, fontId = null, weight = null)) }
        return Spans.normalize(out.copy(spans = kept))
    }
}

data class Palette(val id: String, val name: String, val colors: List<Int>)

object Palettes {
    fun parse(text: String): List<Palette> =
        Json.parseToJsonElement(text).jsonObject["palettes"]!!.jsonArray.map {
            val o = it.jsonObject
            Palette(
                o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content,
                o["colors"]!!.jsonArray.mapNotNull { c -> Colors.parse(c.jsonPrimitive.content) },
            )
        }
}

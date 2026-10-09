package com.halworks.basicart

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.halworks.basicart.data.AppContainer
import com.halworks.basicart.model.LayerBase
import com.halworks.basicart.model.ProjectCodec
import com.halworks.basicart.model.Transform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Layout parity entry point (FORMAT.md §7.10): lays out every case in
 * shared/fixtures/layout/cases.json with the real engine (real font shaping, on device) and
 * writes layout-results-android.json to the app's external files dir.
 */
@RunWith(AndroidJUnit4::class)
class LayoutParityTest {
    @Test
    fun layoutCases() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val app = AppContainer.get(ctx)
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val root = Json.parseToJsonElement(testAssets.open("layout/cases.json").use { it.readBytes().decodeToString() }).jsonObject
        val renderer = app.rendererFor("layout-parity")
        val results = buildJsonArray {
            for (c in root["cases"]!!.jsonArray) {
                val co = c.jsonObject
                val id = co["id"]!!.jsonPrimitive.content
                val layer = ProjectCodec.readText(co["layer"]!!.jsonObject, LayerBase("t", "t", transform = Transform(0.0, 0.0)))
                renderer.canvasWidth = co["canvasWidth"]!!.jsonPrimitive.content.toFloat()
                val r = renderer.layout(layer)
                add(buildJsonObject {
                    put("id", id); put("w", r.w.toDouble()); put("h", r.h.toDouble())
                    put("lines", buildJsonArray {
                        var nextStart = 0
                        for (l in r.lines) {
                            val gl = r.glyphs.filter { it.line == l.index && !it.isNewline }
                            val start = gl.minOfOrNull { it.srcStart } ?: nextStart
                            val end = gl.maxOfOrNull { it.srcEnd } ?: start
                            r.glyphs.firstOrNull { it.line == l.index && it.isNewline }?.let { nextStart = it.srcEnd } ?: run { nextStart = end }
                            add(buildJsonObject {
                                put("start", start); put("end", end)
                                put("x", l.x.toDouble()); put("width", l.width.toDouble())
                                put("top", l.top.toDouble()); put("height", l.height.toDouble()); put("baseline", l.baseline.toDouble())
                            })
                        }
                    })
                })
            }
        }
        val out = buildJsonObject {
            put("platform", "android"); put("appVersion", BuildConfig.VERSION_NAME)
            put("casesVersion", root["casesVersion"]!!.jsonPrimitive.int)
            put("cases", results)
        }
        val f = File(ctx.getExternalFilesDir(null), "layout-results-android.json")
        f.writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), out))
        assertTrue(f.length() > 0)
    }
}

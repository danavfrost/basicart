package com.halworks.basicart.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FixturesTest {
    private val shared = File(System.getProperty("basicart.shared") ?: "../../shared")
    private val fixtures = File(shared, "fixtures")
    private val expectations = Json.parseToJsonElement(File(fixtures, "expectations.json").readText())
        .jsonObject["fixtures"]!!.jsonObject

    private fun tempStoreWith(name: String): Pair<ProjectStore, File> {
        val root = Files.createTempDirectory("ba-fx").toFile()
        val d = File(root, name)
        File(fixtures, name).copyRecursively(d)
        return ProjectStore(root) to d
    }

    private fun snapshot(d: File): Map<String, String> =
        d.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(d).path to ProjectStore.sha256(it.readBytes())
        }

    @Test
    fun everyFixtureIsListedAndClassified() {
        val folders = fixtures.listFiles { f -> f.isDirectory && f.name != "packages" && f.name != "layout" }!!.map { it.name }.sorted()
        assertEquals(folders, expectations.keys.sorted())
        for ((name, expEl) in expectations) {
            val exp = expEl.jsonObject
            val (store, dir) = tempStoreWith(name)
            val before = snapshot(dir)
            val result = store.load(name)
            val entry = store.classify(dir)
            when (exp["result"]!!.jsonPrimitive.content) {
                "ok" -> {
                    assertTrue("$name should load: $result", result is LoadResult.Ok)
                    assertTrue(entry is ProjectEntry.Ok)
                    checkOk(name, exp, (result as LoadResult.Ok).doc, dir)
                }
                "newerVersion" -> {
                    assertTrue("$name: $result", result is LoadResult.NewerVersion)
                    assertTrue(entry is ProjectEntry.Newer)
                    assertEquals("$name folder must be untouched", before, snapshot(dir))
                }
                "corrupt" -> {
                    assertTrue("$name: $result", result is LoadResult.Corrupt)
                    assertTrue(entry is ProjectEntry.Corrupt)
                    assertEquals("$name folder must be untouched", before, snapshot(dir))
                }
                else -> fail("unknown result")
            }
        }
    }

    private fun checkOk(name: String, exp: JsonObject, doc: Document, dir: File) {
        val c = exp["canvas"]!!.jsonArray
        assertEquals(name, c[0].jsonPrimitive.int, doc.canvas.width)
        assertEquals(name, c[1].jsonPrimitive.int, doc.canvas.height)
        assertEquals(name, c[2].jsonPrimitive.content, Colors.format(doc.canvas.background))
        val layers = exp["layers"]!!.jsonArray
        assertEquals(name, layers.size, doc.layers.size)
        layers.forEachIndexed { i, le ->
            val l = le.jsonObject
            val actual = doc.layers[i]
            assertEquals(name, l["id"]!!.jsonPrimitive.content, actual.id)
            assertEquals(name, l["type"]!!.jsonPrimitive.content, actual.typeName)
            assertEquals(name, l["visible"]!!.jsonPrimitive.boolean, actual.visible)
            assertEquals(name, l["locked"]!!.jsonPrimitive.boolean, actual.locked)
            l["box"]?.jsonArray?.let { box ->
                val (w, h) = actual.fixedBox()!!
                assertEquals("$name ${actual.id} w", box[0].jsonPrimitive.content.toDouble(), w, 1e-9)
                assertEquals("$name ${actual.id} h", box[1].jsonPrimitive.content.toDouble(), h, 1e-9)
            }
        }
        (exp["spans"] as? JsonObject)?.forEach { (id, ranges) ->
            val t = doc.layer(id) as TextLayer
            val got = t.spans.map { listOf(it.start, it.end) }
            val want = (ranges as JsonArray).map { r -> r.jsonArray.map { it.jsonPrimitive.int } }
            assertEquals("$name $id spans", want, got)
        }
        (exp["strokeCounts"] as? JsonObject)?.forEach { (id, n) ->
            assertEquals("$name $id strokes", n.jsonPrimitive.int, (doc.layer(id) as DrawingLayer).strokes.size)
        }
        (exp["maskStrokeCounts"] as? JsonObject)?.forEach { (id, n) ->
            assertEquals("$name $id mask strokes", n.jsonPrimitive.int, doc.layer(id)!!.base.mask.size)
        }
        (exp["missingAssets"] as? JsonArray)?.forEach { idEl ->
            val l = doc.layer(idEl.jsonPrimitive.content) as ImageLayer
            assertFalse(File(dir, "assets/${l.assetRef}").exists())
        }
        roundTrip(name, doc)
    }

    private fun roundTrip(name: String, doc: Document) {
        val root = Files.createTempDirectory("ba-rt").toFile()
        val store = ProjectStore(root)
        val saved = store.save(doc)
        val again = (store.load(doc.id) as LoadResult.Ok).doc
        fun norm(d: Document) = d.copy(
            modified = "", generator = "",
            layers = d.layers.map {
                val l = if (it is DrawingLayer) it.copy(strokesState = StrokesState.OK) else it
                l.withBase(l.base.copy(maskState = StrokesState.OK))
            },
        )
        assertEquals("$name round-trip", norm(saved), norm(again))
        assertEquals("$name round-trip vs original", norm(doc), norm(again))
    }

    @Test
    fun unknownFontIdIsKept() {
        val (store, _) = tempStoreWith("02-text-effects")
        val doc = (store.load("02-text-effects") as LoadResult.Ok).doc
        val root = Files.createTempDirectory("ba-font").toFile()
        val s2 = ProjectStore(root)
        s2.save(doc)
        val text = File(root, "02-text-effects/project.json").readText()
        assertTrue(text.contains("\"fontId\": \"no-such-font\""))
    }

    @Test
    fun folderNameWinsOverStoredId() {
        val root = Files.createTempDirectory("ba-id").toFile()
        File(fixtures, "03-rich-text-spans").copyRecursively(File(root, "renamed-folder"))
        val doc = (ProjectStore(root).load("renamed-folder") as LoadResult.Ok).doc
        assertEquals("renamed-folder", doc.id)
    }

    @Test
    fun leftoverTmpFilesAreDeletedOnOpen() {
        val (store, dir) = tempStoreWith("01-all-layer-types")
        File(dir, "project.json.tmp").writeText("junk")
        File(dir, "strokes/draw-1.json.tmp").writeText("junk")
        assertTrue(store.load("01-all-layer-types") is LoadResult.Ok)
        assertFalse(File(dir, "project.json.tmp").exists())
        assertFalse(File(dir, "strokes/draw-1.json.tmp").exists())
    }
}

package com.halworks.basicart.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PackageTest {
    private val shared = File(System.getProperty("basicart.shared") ?: "../../shared")
    private val packages = File(shared, "fixtures/packages")
    private val expect = Json.parseToJsonElement(File(shared, "fixtures/expectations.json").readText()).jsonObject["packages"]!!.jsonObject

    private fun snapshot(d: File) = d.walkTopDown().filter { it.isFile }.associate { it.relativeTo(d).path to ProjectStore.sha256(it.readBytes()) }

    @Test fun sharedPackagesBehaveAsExpected() {
        for ((name, e) in expect) {
            val exp = e.jsonObject
            val root = Files.createTempDirectory("ba-pkg").toFile()
            // an existing project with the same name to exercise "(2)"
            val existing = ProjectStore(root)
            existing.save(Document("keep", "Mixed styles", "", "", "", CanvasSpec(100, 100), emptyList()))
            val before = snapshot(File(root, "keep"))
            val zip = File(root.parentFile, "in-$name").also { File(packages, name).copyTo(it, true) }
            val r = ProjectPackage.import(zip, root, existing.names(), "2026-10-09T12:00:00Z")
            assertTrue("$name leaves no temp folder", root.listFiles()!!.none { it.name.startsWith(".import-") })
            assertEquals("$name keeps existing projects", before, snapshot(File(root, "keep")))
            when (exp["result"]!!.jsonPrimitive.content) {
                "ok" -> {
                    assertTrue("$name: $r", r is ImportResult.Ok)
                    val ok = r as ImportResult.Ok
                    assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(ok.projectId))
                    val doc = (ProjectStore(root).load(ok.projectId) as LoadResult.Ok).doc
                    val src = (ProjectStore(File(shared, "fixtures")).loadReadOnly(exp["source"]!!.jsonPrimitive.content) as LoadResult.Ok).doc
                    assertEquals(name, src.layers.map { it.id }, doc.layers.map { it.id })
                    assertEquals(name, src.canvas, doc.canvas)
                    if (name.contains("12")) assertEquals("Mixed styles (2)", ok.name)
                    val state = ProjectStore(root).readEditorState(ok.projectId)
                    assertEquals(exp["editorState"]!!.jsonPrimitive.boolean, state.activeTool != null)
                    if (exp["editorState"]!!.jsonPrimitive.boolean) {
                        assertEquals(listOf("t-styled"), state.selectedLayerIds)
                        assertEquals("text", state.activeTool); assertEquals("color", state.textTab)
                        assertEquals(1.5, state.zoom!!, 1e-9)
                        assertEquals(EditorStateDoc.TextSel("t-styled", 4, 8), state.validFor(doc).textSelection)
                    }
                    // skipped junk is not extracted
                    assertFalse(File(root, "${ok.projectId}/__MACOSX").exists())
                    assertFalse(File(root, "${ok.projectId}/.DS_Store").exists())
                }
                else -> {
                    assertTrue("$name: $r", r is ImportResult.Rejected)
                    val reasons = (exp["reasons"] as JsonArray).map { it.jsonPrimitive.content }
                    assertTrue("$name: ${(r as ImportResult.Rejected).reason.code} in $reasons", r.reason.code in reasons)
                    assertEquals("$name adds no project", 1, ProjectStore(root).list().size)
                }
            }
        }
    }

    @Test fun exportImportRoundTrip() {
        val root = Files.createTempDirectory("ba-rt").toFile()
        val store = ProjectStore(root)
        File(shared, "fixtures/11-eraser-masks").copyRecursively(File(root, "src"))
        val doc = (store.load("src") as LoadResult.Ok).doc
        store.markStrokesSaved(doc); store.save(doc)
        // a stray file of a removed layer and a tmp file must not be packed
        File(root, "src/strokes/gone.json").writeText("{}")
        File(root, "src/project.json.tmp").writeText("x")
        val state = EditorStateDoc(listOf("t-masked"), "text", "effects", 2.0, 300.0, 200.0, EditorStateDoc.TextSel("t-masked", 0, 2))
        store.writeEditorState("src", state.write())
        val zip = File(root.parentFile, "rt-${System.nanoTime()}.zip")
        zip.outputStream().use { ProjectPackage.export(File(root, "src"), it, "android", "1.0.0") }
        val names = ProjectPackage.centralDirectory(zip).map { it.name }
        assertEquals(ProjectPackage.MANIFEST, names.first())
        assertFalse(names.any { it.contains("gone") || it.endsWith(".tmp") })
        val r = ProjectPackage.import(zip, root, store.names()) as ImportResult.Ok
        val a = Json.parseToJsonElement(File(root, "src/project.json").readText()).jsonObject
        val b = Json.parseToJsonElement(File(root, "${r.projectId}/project.json").readText()).jsonObject
        assertEquals(JsonObject(a - setOf("id", "modified", "name")), JsonObject(b - setOf("id", "modified", "name")))
        for (n in names.filter { it != ProjectPackage.MANIFEST && it != "project.json" && !it.endsWith("/") }) {
            assertTrue(n, File(root, "src/$n").readBytes().contentEquals(File(root, "${r.projectId}/$n").readBytes()))
        }
        assertEquals(state, store.readEditorState(r.projectId))
        assertTrue(r.name.endsWith("(2)"))
    }

    @Test fun badAssetHashIsDroppedNotFatal() {
        val root = Files.createTempDirectory("ba-hash").toFile()
        val zip = File(root.parentFile, "hash-${System.nanoTime()}.zip")
        val pj = File(shared, "fixtures/01-all-layer-types/project.json").readText()
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() }
            put(ProjectPackage.MANIFEST, """{"package":"basicart-project","packageVersion":1,"formatVersion":1,"appPlatform":"android","appVersion":"1","exported":"2026-10-09T00:00:00Z"}""".toByteArray())
            put("project.json", pj.toByteArray())
            put("assets/6fe4aad40edba5d4736cec2dc86508a6910027e4004bc31ce0d2f80eabc79ada.png", byteArrayOf(1, 2, 3))
        }
        val r = ProjectPackage.import(zip, root, emptySet()) as ImportResult.Ok
        assertFalse(File(root, "${r.projectId}/assets/6fe4aad40edba5d4736cec2dc86508a6910027e4004bc31ce0d2f80eabc79ada.png").exists())
        assertTrue(ProjectStore(root).load(r.projectId) is LoadResult.Ok)
    }

    @Test fun editorStateParsingIsLenient() {
        val s = EditorStateDoc.parse("""{"selectedLayerIds":["a",5],"activeTool":"laser","textTab":"color","view":{"zoom":99,"centerX":1,"centerY":2},"textSelection":{"layerId":"a","start":3,"end":1}}""")
        assertEquals(EditorStateDoc(listOf("a"), null, "color"), s)
        assertEquals(EditorStateDoc(), EditorStateDoc.parse("{nope"))
        assertEquals("package.zip", ProjectPackage.fileName("package"))
        assertEquals("a_b_c.zip", ProjectPackage.fileName("a/b:c"))
        assertEquals("Basic Art project.zip", ProjectPackage.fileName("  "))
    }

    /** -Pxplat: write pkg-12.zip from fixture 12 and import iOS's pkg-12.zip if present. */
    @Test fun crossPlatformPackages() {
        val x = System.getProperty("basicart.xplat")?.let { File(it) }
        assumeTrue(x != null)
        val outDir = File(x, "android-out").apply { mkdirs() }
        val root = Files.createTempDirectory("ba-x12").toFile()
        File(shared, "fixtures/12-mixed-styles").copyRecursively(File(root, "p12"))
        File(root, "p12/editor-state.json").writeText(
            EditorStateDoc(listOf("t-styled"), "text", "color", 1.5, 540.0, 420.0, EditorStateDoc.TextSel("t-styled", 4, 8)).write())
        File(outDir, "pkg-12.zip").outputStream().use { ProjectPackage.export(File(root, "p12"), it, "android", "1.0.0") }
        val back = ProjectPackage.import(File(outDir, "pkg-12.zip").copyTo(File(root.parentFile, "self-${System.nanoTime()}.zip")), root, emptySet())
        assertTrue("own package re-imports: $back", back is ImportResult.Ok)
        val ios = File(x, "ios-out/pkg-12.zip")
        if (ios.isFile) {
            val r = ProjectPackage.import(ios.copyTo(File(root.parentFile, "ios-${System.nanoTime()}.zip")), root, emptySet())
            File(x, "android-read-ios-pkg.txt").writeText("ios-out/pkg-12.zip → $r\n")
            assertTrue("iOS package imports: $r", r is ImportResult.Ok)
            val doc = (ProjectStore(root).load((r as ImportResult.Ok).projectId) as LoadResult.Ok).doc
            assertEquals(listOf("t-styled", "t-solid"), doc.layers.map { it.id })
        }
    }
}

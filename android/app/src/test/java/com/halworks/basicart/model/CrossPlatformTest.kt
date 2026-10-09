package com.halworks.basicart.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Cross-platform exchange (run with -Pxplat=<dir>):
 * writes every loadable fixture with this app's writer to <dir>/android-out/<fixture>/ and
 * loads whatever the iOS app wrote to <dir>/ios-out/.
 */
class CrossPlatformTest {
    private val xplat = System.getProperty("basicart.xplat")?.let { File(it) }
    private val shared = File(System.getProperty("basicart.shared") ?: "../../shared")

    @Test fun writeAndroidOut() {
        assumeTrue(xplat != null)
        val out = File(xplat, "android-out").apply { deleteRecursively(); mkdirs() }
        val fixtures = File(shared, "fixtures").listFiles { f -> f.isDirectory }!!.sortedBy { it.name }
        val tmp = java.nio.file.Files.createTempDirectory("ba-x").toFile()
        var written = 0
        for (f in fixtures) {
            f.copyRecursively(File(tmp, f.name))
            val r = ProjectStore(tmp).load(f.name) as? LoadResult.Ok ?: continue
            val store = ProjectStore(out)
            // assets are copied as-is (write-once originals); everything else comes from our writer
            File(f, "assets").takeIf { it.exists() }?.copyRecursively(File(out, "${f.name}/assets"))
            store.save(r.doc)
            val again = ProjectStore(out).load(f.name)
            assertTrue("${f.name} re-reads", again is LoadResult.Ok)
            written++
        }
        File(out, "MANIFEST.txt").writeText("Written by Basic Art Android ProjectStore from shared/fixtures ($written projects).\n")
        val expectedOk = kotlinx.serialization.json.Json.parseToJsonElement(File(shared, "fixtures/expectations.json").readText())
            .let { (it as kotlinx.serialization.json.JsonObject)["fixtures"] as kotlinx.serialization.json.JsonObject }
            .values.count { (it as kotlinx.serialization.json.JsonObject)["result"].toString() == "\"ok\"" }
        assertEquals(expectedOk, written)
    }

    @Test fun readIosOut() {
        assumeTrue(xplat != null)
        val inDir = File(xplat, "ios-out")
        assumeTrue(inDir.isDirectory)
        val report = StringBuilder()
        val store = ProjectStore(inDir)
        val dirs = inDir.listFiles { f -> f.isDirectory }!!.sortedBy { it.name }
        for (d in dirs) {
            val r = store.loadReadOnly(d.name)
            val line = when (r) {
                is LoadResult.Ok -> "OK      ${d.name}: ${r.doc.layers.size} layers, canvas ${r.doc.canvas.width}x${r.doc.canvas.height}"
                is LoadResult.NewerVersion -> "NEWER   ${d.name}"
                is LoadResult.Corrupt -> "CORRUPT ${d.name}: ${r.reason}"
            }
            report.append(line).append('\n')
        }
        File(xplat, "android-read-ios-report.txt").writeText(report.toString())
        println(report)
        val expect = File(shared, "fixtures/expectations.json").readText()
        for (d in dirs) {
            val ok = store.loadReadOnly(d.name)
            if (expect.contains("\"${d.name}\": {\n      \"result\": \"ok\"")) assertTrue("${d.name}: $ok", ok is LoadResult.Ok)
        }
        assertEquals(dirs.size, report.lines().count { it.isNotBlank() })
    }
}

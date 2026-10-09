package com.halworks.basicart

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.halworks.basicart.data.AppContainer
import com.halworks.basicart.model.LoadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders fixtures 01–06 at 100% to PNG (golden images for cross-platform review).
 * Fixtures must be installed in files/projects (the test script copies them).
 */
@RunWith(AndroidJUnit4::class)
class RenderFixturesTest {
    @Test
    fun renderFixtures() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val app = AppContainer.get(ctx)
        val out = File(ctx.getExternalFilesDir(null), "golden").apply { mkdirs() }
        val ids = listOf("01-all-layer-types", "02-text-effects", "03-rich-text-spans", "04-flags-shapes-image", "05-drawing-brushes", "06-missing-asset", "11-eraser-masks", "12-mixed-styles")
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        fun copy(path: String, dest: File) {
            val kids = testAssets.list(path) ?: emptyArray()
            if (kids.isEmpty()) {
                dest.parentFile?.mkdirs()
                testAssets.open(path).use { i -> dest.outputStream().use { i.copyTo(it) } }
            } else kids.forEach { copy("$path/$it", File(dest, it)) }
        }
        for (id in ids) {
            app.store.dir(id).deleteRecursively()
            copy(id, app.store.dir(id))
            val r = app.store.load(id)
            assertTrue("$id: $r", r is LoadResult.Ok)
            val doc = (r as LoadResult.Ok).doc
            val bmp = app.rendererFor(id).renderBitmap(doc, 1f, doc.canvas.width, doc.canvas.height)
            assertEquals(doc.canvas.width, bmp.width)
            File(out, "$id.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (id == "11-eraser-masks") {
                // Every masked layer is cut where its first mask stroke starts (background shows).
                val r = app.rendererFor(id)
                for (lid in listOf("img-masked", "shape-masked")) {
                    val l = doc.layer(lid)!!
                    val s = l.base.mask.first()
                    val p = floatArrayOf(s.points[0], s.points[1])
                    r.layerMatrix(l).mapPoints(p)
                    val c = bmp.getPixel(p[0].toInt(), p[1].toInt())
                    assertEquals("$lid erased at mask start", android.graphics.Color.WHITE, c)
                }
            }
        }
    }
}

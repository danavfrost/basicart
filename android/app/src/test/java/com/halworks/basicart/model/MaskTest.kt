package com.halworks.basicart.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MaskTest {
    private val shared = File(System.getProperty("basicart.shared") ?: "../../shared")

    private fun img(rot: Int = 0, fh: Boolean = false, fv: Boolean = false, crop: CropRect = CropRect(8.0, 4.0, 40.0, 30.0)) =
        ImageLayer(LayerBase("i", "i", transform = Transform(0.0, 0.0)), "a".repeat(64) + ".png", 64, 48, crop, rot, fh, fv)

    @Test fun boxNaturalRoundTripForAllOrientations() {
        for (r in 0..3) for (h in listOf(false, true)) for (v in listOf(false, true)) {
            val l = img(r, h, v)
            for ((x, y) in listOf(0.0 to 0.0, 3.5 to 7.25, l.boxWidth to l.boxHeight)) {
                val (nx, ny) = MaskGeometry.boxToNatural(l, x, y)
                val (bx, by) = MaskGeometry.naturalToBox(l, nx, ny)
                assertEquals(x, bx, 1e-9); assertEquals(y, by, 1e-9)
            }
        }
        // rotate90:1 maps the crop's top-left natural corner to the box's top-right
        val l = img(1)
        assertEquals(30.0 to 0.0, MaskGeometry.naturalToBox(l, 8.0, 4.0))
    }

    private fun stroke(vararg p: Float) = Stroke(Brush.ERASER, 10.0, points = FloatList(floatArrayOf(*p)))

    @Test fun remapKeepsMaskOnTheSamePixels() {
        val old = img().let { it.copy(base = it.base.copy(mask = listOf(stroke(5f, 6f, 20f, 10f)))) }
        // rotate (UI rule: rotate90+1 and swap flips), then un-crop
        val rotated = MaskGeometry.remapImage(old, old.copy(rotate90 = 1, flipH = old.flipV, flipV = old.flipH))
        val uncropped = MaskGeometry.remapImage(rotated, rotated.copy(crop = CropRect(0.0, 0.0, 64.0, 48.0)))
        val p = uncropped.base.mask[0].points
        // natural position of (5,6) in the old box = (13,10); in the new box (rot 1, full crop) → (48-10, 13)
        assertEquals(38f, p[0], 1e-4f); assertEquals(13f, p[1], 1e-4f)
        assertEquals(10.0, uncropped.base.mask[0].size, 0.0)
        // no geometry change → same instance of the mask
        val same = MaskGeometry.remapImage(old, old.copy(adjust = Adjust(brightness = 10.0)))
        assertTrue(same.base.mask === old.base.mask)
    }

    @Test fun textScalingScalesMaskAboutCenter() {
        val m = MaskGeometry.scaleText(listOf(stroke(0f, 0f, 100f, 50f)), 2.0, 50.0, 25.0, 100.0, 50.0)
        assertEquals(0f, m[0].points[0], 1e-4f); assertEquals(200f, m[0].points[2], 1e-4f)
        assertEquals(20.0, m[0].size, 1e-9)
    }

    @Test fun maskPersistenceDuplicateAndCorrupt() {
        val root = Files.createTempDirectory("ba-mask").toFile()
        File(shared, "fixtures/11-eraser-masks").copyRecursively(File(root, "p"))
        val store = ProjectStore(root)
        val doc = (store.load("p") as LoadResult.Ok).doc
        store.markStrokesSaved(doc)
        // bad mask file → no mask, renamed .corrupt on save; stray drawing mask ignored
        assertEquals(0, doc.layer("shape-bad-mask")!!.base.mask.size)
        assertEquals(0, doc.layer("draw-plain")!!.base.mask.size)
        store.save(doc)
        assertTrue(File(root, "p/strokes/shape-bad-mask.mask.json.corrupt").exists())
        // duplicate copies the mask with the new layerId
        val src = doc.layer("img-masked")!!
        val dup = src.withBase(src.base.copy(id = "img-copy"))
        store.save(doc.copy(layers = doc.layers + dup))
        val f = File(root, "p/strokes/img-copy.mask.json")
        assertTrue(f.exists())
        assertTrue(f.readText().contains("\"layerId\":\"img-copy\""))
        assertTrue(f.readText().contains("\"brush\":\"eraser\""))
        val reloaded = (store.load("p") as LoadResult.Ok).doc
        assertEquals(src.base.mask, reloaded.layer("img-copy")!!.base.mask)
        // clearing a mask (undo) removes the file
        val cleared = reloaded.copy(layers = reloaded.layers.map { if (it.id == "img-copy") it.withBase(it.base.copy(mask = emptyList())) else it })
        store.markStrokesSaved(reloaded)
        store.save(cleared)
        assertFalse(f.exists())
    }

    @Test fun maskWithNonEraserStrokeIsIgnored() {
        assertEquals(ProjectCodec.StrokesResult.Invalid, ProjectCodec.readMask("""{"strokes":[{"brush":"pen","points":[1,2]}]}"""))
        assertTrue(ProjectCodec.readMask("""{"strokes":[{"brush":"eraser","points":[1,2]}]}""") is ProjectCodec.StrokesResult.Ok)
    }
}

class PencilGrainTest {
    @Test fun tileMatchesFormatSpec() {
        val a = PencilGrain.alpha
        assertEquals(listOf(223, 184, 185, 173, 156, 252, 239, 252), (0 until 8).map { a[it].toInt() and 0xFF })
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(a).joinToString("") { "%02x".format(it) }
        assertEquals("84e46aa5abb160be583d2f609760f24f6964dd7dde7af5b556cf6ad3fa8b9bc1", sha)
    }
}

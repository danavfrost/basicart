package com.halworks.basicart.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StoreTest {
    private fun doc(id: String, name: String = "P") = Document(
        id, name, "2026-01-01T00:00:00Z", "", "", CanvasSpec(200, 100),
        listOf(
            DrawingLayer(LayerBase("d1", "Drawing", transform = Transform(100.0, 50.0)), 200, 100,
                listOf(Stroke(Brush.PEN, points = FloatList(floatArrayOf(1f, 2f, 3f, 4f))))),
        ),
    )

    @Test fun saveListDuplicateRenameDeleteClear() {
        val root = Files.createTempDirectory("ba-store").toFile()
        val s = ProjectStore(root)
        s.save(doc("a", "Alpha"))
        Thread.sleep(5)
        s.save(doc("b", "Beta"), "2027-01-01T00:00:00Z")
        val list = s.list()
        assertEquals(listOf("b", "a"), list.map { it.id })
        assertTrue(File(root, "a/strokes/d1.json").exists())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })

        val dup = s.duplicate("a", "Alpha copy")
        assertNotNull(dup)
        val dupDoc = (s.load(dup!!) as LoadResult.Ok).doc
        assertEquals("Alpha copy", dupDoc.name)
        assertEquals(1, (dupDoc.layers[0] as DrawingLayer).strokes.size)

        assertTrue(s.rename("a", "  Renamed  "))
        assertEquals("Renamed", (s.load("a") as LoadResult.Ok).doc.name)

        assertTrue(s.delete("a"))
        assertFalse(File(root, "a").exists())
        assertEquals(2, s.list().size)
        s.clearAll()
        assertEquals(0, s.list().size)
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun assetsAreContentAddressed() {
        val root = Files.createTempDirectory("ba-asset").toFile()
        val s = ProjectStore(root)
        val r1 = s.importAsset("p", byteArrayOf(1, 2, 3), "png")
        val r2 = s.importAsset("p", byteArrayOf(1, 2, 3), "png")
        assertEquals(r1, r2)
        assertTrue(Regex("^[0-9a-f]{64}\\.png$").matches(r1))
        assertEquals(1, File(root, "p/assets").listFiles()!!.size)
    }

    @Test fun corruptStrokesRenamedOnSave() {
        val root = Files.createTempDirectory("ba-cs").toFile()
        val s = ProjectStore(root)
        s.save(doc("c"))
        File(root, "c/strokes/d1.json").writeText("{broken")
        val ProjectStore2 = ProjectStore(root)
        val loaded = (ProjectStore2.load("c") as LoadResult.Ok).doc
        assertEquals(StrokesState.CORRUPT, (loaded.layers[0] as DrawingLayer).strokesState)
        ProjectStore2.save(loaded)
        assertTrue(File(root, "c/strokes/d1.json.corrupt").exists())
    }
}

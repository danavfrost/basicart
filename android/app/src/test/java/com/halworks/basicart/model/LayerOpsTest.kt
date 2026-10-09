package com.halworks.basicart.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerOpsTest {
    private fun shape(id: String) = ShapeLayer(LayerBase(id, id, transform = Transform(0.0, 0.0)), ShapeKind.RECT, 10.0, 10.0)
    private val layers = listOf(shape("a"), shape("b"), shape("c"), shape("d"))
    private fun ids(l: List<Layer>) = l.joinToString("") { it.id }

    @Test fun ordering() {
        assertEquals("bacd", ids(LayerOps.moveUp(layers, "a")))
        assertEquals("abcd", ids(LayerOps.moveUp(layers, "d")))
        assertEquals("abdc", ids(LayerOps.moveDown(layers, "d")))
        assertEquals("abcd", ids(LayerOps.moveDown(layers, "a")))
        assertEquals("bcda", ids(LayerOps.toTop(layers, "a")))
        assertEquals("dabc", ids(LayerOps.toBottom(layers, "d")))
        assertEquals("bcad", ids(LayerOps.move(layers, 0, 2)))
        assertEquals("adbc", ids(LayerOps.move(layers, 3, 1)))
        assertEquals("abcd", ids(LayerOps.move(layers, 9, 1)))
        assertEquals("abxcd", ids(LayerOps.insertAbove(layers, shape("x"), "b")))
        assertEquals("abcdx", ids(LayerOps.insertAbove(layers, shape("x"), null)))
        assertEquals("acd", ids(LayerOps.remove(layers, "b")))
    }

    @Test fun history() {
        val h = History<Int>(limit = 50)
        assertFalse(h.canUndo)
        var cur = 0
        repeat(60) { h.push(cur); cur++ }
        assertEquals(50, h.undoSize)
        cur = h.undo(cur)!!
        assertEquals(59, cur)
        cur = h.undo(cur)!!
        assertEquals(58, cur)
        assertTrue(h.canRedo)
        cur = h.redo(cur)!!
        assertEquals(59, cur)
        h.push(cur); cur = 100
        assertFalse(h.canRedo)
        assertEquals(59, h.undo(cur))
        val h2 = History<Int>()
        assertNull(h2.undo(1))
    }
}

class PresetsAndFontsTest {
    private val shared = java.io.File(System.getProperty("basicart.shared") ?: "../../shared")

    @Test fun presetsApplyAndKeepContent() {
        val presets = TextPresets.parse(java.io.File(shared, "presets/text-presets.json").readText())
        val meme = presets.first { it.id == "classic-meme" }
        val t = TextLayer(
            LayerBase("t", "T", transform = Transform(10.0, 10.0)), text = "Hello", fontSize = 90.0,
            weight = 700, flags = StyleFlags(italic = true), spans = listOf(Span(0, 2, StyleFlags())),
            shadow = Shadow(enabled = true),
        )
        val m = TextPresets.apply(t, meme)
        assertEquals("anton", m.fontId)
        assertEquals(400, m.weight)
        assertEquals(TextCase.UPPER, m.textCase)
        assertEquals(90.0, m.fontSize, 0.0)
        assertEquals("Hello", m.text)
        assertFalse(m.shadow.enabled)
        assertFalse(m.flags.italic)
        assertTrue(m.outline.enabled)
        assertEquals(meme.props["outline"]!!.let { (it as kotlinx.serialization.json.JsonObject)["width"]!!.toString().toDouble() }, m.outline.width, 1e-9)
        assertEquals(1.05, m.lineHeight, 1e-9)
        // span (0,2) plain now equals layer flags → dropped by normalization
        assertEquals(emptyList<Span>(), m.spans)
        val retro = TextPresets.apply(t, presets.first { it.id == "retro" })
        assertTrue(retro.fill is Fill.Linear)
        assertEquals(3, (retro.fill as Fill.Linear).stops.size)
    }

    @Test fun palettesLoad() {
        val p = Palettes.parse(java.io.File(shared, "palettes/palettes.json").readText())
        assertTrue(p.size >= 7)
        assertEquals("Basic", p[0].name)
    }

    private fun f(w: Int, it: Boolean = false) = FontFileSpec(w, it, "", "f$w$it")

    @Test fun fontSelection() {
        val inter = listOf(f(300), f(400), f(700), f(900), f(400, true), f(700, true))
        assertEquals(400, FontSelect.select(inter, 400, false, false).file.weight)
        assertEquals(700, FontSelect.select(inter, 400, true, false).file.weight)
        assertEquals(900, FontSelect.select(inter, 900, true, false).file.weight)
        assertEquals(700, FontSelect.select(inter, 300, true, false).file.weight)
        val bi = FontSelect.select(inter, 400, true, true)
        assertEquals(700, bi.file.weight); assertTrue(bi.file.italic); assertFalse(bi.synthItalic)
        // ties → lighter for regular
        val two = listOf(f(300), f(500))
        assertEquals(300, FontSelect.select(two, 400, false, false).file.weight)
        // single-weight family: bold synthesized, italic synthesized
        val anton = listOf(f(400))
        val c = FontSelect.select(anton, 400, true, true)
        assertTrue(c.synthBold); assertTrue(c.synthItalic)
        // 700 missing → 800
        val noB = listOf(f(400), f(800), f(900))
        assertEquals(800, FontSelect.select(noB, 400, true, false).file.weight)
        // ties → heavier for bold (600 vs 800 around T=700)
        val tie = listOf(f(400), f(600), f(800))
        assertEquals(800, FontSelect.select(tie, 400, true, false).file.weight)
        assertEquals(400, FontSelect.nearestWeight(inter, 450))
    }
}

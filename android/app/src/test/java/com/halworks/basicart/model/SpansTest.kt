package com.halworks.basicart.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpansTest {
    private val plain = LayerStyle(StyleFlags(), BLACK, "inter", 400, 64.0)
    private val B = SpanStyle(bold = true)
    private val I = SpanStyle(italic = true)
    private fun layer(text: String, spans: List<Span> = emptyList(), fill: Fill = Fill.Solid(BLACK), flags: StyleFlags = StyleFlags()) =
        TextLayer(LayerBase("t", "t", transform = Transform(0.0, 0.0)), text = text, spans = spans, fill = fill, flags = flags)

    @Test fun graphemes() {
        assertArrayEquals(intArrayOf(0, 1, 2), Graphemes.boundaries("ab"))
        assertArrayEquals(intArrayOf(0, 1, 5, 6), Graphemes.boundaries("a👋🏽b"))
        assertArrayEquals(intArrayOf(0, 2), Graphemes.boundaries("❤️"))
        assertArrayEquals(intArrayOf(0, 2), Graphemes.boundaries("é"))
        val fam = "👨‍👩‍👧"
        assertArrayEquals(intArrayOf(0, fam.length), Graphemes.boundaries(fam))
        assertArrayEquals(intArrayOf(0, 4, 8), Graphemes.boundaries("🇺🇸🇬🇧"))
        assertArrayEquals(intArrayOf(0), Graphemes.boundaries(""))
    }

    @Test fun normalizeReducesSortsMergesClamps() {
        val t = "Hello brave new world"
        val spans = listOf(
            Span(16, 30, I), // clamped to 21
            Span(6, 8, SpanStyle(bold = true, italic = false)), Span(8, 11, B), // italic=false equals layer → merge
            Span(0, 3, SpanStyle(fontId = "inter", weight = 400, size = 64.0, color = BLACK)), // all equal → dropped
            Span(4, 4, B),
        )
        assertEquals(listOf(Span(6, 11, B), Span(16, 21, I)), Spans.normalize(t, plain, spans))
    }

    @Test fun colorAgainstGradientIsAlwaysKept() {
        val grad = plain.copy(solidColor = null)
        assertEquals(listOf(Span(0, 2, SpanStyle(color = BLACK))), Spans.normalize("abc", grad, listOf(Span(0, 2, SpanStyle(color = BLACK)))))
        assertEquals(emptyList<Span>(), Spans.normalize("abc", plain, listOf(Span(0, 2, SpanStyle(color = BLACK)))))
    }

    @Test fun laterSpanWinsOverlap() {
        assertEquals(listOf(Span(0, 3, B), Span(3, 8, I)), Spans.normalize("abcdefgh", plain, listOf(Span(0, 5, B), Span(3, 8, I))))
    }

    @Test fun snapsOutwardToGraphemes() {
        assertEquals(listOf(Span(2, 4, B)), Spans.normalize("I ❤️ you", plain, listOf(Span(3, 4, B))))
        assertEquals(listOf(Span(1, 5, B)), Spans.normalize("x👋🏽y", plain, listOf(Span(2, 3, B))))
    }

    @Test fun toggleSelection() {
        var t = layer("Hello world")
        var r = Spans.toggle(t.text, t, StyleFlag.BOLD, 0, 5)
        assertEquals(listOf(Span(0, 5, B)), r.spans); t = t.copy(spans = r.spans)
        r = Spans.toggle(t.text, t, StyleFlag.BOLD, 0, 2)
        assertEquals(listOf(Span(2, 5, B)), r.spans); t = t.copy(spans = r.spans)
        r = Spans.toggle(t.text, t, StyleFlag.BOLD, 0, 8)
        assertEquals(listOf(Span(0, 8, B)), r.spans); t = t.copy(spans = r.spans)
        assertTrue(Spans.allHave(t.text, t.layerStyle(), t.spans, StyleFlag.BOLD, 1, 7))
        assertFalse(Spans.allHave(t.text, t.layerStyle(), t.spans, StyleFlag.BOLD, 1, 10))
    }

    @Test fun toggleWholeSetsLayerAndClearsField() {
        val t = layer("Hello world", listOf(Span(0, 5, SpanStyle(italic = true, bold = false))))
        val r = Spans.toggle(t.text, t, StyleFlag.BOLD, 3, 3)
        assertEquals(StyleFlags(bold = true), r.layerFlags)
        assertEquals(listOf(Span(0, 5, I)), r.spans)
    }

    @Test fun charStylingSelectionAndWhole() {
        var t = layer("plain RED plain")
        t = CharStyling.setColor(t, 6, 9, 0xFFE53935.toInt())
        t = CharStyling.setFont(t, 6, 9, "anton", 400)
        t = CharStyling.setSize(t, 6, 9, 90.0)
        assertEquals(listOf(Span(6, 9, SpanStyle(color = 0xFFE53935.toInt(), fontId = "anton", size = 90.0))), t.spans)
        // whole-box color: layer fill, span color removed
        val w = CharStyling.setColor(t, 0, 0, WHITE)
        assertEquals(Fill.Solid(WHITE), w.fill)
        assertNull(w.spans.single().style.color)
        // gradient is layer-wide and removes span colors
        val g = CharStyling.setLayerFill(t, Fill.Linear(0.0, listOf(GradientStop(0.0, BLACK), GradientStop(1.0, WHITE))))
        assertNull(g.spans.single().style.color)
        // whole-box size: layer size, span size cleared
        val z = CharStyling.setSize(t, 0, t.text.length, 50.0)
        assertEquals(50.0, z.fontSize, 0.0)
        assertNull(z.spans.single().style.size)
        // choosing the layer's own value on a range drops the field
        assertEquals(emptyList<Span>(), CharStyling.setFont(layer("abc"), 0, 2, "inter", 400).spans)
    }

    @Test fun scaleAndInsertDelete() {
        assertEquals(listOf(Span(0, 2, SpanStyle(size = 133.3333))), Spans.scaleSizes(listOf(Span(0, 2, SpanStyle(size = 100.0))), 4.0 / 3.0))
        val spans = listOf(Span(2, 4, B))
        assertEquals(listOf(Span(2, 6, B)), Spans.insert("abXXcdef", plain, spans, 3, 2))
        assertEquals(listOf(Span(2, 6, B)), Spans.insert("abcdXXef", plain, spans, 4, 2))
        assertEquals(listOf(Span(4, 6, B)), Spans.insert("abXXcdef", plain, spans, 2, 2))
        assertEquals(listOf(Span(0, 4, B)), Spans.insert("XXabcdef", plain, listOf(Span(0, 2, B)), 0, 2))
        assertEquals(listOf(Span(2, 4, B), Span(4, 5, I)), Spans.delete("01239", plain, listOf(Span(2, 6, B), Span(8, 10, I)), 4, 9))
        assertEquals(listOf(Span(11, 16, B)), Spans.applyEdit("Hello brave world", "Hello very brave world", plain, listOf(Span(6, 11, B))))
    }

    @Test fun presetsClearSpanColorFontWeight() {
        val shared = java.io.File(System.getProperty("basicart.shared") ?: "../../shared")
        val preset = TextPresets.parse(java.io.File(shared, "presets/text-presets.json").readText()).first { it.id == "caption-bar" }
        val t = layer("abcdef", listOf(Span(0, 3, SpanStyle(color = 0xFF00FF00.toInt(), fontId = "anton", weight = 900, size = 30.0, underline = true))))
        val p = TextPresets.apply(t, preset)
        assertEquals(listOf(Span(0, 3, SpanStyle(size = 30.0, underline = true))), p.spans)
    }
}

class MixedStylesFixtureTest {
    private val shared = java.io.File(System.getProperty("basicart.shared") ?: "../../shared")

    @Test fun fixture12SpansParseAndWriteNormalized() {
        val root = java.nio.file.Files.createTempDirectory("ba-12").toFile()
        java.io.File(shared, "fixtures/12-mixed-styles").copyRecursively(java.io.File(root, "p"))
        val doc = (ProjectStore(root).load("p") as LoadResult.Ok).doc
        val styled = doc.layer("t-styled") as TextLayer
        assertTrue(styled.fill is Fill.Linear)
        assertEquals(SpanStyle(color = 0xFFFFD23F.toInt(), fontId = "anton", size = 110.0), styled.spans[0].style)
        assertEquals(SpanStyle(fontId = "caveat", size = 32.0, italic = true), styled.spans[4].style)
        // white span color over a gradient is kept (never equal to a gradient fill)
        assertEquals(WHITE, styled.spans[2].style.color)
        val solid = doc.layer("t-solid") as TextLayer
        assertEquals(SpanStyle(color = 0xFFE53935.toInt(), weight = 900), solid.spans.single().style)
        // writer emits exactly the normalized spans of the fixtures (only fields that differ)
        for (fx in listOf("03-rich-text-spans", "12-mixed-styles")) {
            val src = java.io.File(shared, "fixtures/$fx/project.json").readText()
            val d = (ProjectCodec.load(src) as LoadResult.Ok).doc
            fun spansOf(json: String) = kotlinx.serialization.json.Json.parseToJsonElement(json)
                .let { it as kotlinx.serialization.json.JsonObject }["layers"].toString()
                .let { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonArray }
                .map { (it as kotlinx.serialization.json.JsonObject)["spans"] }
            assertEquals(fx, spansOf(src), spansOf(ProjectCodec.write(d, "t")))
        }
    }
}

package com.halworks.basicart.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecTest {
    private fun minimal(extraLayer: String = "", canvas: String = "\"width\": 100, \"height\": 100") = """
        {"formatVersion": 1, "id": "p1", "canvas": {$canvas}, "layers": [$extraLayer]}
    """.trimIndent()

    @Test fun colors() {
        assertEquals(0xFF112233.toInt(), Colors.parse("#112233"))
        assertEquals(0x80112233.toInt(), Colors.parse("#11223380"))
        assertEquals(0x80AABBCC.toInt(), Colors.parse("#aabbcc80"))
        assertEquals(null, Colors.parse("#abc"))
        assertEquals(null, Colors.parse("112233"))
        assertEquals("#AABBCC80", Colors.format(0x80AABBCC.toInt()))
        assertEquals("#00000000", Colors.format(0))
    }

    @Test fun defaultsApplied() {
        val r = ProjectCodec.load(minimal()) as LoadResult.Ok
        assertEquals("Untitled", r.doc.name)
        assertEquals(WHITE, r.doc.canvas.background)
    }

    @Test fun outOfRangeValuesAreClamped() {
        val r = ProjectCodec.load(minimal("""{"id":"t","type":"text","opacity":1.3,"transform":{"rotation":-90},"text":"x","fontSize":9000}""")) as LoadResult.Ok
        val t = r.doc.layers[0] as TextLayer
        assertEquals(1.0, t.opacity, 0.0)
        assertEquals(2000.0, t.fontSize, 0.0)
        assertEquals(270.0, t.transform.rotation, 0.0)
        assertEquals(50.0, t.transform.x, 0.0) // default = canvas center
    }

    @Test fun corruptCases() {
        assertTrue(ProjectCodec.load("[]") is LoadResult.Corrupt)
        assertTrue(ProjectCodec.load("{") is LoadResult.Corrupt)
        assertTrue(ProjectCodec.load(minimal(canvas = "\"width\": 10, \"height\": 100")) is LoadResult.Corrupt)
        assertTrue(ProjectCodec.load(minimal("""{"id":"a","type":"blob","transform":{}}""")) is LoadResult.Corrupt)
        assertTrue(ProjectCodec.load(minimal("""{"id":"a","type":"shape","shape":"star","width":1,"height":1,"transform":{}}""")) is LoadResult.Corrupt)
        assertTrue(ProjectCodec.load(minimal("""{"id":"a","type":"text","text":"x","visible":1,"transform":{}}""")) is LoadResult.Corrupt)
        assertTrue(ProjectCodec.load("""{"formatVersion": 0, "id":"p", "canvas":{"width":100,"height":100},"layers":[]}""") is LoadResult.Corrupt)
        assertTrue(ProjectCodec.load("""{"formatVersion": "1", "id":"p", "canvas":{"width":100,"height":100},"layers":[]}""") is LoadResult.Corrupt)
    }

    @Test fun malformedColorStringFallsBackToFieldDefault() {
        val r = ProjectCodec.load(minimal(
            """{"id":"s","type":"shape","shape":"rect","width":10,"height":10,"transform":{},"fill":{"enabled":true,"color":"#12"},"stroke":{"color":"red"}},
               {"id":"t","type":"text","text":"x","transform":{},"fill":{"type":"solid","color":"#GGGGGG"},"shadow":{"enabled":true,"color":"#1234567"}}""",
            canvas = "\"width\": 100, \"height\": 100, \"background\": \"white\"",
        ))
        assertTrue("$r", r is LoadResult.Ok)
        val d = (r as LoadResult.Ok).doc
        assertEquals(WHITE, d.canvas.background)
        assertEquals(0xFF3478F6.toInt(), (d.layers[0] as ShapeLayer).fill.color)
        assertEquals(BLACK, (d.layers[0] as ShapeLayer).stroke.color)
        assertEquals(Fill.Solid(BLACK), (d.layers[1] as TextLayer).fill)
        assertEquals(0x80000000.toInt(), (d.layers[1] as TextLayer).shadow.color)
        // a non-string color is a type error → corrupt
        assertTrue(ProjectCodec.load(minimal(canvas = "\"width\": 100, \"height\": 100, \"background\": 5")) is LoadResult.Corrupt)
    }

    @Test fun unknownBrushInvalidatesOnlyTheStrokesFile() {
        val root = java.nio.file.Files.createTempDirectory("ba-brush").toFile()
        val d = java.io.File(root, "p").apply { mkdirs() }
        java.io.File(d, "project.json").writeText(minimal("""{"id":"dr","type":"drawing","width":100,"height":100,"transform":{}}"""))
        java.io.File(d, "strokes").mkdirs()
        java.io.File(d, "strokes/dr.json").writeText("""{"formatVersion":1,"layerId":"dr","strokes":[{"brush":"pen","points":[1,2]},{"brush":"crayon","points":[3,4]}]}""")
        val r = ProjectStore(root).load("p")
        assertTrue("project still opens", r is LoadResult.Ok)
        val l = (r as LoadResult.Ok).doc.layers[0] as DrawingLayer
        assertEquals(0, l.strokes.size)
        assertEquals(StrokesState.CORRUPT, l.strokesState)
    }

    @Test fun newerVersionBeforeValidation() {
        val r = ProjectCodec.load("""{"formatVersion": 2, "name": "Future", "canvas": "nonsense"}""")
        assertEquals(LoadResult.NewerVersion(2, "Future"), r)
    }

    @Test fun writerRoundsAndNormalizes() {
        val doc = Document(
            "p", "n", "", "", "", CanvasSpec(100, 100),
            listOf(
                ShapeLayer(LayerBase("s", "S", transform = Transform(1.234567, 2.0, 1.0, 370.0)), ShapeKind.LINE, 50.0, 99.0,
                    stroke = ShapeStroke(width = 6.0)),
            ),
        )
        val json = ProjectCodec.write(doc, "test")
        assertTrue(json, json.contains("\"x\": 1.2346"))
        assertTrue(json, json.contains("\"rotation\": 10"))
        assertTrue(json, json.contains("\"height\": 6"))
        assertTrue(json, json.contains("\n  \"canvas\""))
    }

    @Test fun invalidStrokesFileIsNotFatal() {
        assertEquals(ProjectCodec.StrokesResult.Invalid, ProjectCodec.readStrokes("{nope"))
        assertEquals(ProjectCodec.StrokesResult.Invalid, ProjectCodec.readStrokes("""{"strokes":[{"brush":"crayon","points":[1,2]}]}"""))
        val ok = ProjectCodec.readStrokes("""{"strokes":[{"brush":"pen","points":[1,2,3,4],"pressure":[0.5,1]}]}""")
        assertTrue(ok is ProjectCodec.StrokesResult.Ok)
    }
}

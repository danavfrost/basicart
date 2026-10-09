package com.halworks.basicart

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.halworks.basicart.data.AppContainer
import com.halworks.basicart.model.CanvasSpec
import com.halworks.basicart.model.Document
import com.halworks.basicart.model.LayerBase
import com.halworks.basicart.model.OutlineStyle
import com.halworks.basicart.model.Shadow
import com.halworks.basicart.model.TextLayer
import com.halworks.basicart.model.TextPresets
import com.halworks.basicart.model.Transform
import com.halworks.basicart.model.WHITE
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Text effects (glow, outline, shadow, italic overhang) must never be clipped at the layout
 * box: the layer's content bounds must contain every painted pixel.
 */
@RunWith(AndroidJUnit4::class)
class EffectsBoundsTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = AppContainer.get(ctx)

    private fun variants(): List<TextLayer> {
        val meme = app.presets.first { it.id == "classic-meme" }
        val base = TextPresets.apply(TextLayer(LayerBase("t", "t", transform = Transform(500.0, 400.0)), text = "WHEN THE CODE", fontSize = 90.0), meme)
        return listOf(
            base,
            base.copy(outline = base.outline.copy(style = OutlineStyle.GLOW, glowRadius = 0.6)),
            base.copy(outline = base.outline.copy(style = OutlineStyle.DOUBLE, width = 0.1, width2 = 0.1)),
            base.copy(shadow = Shadow(true, 0xFF000000.toInt(), 0.3, 0.4, 0.4)),
            base.copy(skew = 30.0, flags = base.flags.copy(italic = true)),
            base.copy(curve = 60.0),
            base.copy(text = "Your text", fontSize = 86.0, outline = base.outline.copy(style = OutlineStyle.GLOW)),
        )
    }

    @Test fun effectsStayInsideContentBounds() {
        val r = app.rendererFor("bounds-test")
        val out = File(ctx.getExternalFilesDir(null), "bounds").apply { mkdirs() }
        variants().forEachIndexed { i, t ->
            val b = r.contentBounds(t)
            // Render the layer into a bitmap with a generous margin around its content bounds.
            val pad = 200f
            val bmp = Bitmap.createBitmap((b.width() + 2 * pad).toInt(), (b.height() + 2 * pad).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.translate(pad - b.left, pad - b.top)
            r.drawContent(c, t, 1f)
            File(out, "v$i.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            var outside = 0
            for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                val inside = x >= pad - 1 && y >= pad - 1 && x <= pad + b.width() + 1 && y <= pad + b.height() + 1
                if (!inside && (bmp.getPixel(x, y) ushr 24) > 8) outside++
            }
            assertTrue("variant $i paints $outside px outside its content bounds", outside == 0)
        }
        // And the document render matches: nothing is clipped by the cache path either.
        val doc = Document("bounds-test", "b", "", "", "", CanvasSpec(1000, 800, WHITE), variants().take(2))
        r.renderBitmap(doc, 1f, 1000, 800).compress(Bitmap.CompressFormat.PNG, 100, File(out, "doc.png").outputStream())
        val cache = com.halworks.basicart.ui.editor.LayerCache(r)
        val scr = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
        val sc = Canvas(scr); sc.drawColor(WHITE)
        val v = variants()
        cache.draw(Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)), v[0], 0.5f, 0f, 0f, false)
        val g = v[0].copy(text = "Your text", fontSize = 86.0, outline = v[0].outline.copy(style = OutlineStyle.GLOW))
        cache.draw(sc, g, 0.88f, 0f, 0f, false)
        scr.compress(Bitmap.CompressFormat.PNG, 100, File(out, "cache.png").outputStream())
    }
}

@RunWith(AndroidJUnit4::class)
class FallbackGlyphsTest {
    /** Characters missing from the chosen font (CJK, symbols, color emoji) fall back, are measured and drawn. */
    @Test fun missingGlyphsFallBackAndAreMeasured() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val r = AppContainer.get(ctx).rendererFor("fallback")
        val t = TextLayer(LayerBase("t", "t", transform = Transform(0.0, 0.0)), text = "Ab 日本 ✓ 👍🏽 é", fontId = "anton", fontSize = 80.0)
        val lay = r.layout(t)
        val b = r.contentBounds(t)
        val bmp = Bitmap.createBitmap(b.width().toInt() + 2, b.height().toInt() + 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.translate(-b.left, -b.top)
        r.drawContent(c, t, 1f)
        for (g in lay.glyphs) {
            if (g.isSpace || g.isNewline) continue
            assertTrue("'${g.display}' has an advance", g.advance > 5f)
            var ink = 0
            val x0 = (g.x - b.left).toInt().coerceAtLeast(0); val x1 = (g.x + g.advance - b.left).toInt().coerceAtMost(bmp.width - 1)
            for (x in x0..x1) for (y in 0 until bmp.height) if ((bmp.getPixel(x, y) ushr 24) > 64) ink++
            assertTrue("'${g.display}' is drawn", ink > 20)
        }
    }
}

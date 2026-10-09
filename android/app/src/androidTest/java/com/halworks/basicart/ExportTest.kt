package com.halworks.basicart

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.halworks.basicart.data.AppContainer
import com.halworks.basicart.data.ExportFormat
import com.halworks.basicart.data.Exporter
import com.halworks.basicart.model.CanvasSpec
import com.halworks.basicart.model.Document
import com.halworks.basicart.model.LayerBase
import com.halworks.basicart.model.ShapeKind
import com.halworks.basicart.model.ShapeLayer
import com.halworks.basicart.model.TRANSPARENT
import com.halworks.basicart.model.TextLayer
import com.halworks.basicart.model.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Export encoders produce valid files with the right dimensions (spec §16.1). */
@RunWith(AndroidJUnit4::class)
class ExportTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = AppContainer.get(ctx)
    private val doc = Document(
        "export-test", "Export test", "", "", "", CanvasSpec(640, 360, TRANSPARENT),
        listOf(
            ShapeLayer(LayerBase("s", "S", transform = Transform(320.0, 180.0)), ShapeKind.ELLIPSE, 300.0, 200.0),
            TextLayer(LayerBase("t", "T", transform = Transform(320.0, 180.0)), text = "Export", fontSize = 80.0),
        ),
    )

    private fun check(format: ExportFormat, scale: Float, w: Int, h: Int) {
        val bytes = Exporter.encode(app.rendererFor(doc.id), doc, format, scale, 90) { _, _ -> }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        assertEquals("$format width", w, opts.outWidth)
        assertEquals("$format height", h, opts.outHeight)
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val corner = full.getPixel(0, 0)
        when (format) {
            ExportFormat.JPEG -> assertTrue("JPEG flattens onto white", android.graphics.Color.red(corner) > 240)
            else -> assertEquals("$format keeps transparency", 0, android.graphics.Color.alpha(corner))
        }
    }

    @Test fun png() = check(ExportFormat.PNG, 1f, 640, 360)
    @Test fun jpegHalf() = check(ExportFormat.JPEG, 0.5f, 320, 180)
    @Test fun gifQuarter() = check(ExportFormat.GIF, 0.25f, 160, 90)
    @Test fun customLongEdge() = check(ExportFormat.PNG, 1000f / 640f, 1000, 563)
}

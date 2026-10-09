package com.halworks.basicart.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.halworks.basicart.model.Document
import com.halworks.basicart.model.GifEncoder
import com.halworks.basicart.render.Renderer
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Renders the document from the model (never the screen) and encodes it. */
object Exporter {
    fun outputSize(doc: Document, scale: Float): Pair<Int, Int> =
        max(1, (doc.canvas.width * scale).roundToInt()) to max(1, (doc.canvas.height * scale).roundToInt())

    fun encode(renderer: Renderer, doc: Document, format: ExportFormat, scale: Float, jpegQuality: Int, progress: (String, Float) -> Unit): ByteArray {
        val (w, h) = outputSize(doc, scale)
        progress("Rendering…", 0.1f)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (format == ExportFormat.JPEG) c.drawColor(android.graphics.Color.WHITE) // flatten transparency onto white
        renderer.drawDocument(c, doc, w.toFloat() / doc.canvas.width)
        progress("Encoding…", 0.5f)
        val out = ByteArrayOutputStream()
        try {
            when (format) {
                ExportFormat.PNG -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                ExportFormat.JPEG -> bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(50, 100), out)
                ExportFormat.GIF -> {
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    out.write(GifEncoder.encode(px, w, h) { f -> progress("Encoding…", 0.5f + 0.45f * f) })
                }
            }
        } finally { bmp.recycle() }
        return out.toByteArray()
    }

    fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(80).ifEmpty { "Basic Art" }

    /** Saves to Pictures/Basic Art via MediaStore. Returns a user-facing location. */
    fun saveToDevice(context: Context, bytes: ByteArray, fileName: String, format: ExportFormat): String {
        val display = "${safeName(fileName)}.${format.ext}"
        val resolver = context.contentResolver
        var saved = display
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, display)
                put(MediaStore.Images.Media.MIME_TYPE, format.mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Basic Art")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create the file")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Couldn't write the file")
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null); throw e
            }
            // MediaStore renames on collision ("Name (1).jpg"): report the name it actually used.
            saved = actualName(context, uri) ?: display
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Basic Art")
            dir.mkdirs()
            var f = File(dir, display)
            var i = 1
            while (f.exists()) { f = File(dir, "${safeName(fileName)} ($i).${format.ext}"); i++ }
            f.writeBytes(bytes)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Images.Media.MIME_TYPE, format.mime)
                @Suppress("DEPRECATION") put(MediaStore.Images.Media.DATA, f.absolutePath)
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            saved = f.name
        }
        return "Pictures/Basic Art/$saved"
    }

    /** The display name MediaStore gave an item (it may differ from the requested one). */
    fun actualName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    /** Writes to cache/exports and returns a share intent (FileProvider). */
    fun shareIntent(context: Context, bytes: ByteArray, fileName: String, format: ExportFormat): Intent {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "${safeName(fileName)}.${format.ext}")
        f.writeBytes(bytes)
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".exports", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share image")
    }
}

package com.halworks.basicart.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.halworks.basicart.model.CanvasSpec
import com.halworks.basicart.model.Document
import com.halworks.basicart.model.ImageLayer
import com.halworks.basicart.model.LayerBase
import com.halworks.basicart.model.Timestamps
import com.halworks.basicart.model.Transform
import com.halworks.basicart.render.Renderer
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Projects {
    fun newId() = UUID.randomUUID().toString()

    /** "Untitled", then "Untitled 2", "Untitled 3"… (the date is already shown under each tile). */
    fun defaultName(app: AppContainer? = null): String {
        val names = app?.store?.list()?.mapNotNull { (it as? com.halworks.basicart.model.ProjectEntry.Ok)?.name }?.toSet() ?: emptySet()
        if ("Untitled" !in names) return "Untitled"
        var i = 2
        while ("Untitled $i" in names) i++
        return "Untitled $i"
    }

    /** "Today, 9:39 PM" · "Yesterday, 8:02 AM" · "Oct 3" · "Oct 3, 2025". */
    fun relativeDate(millis: Long, now: Long = System.currentTimeMillis()): String {
        val zone = java.time.ZoneId.systemDefault()
        val d = java.time.Instant.ofEpochMilli(millis).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(millis))
        return when (d.toLocalDate()) {
            today -> "Today, $time"
            today.minusDays(1) -> "Yesterday, $time"
            else -> {
                val pattern = if (d.year == today.year) "MMM d" else "MMM d, yyyy"
                SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), pattern), Locale.getDefault()).format(Date(millis))
            }
        }
    }

    fun create(app: AppContainer, width: Int, height: Int, background: Int, name: String?): String {
        val now = Timestamps.now()
        val doc = Document(
            newId(), name?.trim()?.takeIf { it.isNotEmpty() }?.take(100) ?: defaultName(app), now, now, "",
            CanvasSpec(width.coerceIn(16, 8192), height.coerceIn(16, 8192), background), emptyList(),
        )
        app.store.save(doc, now)
        return doc.id
    }

    /** "Start from photo": canvas sized to the photo (fit within 8192), photo as the bottom layer. */
    fun createFromPhoto(app: AppContainer, context: Context, uri: Uri, name: String?): String? {
        val id = newId()
        val img = try { ImageImport.import(context, app.store, id, uri) } catch (e: Exception) { null }
        if (img == null) { app.store.delete(id); return null }
        val f = min(1.0, 8192.0 / max(img.naturalWidth, img.naturalHeight))
        val w = max(16, (img.naturalWidth * f).roundToInt())
        val h = max(16, (img.naturalHeight * f).roundToInt())
        val now = Timestamps.now()
        val layer = ImageLayer(
            LayerBase(newId(), "Photo", transform = Transform(w / 2.0, h / 2.0, f, 0.0)),
            img.assetRef, img.naturalWidth, img.naturalHeight,
        )
        val doc = Document(
            id, name?.trim()?.takeIf { it.isNotEmpty() }?.take(100) ?: defaultName(app), now, now, "",
            CanvasSpec(w, h, com.halworks.basicart.model.WHITE), listOf(layer),
        )
        app.store.save(doc, now)
        writeThumb(app, app.rendererFor(id), doc)
        return id
    }

    /** thumb.png: whole canvas, ≤512 px longest side, never upscaled (FORMAT.md §10.5). */
    fun writeThumb(app: AppContainer, renderer: Renderer, doc: Document) {
        try {
            val s = min(1f, 512f / max(doc.canvas.width, doc.canvas.height))
            val w = max(1, (doc.canvas.width * s).roundToInt())
            val h = max(1, (doc.canvas.height * s).roundToInt())
            val bmp = renderer.renderBitmap(doc, s, w, h)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            app.store.writeThumb(doc.id, out.toByteArray())
            ThumbCache.invalidate(doc.id)
        } catch (e: Throwable) {
            android.util.Log.w("Projects", "Thumbnail failed", e)
        }
    }
}

/** Decoded home-grid thumbnails keyed by project id + file timestamp. */
object ThumbCache {
    private val cache = android.util.LruCache<String, Bitmap>(48)
    private val versions = HashMap<String, Long>()
    @Synchronized fun get(id: String, mtime: Long): Bitmap? = if (versions[id] == mtime) cache.get(id) else null
    @Synchronized fun put(id: String, mtime: Long, b: Bitmap) { versions[id] = mtime; cache.put(id, b) }
    @Synchronized fun invalidate(id: String) { versions.remove(id); cache.remove(id) }
}

package com.halworks.basicart.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.halworks.basicart.model.ProjectStore
import com.halworks.basicart.render.ImageProvider
import com.halworks.basicart.render.ImageSource
import java.io.File

/**
 * Decodes project assets with EXIF orientation applied, at power-of-two sample sizes
 * (proxies for the screen, full resolution for export), with a shared memory-bounded cache.
 */
class ImageStore(private val store: ProjectStore) {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 5).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val missing = HashSet<String>()

    fun forProject(projectId: String) = ImageProvider { ref, minScale -> get(store.assetFile(projectId, ref), minScale) }

    fun get(file: File, minScale: Float): ImageSource? {
        val path = file.absolutePath
        synchronized(missing) { if (path in missing) return null }
        if (!file.exists()) { synchronized(missing) { missing.add(path) }; return null }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { synchronized(missing) { missing.add(path) }; return null }
        // Pick the largest power-of-two sample that keeps ≥ minScale, capped for memory.
        var sample = 1
        while (1f / (sample * 2) >= minScale.coerceAtMost(1f) && sample < 64) sample *= 2
        val maxPixels = 48_000_000L
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > maxPixels) sample *= 2
        // Prefer an already-cached finer decode.
        var s = sample
        while (s >= 1) {
            cache.get("$path@$s")?.let { return ImageSource(it, it.width.toFloat() / orientedWidth(path, bounds)) }
            s /= 2
        }
        val bmp = synchronized(this) {
            cache.get("$path@$sample") ?: decode(path, sample)?.also { cache.put("$path@$sample", it) }
        } ?: run { synchronized(missing) { missing.add(path) }; return null }
        return ImageSource(bmp, bmp.width.toFloat() / orientedWidth(path, bounds))
    }

    private val orientCache = HashMap<String, Int>()
    private fun orientation(path: String): Int = synchronized(orientCache) {
        orientCache.getOrPut(path) {
            try { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        }
    }

    private fun swaps(o: Int) = o == ExifInterface.ORIENTATION_ROTATE_90 || o == ExifInterface.ORIENTATION_ROTATE_270 ||
        o == ExifInterface.ORIENTATION_TRANSPOSE || o == ExifInterface.ORIENTATION_TRANSVERSE

    private fun orientedWidth(path: String, b: BitmapFactory.Options): Float =
        (if (swaps(orientation(path))) b.outHeight else b.outWidth).toFloat()

    private fun decode(path: String, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val raw = try { BitmapFactory.decodeFile(path, opts) } catch (e: OutOfMemoryError) { null } ?: return null
        return applyOrientation(raw, orientation(path))
    }

    companion object {
        fun applyOrientation(raw: Bitmap, o: Int): Bitmap {
            val m = Matrix()
            when (o) {
                ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> return raw
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
                else -> return raw
            }
            val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (out != raw) raw.recycle()
            return out
        }
    }
}

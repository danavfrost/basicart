package com.halworks.basicart.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.halworks.basicart.model.ProjectStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

data class ImportedImage(val assetRef: String, val naturalWidth: Int, val naturalHeight: Int)

/** Copies a picked image into a project's assets (original bytes, content-addressed). */
object ImageImport {
    private const val MAX_BYTES = 80L * 1024 * 1024

    fun import(context: Context, store: ProjectStore, projectId: String, uri: Uri): ImportedImage? {
        var bytes = context.contentResolver.openInputStream(uri)?.use { inp ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = inp.read(buf); if (n < 0) break
                total += n; if (total > MAX_BYTES) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return null
        var ext = detect(bytes)
        if (ext == null || ext == "heic") {
            // Unknown or HEIC: re-encode for portability (FORMAT.md §2.1).
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val oriented = if (ext == "heic") ImageStore.applyOrientation(bmp, orientation(bytes)) else bmp
            val out = ByteArrayOutputStream()
            if (oriented.hasAlpha()) { oriented.compress(Bitmap.CompressFormat.PNG, 100, out); ext = "png" }
            else { oriented.compress(Bitmap.CompressFormat.JPEG, 95, out); ext = "jpg" }
            oriented.recycle()
            bytes = out.toByteArray()
        }
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return null
        val o = if (ext == "jpg" || ext == "webp") orientation(bytes) else ExifInterface.ORIENTATION_NORMAL
        val swap = o == ExifInterface.ORIENTATION_ROTATE_90 || o == ExifInterface.ORIENTATION_ROTATE_270 ||
            o == ExifInterface.ORIENTATION_TRANSPOSE || o == ExifInterface.ORIENTATION_TRANSVERSE
        val ref = store.importAsset(projectId, bytes, ext!!)
        return ImportedImage(ref, if (swap) b.outHeight else b.outWidth, if (swap) b.outWidth else b.outHeight)
    }

    private fun orientation(bytes: ByteArray) = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }

    fun detect(b: ByteArray): String? {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return "png"
        if (at(0) == 0xFF && at(1) == 0xD8) return "jpg"
        if (at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code) return "gif"
        if (b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP") return "webp"
        if (b.size > 12 && String(b, 4, 4, Charsets.US_ASCII) == "ftyp") {
            val brand = String(b, 8, 4, Charsets.US_ASCII)
            if (brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "heim", "heis")) return "heic"
        }
        return null
    }
}

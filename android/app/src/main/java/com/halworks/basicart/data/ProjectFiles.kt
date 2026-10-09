package com.halworks.basicart.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.halworks.basicart.BuildConfig
import com.halworks.basicart.model.ImportReason
import com.halworks.basicart.model.ImportResult
import com.halworks.basicart.model.ProjectPackage
import java.io.File
import java.util.UUID

/** Android side of project packages (specs §11a): building, saving, sharing and importing .zip files. */
object ProjectFiles {
    const val MIME = "application/zip"

    /** Builds `<name>.zip` in the cache (off the main thread). */
    fun buildZip(app: AppContainer, projectId: String, projectName: String, progress: (Float) -> Unit = {}): File {
        val dir = File(app.app.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles { f -> f.name.endsWith(".zip") }?.forEach { it.delete() }
        val out = File(dir, ProjectPackage.fileName(projectName))
        out.outputStream().use { ProjectPackage.export(app.store.dir(projectId), it, "android", BuildConfig.VERSION_NAME, progress = progress) }
        return out
    }

    /** API 29+: MediaStore Downloads. Returns a user-facing location. */
    fun saveToDownloads(context: Context, zip: File): String {
        check(Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, zip.name)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create the file")
        try {
            r.openOutputStream(uri)?.use { o -> zip.inputStream().use { it.copyTo(o) } } ?: error("Couldn't write")
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); r.update(uri, values, null, null)
        } catch (e: Exception) { r.delete(uri, null, null); throw e }
        return "Downloads/${Exporter.actualName(context, uri) ?: zip.name}"
    }

    /** SAF fallback (API 26–28): write into the document the user created. */
    fun writeTo(context: Context, zip: File, target: Uri) {
        context.contentResolver.openOutputStream(target)?.use { o -> zip.inputStream().use { it.copyTo(o) } } ?: error("Couldn't write")
    }

    fun shareIntent(context: Context, zip: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".exports", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share project file")
    }

    /** Copies the incoming document to the cache, then runs the §13.2 import. Null: the file couldn't be read at all. */
    fun import(app: AppContainer, uri: Uri): ImportResult? {
        if (uri.scheme != "content") return null
        val tmp = File(app.app.cacheDir, "import-${UUID.randomUUID()}.zip")
        val input = try { app.app.contentResolver.openInputStream(uri) } catch (e: Exception) {
            android.util.Log.w("Import", "Can't open $uri", e); null
        } ?: return null
        try {
            val limit = (1L shl 30) + (64L shl 20)
            input.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        total += n; if (total > limit) return ImportResult.Rejected(ImportReason.TOO_LARGE)
                        out.write(buf, 0, n)
                    }
                }
            }
            return ProjectPackage.import(tmp, app.store.root, app.store.names())
        } catch (e: Exception) {
            android.util.Log.w("Import", "Import failed", e)
            return ImportResult.Rejected(ImportReason.CORRUPT_ZIP)
        } finally {
            tmp.delete()
        }
    }

    /** Friendly message per reason (§13.2). */
    fun message(r: ImportReason): String = when (r) {
        ImportReason.NOT_A_PACKAGE -> "This isn't a Basic Art project file."
        ImportReason.NEWER_VERSION -> "This project was made with a newer version of Basic Art. Update Basic Art to open this project."
        ImportReason.CORRUPT_ZIP, ImportReason.CORRUPT_PROJECT -> "This file looks damaged and can't be imported."
        ImportReason.ENCRYPTED -> "This file is password-protected and can't be imported."
        ImportReason.TOO_LARGE, ImportReason.COMPRESSION_RATIO, ImportReason.TOO_MANY_ENTRIES ->
            "This file is too large or unusually packed, so it wasn't imported."
        ImportReason.UNSAFE_PATH, ImportReason.SYMLINK, ImportReason.DUPLICATE_ENTRY, ImportReason.UNEXPECTED_ENTRY ->
            "This file contains things a Basic Art project shouldn't, so it wasn't imported."
    }
}

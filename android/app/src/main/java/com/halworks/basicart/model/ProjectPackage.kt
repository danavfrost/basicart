package com.halworks.basicart.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Why an import was refused (FORMAT.md §13.2 reason codes). */
enum class ImportReason(val code: String) {
    CORRUPT_ZIP("corruptZip"), UNSAFE_PATH("unsafePath"), SYMLINK("symlink"), DUPLICATE_ENTRY("duplicateEntry"),
    UNEXPECTED_ENTRY("unexpectedEntry"), ENCRYPTED("encrypted"), TOO_MANY_ENTRIES("tooManyEntries"),
    TOO_LARGE("tooLarge"), COMPRESSION_RATIO("compressionRatio"), NOT_A_PACKAGE("notAPackage"),
    NEWER_VERSION("newerVersion"), CORRUPT_PROJECT("corruptProject"),
}

sealed interface ImportResult {
    data class Ok(val projectId: String, val name: String) : ImportResult
    data class Rejected(val reason: ImportReason) : ImportResult
}

private class Reject(val reason: ImportReason) : Exception(reason.code)

/**
 * Project package (.zip) export and import (FORMAT.md §13). Pure java.io/zip so it is
 * unit-testable; Android code only handles pickers, MediaStore and sharing.
 */
object ProjectPackage {
    const val PACKAGE_VERSION = 1
    const val MANIFEST = "basicart-package.json"
    private const val MAX_ENTRIES = 10_000
    private const val MAX_TOTAL = 1L shl 30
    private const val MAX_ENTRY = 256L shl 20
    private const val RATIO_MIN_SIZE = 1L shl 20
    private const val MAX_RATIO = 100
    private val ASSET = Regex("^assets/[0-9a-f]{64}\\.(png|jpg|webp|gif|heic)$")
    private val STROKES = Regex("^strokes/[A-Za-z0-9_-]{1,64}(\\.mask)?\\.json$")
    private val FIXED = setOf(MANIFEST, "project.json", "thumb.png", "editor-state.json", "assets/", "strokes/")

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** `<name>.zip` with unsafe characters replaced (§13.1). */
    fun fileName(projectName: String): String {
        val cleaned = projectName.map { c -> if (c in "/\\:*?\"<>|" || c.code < 32 || c.code == 127) '_' else c }
            .joinToString("").trim().take(80)
        return (cleaned.ifEmpty { "Basic Art project" }) + ".zip"
    }

    // ================================================================ export

    /**
     * Writes the saved project folder [dir] as a package. Only referenced assets and the
     * strokes/masks of existing layers are included; never tmp/corrupt/bak files.
     */
    fun export(dir: File, out: OutputStream, appPlatform: String, appVersion: String, now: String = Timestamps.now(), progress: (Float) -> Unit = {}) {
        val projectText = File(dir, "project.json").readText()
        val doc = (ProjectCodec.load(projectText, dir.name) as? LoadResult.Ok)?.doc
            ?: throw IllegalStateException("Project can't be read")
        val files = ArrayList<Pair<String, File>>()
        doc.layers.filterIsInstance<ImageLayer>().map { it.assetRef }.distinct().forEach { ref ->
            File(dir, "assets/$ref").takeIf { it.isFile }?.let { files.add("assets/$ref" to it) }
        }
        for (l in doc.layers) {
            val name = if (l is DrawingLayer) "${l.id}.json" else "${l.id}.mask.json"
            File(dir, "strokes/$name").takeIf { it.isFile }?.let { files.add("strokes/$name" to it) }
        }
        File(dir, "thumb.png").takeIf { it.isFile }?.let { files.add("thumb.png" to it) }
        File(dir, "editor-state.json").takeIf { it.isFile }?.let { files.add("editor-state.json" to it) }
        ZipOutputStream(out, Charsets.UTF_8).use { zip ->
            val manifest = buildJsonObject {
                put("package", "basicart-project"); put("packageVersion", PACKAGE_VERSION)
                put("formatVersion", FORMAT_VERSION); put("appPlatform", appPlatform)
                put("appVersion", appVersion); put("exported", now)
            }
            fun putBytes(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            putBytes(MANIFEST, pretty.encodeToString(JsonElement.serializer(), manifest).toByteArray())
            putBytes("project.json", projectText.toByteArray())
            files.forEachIndexed { i, (name, f) ->
                zip.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                progress((i + 1f) / files.size)
            }
        }
    }

    // ================================================================ central directory

    class CdEntry(val name: String, val flags: Int, val method: Int, val compressed: Long, val size: Long, val madeBy: Int, val extAttrs: Long)

    /** Parses the ZIP central directory (with ZIP64) without trusting or extracting anything. */
    fun centralDirectory(file: File): List<CdEntry> {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len < 22) throw Reject(ImportReason.CORRUPT_ZIP)
            val tailLen = minOf(len, 65_557L).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(len - tailLen); raf.readFully(tail)
            var eocd = -1
            for (i in tailLen - 22 downTo 0) if (u32(tail, i) == 0x06054b50L) { eocd = i; break }
            if (eocd < 0) throw Reject(ImportReason.CORRUPT_ZIP)
            var count = u16(tail, eocd + 10).toLong()
            var cdSize = u32(tail, eocd + 12)
            var cdOffset = u32(tail, eocd + 16)
            // ZIP64 end-of-central-directory locator just before the EOCD
            val locAt = eocd - 20
            if ((count == 0xFFFFL || cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) && locAt >= 0 && u32(tail, locAt) == 0x07064b50L) {
                val z64Off = u64(tail, locAt + 8)
                val z = ByteArray(56); raf.seek(z64Off); raf.readFully(z)
                if (u32(z, 0) != 0x06064b50L) throw Reject(ImportReason.CORRUPT_ZIP)
                count = u64(z, 32); cdSize = u64(z, 40); cdOffset = u64(z, 48)
            }
            if (count > MAX_ENTRIES) throw Reject(ImportReason.TOO_MANY_ENTRIES)
            if (cdOffset + cdSize > len || cdSize > 64L shl 20) throw Reject(ImportReason.CORRUPT_ZIP)
            val cd = ByteArray(cdSize.toInt()); raf.seek(cdOffset); raf.readFully(cd)
            val out = ArrayList<CdEntry>()
            var p = 0
            for (n in 0 until count) {
                if (p + 46 > cd.size || u32(cd, p) != 0x02014b50L) throw Reject(ImportReason.CORRUPT_ZIP)
                val madeBy = u16(cd, p + 4)
                val flags = u16(cd, p + 8)
                val method = u16(cd, p + 10)
                var comp = u32(cd, p + 20); var size = u32(cd, p + 24)
                val nameLen = u16(cd, p + 28); val extraLen = u16(cd, p + 30); val commentLen = u16(cd, p + 32)
                val ext = u32(cd, p + 38)
                if (p + 46 + nameLen + extraLen + commentLen > cd.size) throw Reject(ImportReason.CORRUPT_ZIP)
                val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                // ZIP64 extra field (id 1) for sizes
                var e = p + 46 + nameLen
                val eEnd = e + extraLen
                while (e + 4 <= eEnd) {
                    val id = u16(cd, e); val sz = u16(cd, e + 2)
                    if (id == 1) {
                        var q = e + 4
                        if (size == 0xFFFFFFFFL && q + 8 <= e + 4 + sz) { size = u64(cd, q); q += 8 }
                        if (comp == 0xFFFFFFFFL && q + 8 <= e + 4 + sz) { comp = u64(cd, q) }
                    }
                    e += 4 + sz
                }
                out.add(CdEntry(name, flags, method, comp, size, madeBy, ext))
                p += 46 + nameLen + extraLen + commentLen
            }
            return out
        }
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong()) or (u16(b, i + 2).toLong() shl 16)
    private fun u64(b: ByteArray, i: Int) = u32(b, i) or (u32(b, i + 4) shl 32)

    private fun skipped(name: String) = name.startsWith("__MACOSX/") || name == ".DS_Store" || name.endsWith("/.DS_Store")

    /** Step 2: checks the central directory before anything is extracted. */
    fun prescan(entries: List<CdEntry>): ImportReason? {
        if (entries.size > MAX_ENTRIES) return ImportReason.TOO_MANY_ENTRIES
        val seen = HashSet<String>()
        var total = 0L
        for (e in entries) {
            val n = e.name
            if (n.startsWith("/") || n.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(n) || n.contains('\\') ||
                n.contains('\u0000') || n.split('/').let { segs -> segs.any { it == ".." } || segs.dropLast(1).any { it.isEmpty() } }) {
                return ImportReason.UNSAFE_PATH
            }
            val unix = (e.madeBy shr 8) == 3
            if (unix && ((e.extAttrs ushr 16) and 0xF000L) == 0xA000L) return ImportReason.SYMLINK
            if (skipped(n)) continue
            val key = n.removeSuffix("/").lowercase()
            if (!seen.add(key)) return ImportReason.DUPLICATE_ENTRY
            if (n !in FIXED && !ASSET.matches(n) && !STROKES.matches(n)) return ImportReason.UNEXPECTED_ENTRY
            if (e.flags and 1 != 0) return ImportReason.ENCRYPTED
            if (e.size > MAX_ENTRY) return ImportReason.TOO_LARGE
            total += e.size
            if (total > MAX_TOTAL) return ImportReason.TOO_LARGE
            if (e.size >= RATIO_MIN_SIZE && e.size > MAX_RATIO * maxOf(1L, e.compressed)) return ImportReason.COMPRESSION_RATIO
        }
        return null
    }

    // ================================================================ import

    /**
     * Imports [zip] into [projectsRoot] as a brand-new project (§13.2). Nothing is left
     * behind on failure. Existing names get " (2)", " (3)"…
     */
    fun import(zip: File, projectsRoot: File, existingNames: Set<String>, now: String = Timestamps.now()): ImportResult {
        val temp = File(projectsRoot, ".import-" + UUID.randomUUID().toString().replace("-", "").take(16))
        try {
            val entries = try { centralDirectory(zip) } catch (r: Reject) { throw r } catch (e: Exception) { throw Reject(ImportReason.CORRUPT_ZIP) }
            prescan(entries)?.let { throw Reject(it) }
            val zf = try { ZipFile(zip) } catch (e: Exception) { throw Reject(ImportReason.CORRUPT_ZIP) }
            zf.use { z ->
                // Step 3: manifest
                val me = z.getEntry(MANIFEST) ?: throw Reject(ImportReason.NOT_A_PACKAGE)
                val manifest = try {
                    Json.parseToJsonElement(readLimited(z, me, 1 shl 20).decodeToString()) as? JsonObject
                } catch (e: Reject) { throw e } catch (e: Exception) { null } ?: throw Reject(ImportReason.NOT_A_PACKAGE)
                if ((manifest["package"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "basicart-project") throw Reject(ImportReason.NOT_A_PACKAGE)
                val pv = (manifest["packageVersion"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                val fv = (manifest["formatVersion"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                if (pv == null || fv == null) throw Reject(ImportReason.NOT_A_PACKAGE)
                if (pv > PACKAGE_VERSION || fv > ProjectCodec.SUPPORTED) throw Reject(ImportReason.NEWER_VERSION)

                // Step 4: extract, counting real bytes
                temp.mkdirs()
                var total = 0L
                for (e in entries) {
                    if (skipped(e.name) || e.name.endsWith("/") || e.name == MANIFEST) continue
                    val ze = z.getEntry(e.name) ?: continue
                    val target = File(temp, e.name)
                    if (!target.canonicalPath.startsWith(temp.canonicalPath + File.separator)) throw Reject(ImportReason.UNSAFE_PATH)
                    target.parentFile?.mkdirs()
                    var written = 0L
                    z.getInputStream(ze).use { inp ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val r = inp.read(buf); if (r < 0) break
                                written += r; total += r
                                if (written > MAX_ENTRY || total > MAX_TOTAL) throw Reject(ImportReason.TOO_LARGE)
                                if (written >= RATIO_MIN_SIZE && written > MAX_RATIO * maxOf(1L, e.compressed)) throw Reject(ImportReason.COMPRESSION_RATIO)
                                out.write(buf, 0, r)
                            }
                        }
                    }
                }

                // Step 5: classify project.json
                val pj = File(temp, "project.json")
                if (!pj.isFile) throw Reject(ImportReason.CORRUPT_PROJECT)
                val text = pj.readText()
                when (ProjectCodec.load(text, "x")) {
                    is LoadResult.NewerVersion -> throw Reject(ImportReason.NEWER_VERSION)
                    is LoadResult.Corrupt -> throw Reject(ImportReason.CORRUPT_PROJECT)
                    is LoadResult.Ok -> {}
                }
                val root = Json.parseToJsonElement(text) as JsonObject
                if ((root["formatVersion"] as? JsonPrimitive)?.content?.toDoubleOrNull() != fv) throw Reject(ImportReason.CORRUPT_PROJECT)
                // assets whose bytes don't match their name are removed (render as missing)
                File(temp, "assets").listFiles()?.forEach { f ->
                    if (sha256(f) != f.name.substringBefore('.')) f.delete()
                }

                // Step 6: fresh id, modified = now, unique name
                val newId = UUID.randomUUID().toString()
                val baseName = (root["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(100)?.ifEmpty { null } ?: "Untitled"
                val name = uniqueName(baseName, existingNames)
                val rewritten = JsonObject(root.toMutableMap().apply {
                    put("id", JsonPrimitive(newId)); put("modified", JsonPrimitive(now)); put("name", JsonPrimitive(name))
                })
                ProjectStore.atomicWrite(pj, pretty.encodeToString(JsonElement.serializer(), rewritten).toByteArray())

                // Step 7: atomic rename into place (thumbnail is regenerated by the app if missing)
                val dest = File(projectsRoot, newId)
                if (!temp.renameTo(dest)) throw Reject(ImportReason.CORRUPT_ZIP)
                return ImportResult.Ok(newId, name)
            }
        } catch (r: Reject) {
            temp.deleteRecursively()
            return ImportResult.Rejected(r.reason)
        } catch (e: Exception) {
            temp.deleteRecursively()
            return ImportResult.Rejected(ImportReason.CORRUPT_ZIP)
        }
    }

    fun uniqueName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var i = 2
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }

    private fun readLimited(z: ZipFile, e: ZipEntry, limit: Int): ByteArray {
        z.getInputStream(e).use { inp ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val r = inp.read(buf); if (r < 0) break
                out.write(buf, 0, r)
                if (out.size() > limit) throw Reject(ImportReason.TOO_LARGE)
            }
            return out.toByteArray()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp -> val buf = ByteArray(64 * 1024); while (true) { val r = inp.read(buf); if (r < 0) break; md.update(buf, 0, r) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Deletes leftover `projects/.import-*` folders (call on app start). */
    fun cleanupLeftovers(projectsRoot: File) {
        projectsRoot.listFiles { f -> f.isDirectory && f.name.startsWith(".import-") }?.forEach { it.deleteRecursively() }
    }
}

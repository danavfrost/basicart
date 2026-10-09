package com.halworks.basicart.model

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** One home-grid entry. */
sealed interface ProjectEntry {
    val id: String
    val dir: File
    val thumb: File get() = File(dir, "thumb.png")

    data class Ok(
        override val id: String, override val dir: File, val name: String,
        val modified: String, val modifiedMillis: Long, val width: Int, val height: Int,
    ) : ProjectEntry

    data class Newer(override val id: String, override val dir: File, val name: String?, val modifiedMillis: Long) : ProjectEntry
    data class Corrupt(override val id: String, override val dir: File, val modifiedMillis: Long) : ProjectEntry
}

val ProjectEntry.sortKey: Long
    get() = when (this) {
        is ProjectEntry.Ok -> modifiedMillis
        is ProjectEntry.Newer -> modifiedMillis
        is ProjectEntry.Corrupt -> modifiedMillis
    }

/**
 * File storage for projects (FORMAT.md §2, §10.4). Pure java.io so it is unit-testable.
 * Every write goes through write-temp → fsync → rename.
 */
class ProjectStore(val root: File, private val generator: String = "Basic Art Android") {

    init { root.mkdirs() }

    fun dir(id: String) = File(root, id)

    fun list(): List<ProjectEntry> {
        // Folders starting with "." (e.g. in-progress imports) are not projects.
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return dirs.map { classify(it) }.sortedByDescending { it.sortKey }
    }

    fun classify(d: File): ProjectEntry {
        val pj = File(d, "project.json")
        val mtime = if (pj.exists()) pj.lastModified() else d.lastModified()
        val text = try { pj.readText() } catch (e: Exception) { return ProjectEntry.Corrupt(d.name, d, mtime) }
        return when (val r = ProjectCodec.load(text, d.name)) {
            is LoadResult.Ok -> ProjectEntry.Ok(
                d.name, d, r.doc.name, r.doc.modified,
                Timestamps.parseMillis(r.doc.modified) ?: mtime, r.doc.canvas.width, r.doc.canvas.height,
            )
            is LoadResult.NewerVersion -> ProjectEntry.Newer(d.name, d, r.name, mtime)
            is LoadResult.Corrupt -> ProjectEntry.Corrupt(d.name, d, mtime)
        }
    }

    /**
     * Loads a project with its strokes. Only for openable projects does it clean `*.tmp`
     * leftovers; newer/corrupt folders are never modified.
     */
    fun load(id: String): LoadResult {
        val d = dir(id)
        val text = try { File(d, "project.json").readText() } catch (e: Exception) {
            return LoadResult.Corrupt("project.json missing")
        }
        val r = ProjectCodec.load(text, id)
        if (r !is LoadResult.Ok) return r
        d.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.forEach { it.delete() }
        val layers = r.doc.layers.map { l ->
            if (l is DrawingLayer) attachStrokes(d, l) else attachMask(d, l)
        }
        return LoadResult.Ok(r.doc.copy(layers = layers))
    }

    /** Load without touching the folder (no *.tmp cleanup) — for reading another app's output. */
    fun loadReadOnly(id: String): LoadResult {
        val d = dir(id)
        val text = try { File(d, "project.json").readText() } catch (e: Exception) { return LoadResult.Corrupt("project.json missing") }
        val r = ProjectCodec.load(text, id) as? LoadResult.Ok ?: return ProjectCodec.load(text, id)
        return LoadResult.Ok(r.doc.copy(layers = r.doc.layers.map { l -> if (l is DrawingLayer) attachStrokes(d, l) else attachMask(d, l) }))
    }

    private fun attachStrokes(d: File, l: DrawingLayer): DrawingLayer {
        val f = File(d, "strokes/${l.id}.json")
        if (!f.exists()) return l.copy(strokes = emptyList(), strokesState = StrokesState.MISSING)
        val text = try { f.readText() } catch (e: Exception) { null }
            ?: return l.copy(strokes = emptyList(), strokesState = StrokesState.CORRUPT)
        return when (val s = ProjectCodec.readStrokes(text)) {
            is ProjectCodec.StrokesResult.Ok -> l.copy(strokes = s.strokes, strokesState = StrokesState.OK)
            ProjectCodec.StrokesResult.Invalid -> l.copy(strokes = emptyList(), strokesState = StrokesState.CORRUPT)
        }
    }

    /** Non-drawing layers: eraser mask from strokes/<id>.mask.json (§9.1). Problems → no mask. */
    private fun attachMask(d: File, l: Layer): Layer {
        val f = File(d, "strokes/${l.id}.mask.json")
        if (!f.exists()) return l
        val text = try { f.readText() } catch (e: Exception) { null }
        val r = text?.let { ProjectCodec.readMask(it) } ?: ProjectCodec.StrokesResult.Invalid
        return when (r) {
            is ProjectCodec.StrokesResult.Ok -> l.withBase(l.base.copy(mask = r.strokes, maskState = StrokesState.OK))
            ProjectCodec.StrokesResult.Invalid -> l.withBase(l.base.copy(mask = emptyList(), maskState = StrokesState.CORRUPT))
        }
    }

    /** Remembers what was last written for each drawing layer so unchanged strokes aren't rewritten. */
    private val savedStrokes = HashMap<String, List<Stroke>>()

    fun markStrokesSaved(doc: Document) {
        doc.layers.forEach {
            if (it is DrawingLayer) {
                if (it.strokesState != StrokesState.CORRUPT) savedStrokes[doc.id + "/" + it.id] = it.strokes
            } else if (it.base.maskState != StrokesState.CORRUPT) {
                savedStrokes[doc.id + "/" + it.id + ".mask"] = it.base.mask
            }
        }
    }

    /**
     * Atomic save (§10.4): strokes then project.json. Returns the document with the
     * updated `modified` timestamp.
     */
    @Synchronized
    fun save(doc: Document, now: String = Timestamps.now()): Document {
        val d = dir(doc.id)
        d.mkdirs()
        val sd = File(d, "strokes")
        for (l in doc.layers.filterIsInstance<DrawingLayer>()) {
            val key = doc.id + "/" + l.id
            val f = File(sd, "${l.id}.json")
            if (l.strokesState == StrokesState.CORRUPT && f.exists() && savedStrokes[key] == null) {
                f.renameTo(File(sd, "${l.id}.json.corrupt"))
            }
            if (savedStrokes[key] === l.strokes && f.exists()) continue
            if (l.strokes.isEmpty() && !f.exists()) { savedStrokes[key] = l.strokes; continue }
            sd.mkdirs()
            atomicWrite(f, ProjectCodec.writeStrokes(l).toByteArray(Charsets.UTF_8))
            savedStrokes[key] = l.strokes
        }
        for (l in doc.layers) {
            if (l is DrawingLayer) continue
            val key = doc.id + "/" + l.id + ".mask"
            val f = File(sd, "${l.id}.mask.json")
            if (l.base.maskState == StrokesState.CORRUPT && f.exists() && savedStrokes[key] == null) {
                f.renameTo(File(sd, "${l.id}.mask.json.corrupt"))
            }
            if (savedStrokes[key] === l.base.mask && (f.exists() || l.base.mask.isEmpty())) continue
            if (l.base.mask.isEmpty()) {
                // Mask fully undone: no file = no mask.
                if (f.exists()) f.delete()
            } else {
                sd.mkdirs()
                atomicWrite(f, ProjectCodec.writeMask(l).toByteArray(Charsets.UTF_8))
            }
            savedStrokes[key] = l.base.mask
        }
        val out = doc.copy(modified = now, created = doc.created.ifEmpty { now })
        atomicWrite(File(d, "project.json"), ProjectCodec.write(out, generator).toByteArray(Charsets.UTF_8))
        return out
    }

    /** editor-state.json (FORMAT.md §10.7): written atomically; never touches `modified`. */
    fun writeEditorState(id: String, json: String) {
        val d = dir(id)
        if (!d.exists()) return
        atomicWrite(File(d, "editor-state.json"), json.toByteArray(Charsets.UTF_8))
    }

    fun readEditorState(id: String): EditorStateDoc =
        EditorStateDoc.parse(try { File(dir(id), "editor-state.json").takeIf { it.isFile }?.readText() } catch (e: Exception) { null })

    fun writeThumb(id: String, png: ByteArray) {
        val d = dir(id)
        if (!d.exists()) return
        atomicWrite(File(d, "thumb.png"), png)
    }

    /** Stores an imported image (write-once, content addressed). Returns the assetRef. */
    fun importAsset(projectId: String, bytes: ByteArray, ext: String): String {
        val name = sha256(bytes) + "." + ext
        val f = File(dir(projectId), "assets/$name")
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            atomicWrite(f, bytes)
        }
        return name
    }

    fun assetFile(projectId: String, assetRef: String) = File(dir(projectId), "assets/$assetRef")

    fun delete(id: String): Boolean {
        val d = dir(id)
        savedStrokes.keys.removeAll { it.startsWith("$id/") }
        return d.deleteRecursively()
    }

    fun clearAll() {
        savedStrokes.clear()
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun names(): Set<String> = list().mapNotNull { (it as? ProjectEntry.Ok)?.name }.toSet()

    fun rename(id: String, newName: String): Boolean {
        val r = load(id) as? LoadResult.Ok ?: return false
        markStrokesSaved(r.doc)
        save(r.doc.copy(name = newName.trim().take(100).ifEmpty { "Untitled" }))
        return true
    }

    /** Copies a project folder under a new id and name. */
    fun duplicate(id: String, newName: String): String? {
        val r = load(id) as? LoadResult.Ok ?: return null
        val newId = UUID.randomUUID().toString()
        val nd = dir(newId)
        val od = dir(id)
        File(od, "assets").takeIf { it.exists() }?.copyRecursively(File(nd, "assets"))
        File(od, "strokes").takeIf { it.exists() }?.copyRecursively(File(nd, "strokes"))
        File(od, "thumb.png").takeIf { it.exists() }?.copyTo(File(nd, "thumb.png"))
        val now = Timestamps.now()
        val doc = r.doc.copy(id = newId, name = newName.take(100), created = now)
        markStrokesSaved(doc)
        save(doc, now)
        return newId
    }

    /** Deletes asset/strokes files no longer referenced by any of [docs] (editor close only). */
    fun collectGarbage(id: String, docs: List<Document>) {
        val d = dir(id)
        val assets = docs.flatMap { it.layers }.filterIsInstance<ImageLayer>().map { it.assetRef }.toSet()
        val keep = docs.flatMap { it.layers }.flatMap { l ->
            if (l is DrawingLayer) listOf("${l.id}.json") else listOf("${l.id}.mask.json")
        }.toSet()
        File(d, "assets").listFiles()?.forEach { if (it.name !in assets) it.delete() }
        File(d, "strokes").listFiles()?.forEach { if (it.name.endsWith(".json") && it.name !in keep) it.delete() }
    }

    companion object {
        fun atomicWrite(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // renameTo can fail on some filesystems when the target exists.
                target.delete()
                if (!tmp.renameTo(target)) throw java.io.IOException("rename failed for $target")
            }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

object Timestamps {
    private val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(java.time.ZoneOffset.UTC)

    fun now(): String = fmt.format(java.time.Instant.now())

    fun parseMillis(s: String): Long? = try {
        java.time.Instant.parse(s).toEpochMilli()
    } catch (e: Exception) { null }
}

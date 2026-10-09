package com.halworks.basicart.data

import java.io.File

/**
 * Files written to cache/exports for Share or Save (images, project zips). A share target may
 * read its file late, so they aren't deleted when the share sheet returns; instead old ones are
 * swept at startup, and a saved project zip is deleted as soon as the save completes.
 */
object ExportCache {
    const val MAX_AGE_MS = 60 * 60 * 1000L

    /** Deletes files in [dir] last modified more than [maxAgeMs] before [now]. Returns how many. */
    fun sweep(dir: File, now: Long = System.currentTimeMillis(), maxAgeMs: Long = MAX_AGE_MS): Int {
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (now - f.lastModified() > maxAgeMs && (if (f.isDirectory) f.deleteRecursively() else f.delete())) n++
        }
        return n
    }
}

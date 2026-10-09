package com.halworks.basicart.model

import com.halworks.basicart.data.ExportCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ExportCacheTest {
    @Test fun sweepDeletesOnlyFilesOlderThanAnHour() {
        val dir = Files.createTempDirectory("exports").toFile()
        val now = System.currentTimeMillis()
        val old = dir.resolve("Old.zip").apply { writeText("x"); setLastModified(now - 2 * 60 * 60 * 1000L) }
        val oldImg = dir.resolve("Old.png").apply { writeText("x"); setLastModified(now - 61 * 60 * 1000L) }
        val fresh = dir.resolve("Fresh.jpg").apply { writeText("x"); setLastModified(now - 5 * 60 * 1000L) }
        assertEquals(2, ExportCache.sweep(dir, now))
        assertFalse(old.exists()); assertFalse(oldImg.exists()); assertTrue(fresh.exists())
        // Missing folder is fine.
        assertEquals(0, ExportCache.sweep(dir.resolve("nope"), now))
        dir.deleteRecursively()
    }
}

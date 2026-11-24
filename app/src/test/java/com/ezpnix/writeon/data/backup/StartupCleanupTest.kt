package com.ezpnix.writeon.data.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SEC-11: a killed export/restore leaves plaintext note bodies in `cacheDir`; the startup sweep
 * removes only this app's old temp names.
 */
class StartupCleanupTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun file(name: String, ageMillis: Long): File = File(cacheDir, name).apply {
        writeText("plaintext note body")
        setLastModified(System.currentTimeMillis() - ageMillis)
    }

    private val cacheDir: File by lazy { temporaryFolder.newFolder("cache") }

    @Test
    fun `old producer temp files are removed and everything else is kept`() {
        val old = 60 * 60 * 1000L
        val portable = file("portable123.db", old)
        val export = file("writeon-export456.archive", old)
        val zipTemp = file("notes-backup-encrypted.zip.ziptmp", old)
        val part = file("something.part", old)
        val fresh = file("writeon-portable-fresh.db", 0)
        val unrelated = file("user-visible-cache.txt", old)
        val directory = File(cacheDir, "media").apply { mkdirs() }

        val removed = StartupCleanup.cleanupCacheTemps(
            cacheDir = cacheDir,
            now = System.currentTimeMillis(),
            maxAgeMillis = 60_000,
        )

        assertFalse(portable.exists())
        assertFalse(export.exists())
        assertFalse(zipTemp.exists())
        assertFalse(part.exists())
        assertTrue("a file created moments ago may be an in-flight worker", fresh.exists())
        assertTrue("unrelated cache files are not ours to delete", unrelated.exists())
        assertTrue(directory.isDirectory)
        assertEquals(4, removed)
    }

    @Test
    fun `a missing cache directory is not an error`() {
        val removed = StartupCleanup.cleanupCacheTemps(
            cacheDir = File(temporaryFolder.root, "does-not-exist"),
            now = System.currentTimeMillis(),
            maxAgeMillis = 0,
        )

        assertEquals(0, removed)
    }
}

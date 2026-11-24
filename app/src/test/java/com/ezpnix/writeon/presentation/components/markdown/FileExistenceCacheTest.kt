package com.ezpnix.writeon.presentation.components.markdown

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PERF-12: the short-lived `isFile` cache must be cheap without going stale in a way the UI can see.
 *
 * The clock and the existence probe are injected, so these cases run as pure JVM tests and can make
 * a cache copy disappear without touching a real file.
 */
class FileExistenceCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a positive answer is reused inside the ttl`() {
        var now = 1_000L
        var probes = 0
        val cache = FileExistenceCache(ttlMillis = 1_500L, now = { now }, exists = { probes++; true })

        assertTrue(cache.isFile("/cache/a.jpg"))
        now += 1_499
        assertTrue(cache.isFile("/cache/a.jpg"))

        assertEquals("one stat for two frames inside the window", 1, probes)
    }

    @Test
    fun `the answer is re-checked once the ttl elapses`() {
        var now = 1_000L
        var exists = true
        var probes = 0
        val cache = FileExistenceCache(ttlMillis = 1_500L, now = { now }, exists = { probes++; exists })

        assertTrue(cache.isFile("/cache/a.jpg"))
        exists = false
        now += 1_500

        assertFalse(cache.isFile("/cache/a.jpg"))
        assertEquals(2, probes)
    }

    @Test
    fun `invalidate forces a fresh probe`() {
        var exists = true
        var probes = 0
        val cache = FileExistenceCache(exists = { probes++; exists })

        assertTrue(cache.isFile("/cache/a.jpg"))
        exists = false
        cache.invalidate("/cache/a.jpg")

        assertFalse(cache.isFile("/cache/a.jpg"))
        assertEquals(2, probes)
    }

    @Test
    fun `clear forces a fresh probe for every path`() {
        var exists = true
        var probes = 0
        val cache = FileExistenceCache(exists = { probes++; exists })

        assertTrue(cache.isFile("/cache/a.jpg"))
        exists = false
        cache.clear()

        assertFalse(cache.isFile("/cache/a.jpg"))
        assertEquals(2, probes)
    }

    @Test
    fun `a backwards clock does not trust an answer of unknown age`() {
        var now = 10_000L
        var probes = 0
        val cache = FileExistenceCache(ttlMillis = 1_500L, now = { now }, exists = { probes++; true })

        assertTrue(cache.isFile("/cache/a.jpg"))
        now = 1_000L
        assertTrue(cache.isFile("/cache/a.jpg"))

        assertEquals(2, probes)
    }

    @Test
    fun `a deleted file is noticed after the ttl`() {
        val file = temporaryFolder.newFile("cached-copy.jpg")
        assertTrue(file.isFile)
        var now = 0L
        val cache = FileExistenceCache(ttlMillis = 1_500L, now = { now })

        assertTrue(cache.isFile(file.path))
        assertTrue("inside the window the cached answer stands", cache.isFile(file.path))
        assertTrue(file.delete())
        now += 1_500
        assertFalse(cache.isFile(file.path))
    }

    @Test
    fun `the default probe really looks at the file system`() {
        val present = temporaryFolder.newFile("present.jpg")
        val missing = File(temporaryFolder.root, "missing.jpg")
        val cache = FileExistenceCache()

        assertTrue(cache.isFile(present.path))
        assertFalse(cache.isFile(missing.path))
    }
}

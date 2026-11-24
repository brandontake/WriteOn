package com.ezpnix.writeon.data.local.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PERF-03: the row cache that lets an unchanged note skip decryption when Room re-emits the table.
 *
 * The cache trusts an entry only while `(id, updatedAt)` and both stored lengths still match; these
 * tests pin each of those invalidations, the explicit invalidation used by writes, and the bound.
 */
class NoteRowTextCacheTest {

    @Test
    fun `an entry is returned while the row bytes are unchanged`() {
        val cache = NoteRowTextCache()
        cache.put(1, updatedAt = 10, name = "cipher-name", description = "cipher-body", "name", "body")

        assertEquals(
            "name" to "body",
            cache.get(1, updatedAt = 10, name = "cipher-name", description = "cipher-body")
        )
    }

    @Test
    fun `a newer updatedAt misses`() {
        val cache = NoteRowTextCache()
        cache.put(1, updatedAt = 10, name = "n", description = "d", "name", "body")

        assertNull(cache.get(1, updatedAt = 11, name = "n", description = "d"))
    }

    @Test
    fun `a different stored length misses even when updatedAt is unchanged`() {
        val cache = NoteRowTextCache()
        cache.put(1, updatedAt = 10, name = "n", description = "d", "name", "body")

        assertNull(cache.get(1, updatedAt = 10, name = "n", description = "longer"))
    }

    @Test
    fun `invalidate drops the row`() {
        val cache = NoteRowTextCache()
        cache.put(1, updatedAt = 10, name = "n", description = "d", "name", "body")

        cache.invalidate(1)

        assertNull(cache.get(1, updatedAt = 10, name = "n", description = "d"))
    }

    @Test
    fun `clear drops every row`() {
        val cache = NoteRowTextCache()
        cache.put(1, 10, "n1", "d1", "name1", "body1")
        cache.put(2, 20, "n2", "d2", "name2", "body2")

        cache.clear()

        assertNull(cache.get(1, 10, "n1", "d1"))
        assertNull(cache.get(2, 20, "n2", "d2"))
    }

    @Test
    fun `the retained plaintext is bounded by bytes, not only by entries`() {
        val cache = NoteRowTextCache(maxEntries = 100)
        val largeBody = "x".repeat(2_500_000)

        cache.put(1, 1, "n1", "d1", "name1", largeBody)
        cache.put(2, 2, "n2", "d2", "name2", largeBody)

        assertNull("adding past the byte budget evicts the oldest body", cache.get(1, 1, "n1", "d1"))
        assertEquals("name2" to largeBody, cache.get(2, 2, "n2", "d2"))
    }

    @Test
    fun `the cache is bounded and evicts the least recently used row`() {
        val cache = NoteRowTextCache(maxEntries = 2)
        cache.put(1, 1, "n1", "d1", "name1", "body1")
        cache.put(2, 2, "n2", "d2", "name2", "body2")
        // Touching row 1 makes row 2 the least recently used entry.
        assertEquals("name1" to "body1", cache.get(1, 1, "n1", "d1"))
        cache.put(3, 3, "n3", "d3", "name3", "body3")

        assertNull("the least recently used entry is evicted", cache.get(2, 2, "n2", "d2"))
        assertEquals("name1" to "body1", cache.get(1, 1, "n1", "d1"))
        assertEquals("name3" to "body3", cache.get(3, 3, "n3", "d3"))
    }
}

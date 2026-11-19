package com.ezpnix.writeon.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the process-wide decrypt-failure registry (D3).
 *
 * The registry is a singleton, so every test starts by replacing both real scopes with an empty
 * refresh. That is the same public API the DAO uses; no test-only reset hook is needed.
 */
class DecryptFailureRegistryTest {

    @Before
    fun resetRegistry() {
        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_ACTIVE,
            emptySet(),
            emptySet()
        )
        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_TRASHED,
            emptySet(),
            emptySet()
        )
    }

    @Test
    fun `replaceScope unions two scopes without overwriting each other`() {
        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_ACTIVE,
            rowIds = setOf(1, 2),
            failedIds = setOf(1)
        )
        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_TRASHED,
            rowIds = setOf(3),
            failedIds = setOf(3)
        )

        assertEquals(setOf(1, 3), DecryptFailureRegistry.failedIds.value)
        assertEquals(2, DecryptFailureRegistry.failedCount.value)

        // Replacing one source must not erase the other source's contribution.
        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_ACTIVE,
            rowIds = setOf(1, 2),
            failedIds = emptySet()
        )
        assertEquals(setOf(3), DecryptFailureRegistry.failedIds.value)
    }

    @Test
    fun `markFailed records a single-row failure and markReadable clears it`() {
        DecryptFailureRegistry.markFailed(7)

        assertTrue(DecryptFailureRegistry.isFailed(7))
        assertEquals(setOf(7), DecryptFailureRegistry.failedIds.value)
        assertEquals(1, DecryptFailureRegistry.failedCount.value)

        DecryptFailureRegistry.markReadable(7)

        assertFalse(DecryptFailureRegistry.isFailed(7))
        assertTrue(DecryptFailureRegistry.failedIds.value.isEmpty())
        assertEquals(0, DecryptFailureRegistry.failedCount.value)
    }

    @Test
    fun `a row that becomes readable in a later full refresh is cleared`() {
        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_ACTIVE,
            rowIds = setOf(1, 2),
            failedIds = setOf(1, 2)
        )
        // The editor may also have seen one of them fail; a full refresh is authoritative.
        DecryptFailureRegistry.markFailed(2)
        assertEquals(setOf(1, 2), DecryptFailureRegistry.failedIds.value)

        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_ACTIVE,
            rowIds = setOf(1, 2),
            failedIds = emptySet()
        )

        assertTrue(
            "a row that decrypts again must leave the count on the next refresh",
            DecryptFailureRegistry.failedIds.value.isEmpty()
        )
        assertEquals(0, DecryptFailureRegistry.failedCount.value)
    }

    @Test
    fun `rows that disappear from a refresh are dropped`() {
        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_ACTIVE,
            rowIds = setOf(1, 2),
            failedIds = setOf(1, 2)
        )

        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_ACTIVE,
            rowIds = setOf(1),
            failedIds = setOf(1)
        )
        assertEquals(setOf(1), DecryptFailureRegistry.failedIds.value)

        // A single-row failure whose row no longer exists anywhere must not grow the count forever.
        DecryptFailureRegistry.markFailed(9)
        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_TRASHED,
            rowIds = emptySet(),
            failedIds = emptySet()
        )
        assertEquals(setOf(1), DecryptFailureRegistry.failedIds.value)

        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_ACTIVE,
            rowIds = emptySet(),
            failedIds = emptySet()
        )
        assertTrue(DecryptFailureRegistry.failedIds.value.isEmpty())
    }

    @Test
    fun `the same id failing in two scopes is counted once`() {
        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_ACTIVE,
            rowIds = setOf(1, 2),
            failedIds = setOf(1)
        )
        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_TRASHED,
            rowIds = setOf(1),
            failedIds = setOf(1)
        )
        // A single-row read of the same id must not add a second copy either.
        DecryptFailureRegistry.markFailed(1)

        assertEquals(setOf(1), DecryptFailureRegistry.failedIds.value)
        assertEquals(1, DecryptFailureRegistry.failedCount.value)
    }

    @Test
    fun `markReadable clears a failure that a scope reported`() {
        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_ACTIVE,
            rowIds = setOf(5),
            failedIds = setOf(5)
        )
        assertEquals(setOf(5), DecryptFailureRegistry.failedIds.value)

        DecryptFailureRegistry.markReadable(5)

        assertFalse(DecryptFailureRegistry.isFailed(5))
        assertTrue(DecryptFailureRegistry.failedIds.value.isEmpty())
    }

    @Test
    fun `failedCount and failedIds stay in sync through every transition`() {
        assertSynced()

        DecryptFailureRegistry.markFailed(1)
        assertSynced()

        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_ACTIVE,
            rowIds = setOf(1, 2),
            failedIds = setOf(1, 2)
        )
        assertSynced()

        DecryptFailureRegistry.markReadable(2)
        assertSynced()

        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_TRASHED,
            rowIds = setOf(3),
            failedIds = setOf(3)
        )
        assertSynced()

        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_ACTIVE,
            rowIds = emptySet(),
            failedIds = emptySet()
        )
        assertSynced()

        DecryptFailureRegistry.markFailed(9)
        assertSynced()

        DecryptFailureRegistry.replaceScope(
            DecryptFailureRegistry.SCOPE_TRASHED,
            rowIds = emptySet(),
            failedIds = emptySet()
        )
        assertSynced()
    }

    private fun assertSynced() {
        val ids = DecryptFailureRegistry.failedIds.value
        assertEquals(
            "the count must always be the size of the id set",
            ids.size,
            DecryptFailureRegistry.failedCount.value
        )
        ids.forEach { id ->
            assertTrue("isFailed must agree with failedIds for $id", DecryptFailureRegistry.isFailed(id))
        }
    }
}

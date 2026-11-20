package com.ezpnix.writeon.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DAT-7: the restore coordinator publishes exactly one transition per restore attempt.
 *
 * These tests are plain JVM on purpose: the coordinator is what the UI and the note collectors
 * observe, and it must be testable without a Room connection, a database swap or a process
 * restart. The listeners stand in for the two real subscribers (`MainActivity`'s overlay is a
 * StateFlow collector; `NoteUseCase` registers a pause and a reload listener).
 */
class RestoreCoordinatorTest {

    private var begins = 0
    private var ends = 0

    @Before
    fun setUp() {
        RestoreCoordinator.resetForTest()
        begins = 0
        ends = 0
    }

    @After
    fun tearDown() {
        // This is a process-wide object: leave the next test (and any Robolectric test in the same
        // JVM) with the default state instead of a half-finished restore.
        RestoreCoordinator.resetForTest()
    }

    @Test
    fun `begin twice publishes and notifies only once`() {
        RestoreCoordinator.onBegin { begins++ }
        RestoreCoordinator.onEnd { ends++ }

        RestoreCoordinator.begin()
        RestoreCoordinator.begin()

        assertTrue(RestoreCoordinator.restoring.value)
        assertEquals(1, begins)
        assertEquals(0, ends)
    }

    @Test
    fun `end twice publishes and notifies only once`() {
        RestoreCoordinator.onBegin { begins++ }
        RestoreCoordinator.onEnd { ends++ }

        RestoreCoordinator.begin()
        RestoreCoordinator.end()
        RestoreCoordinator.end()

        assertFalse(RestoreCoordinator.restoring.value)
        assertEquals(1, begins)
        assertEquals(1, ends)
    }

    @Test
    fun `a failed restore reopens and the next attempt is a new transition`() {
        RestoreCoordinator.onBegin { begins++ }
        RestoreCoordinator.onEnd { ends++ }

        // First attempt fails after begin: the coordinator must end it so the app becomes usable.
        RestoreCoordinator.begin()
        RestoreCoordinator.end()
        assertFalse(RestoreCoordinator.restoring.value)

        // A retry is not a duplicate of the first attempt: it gets its own transition.
        RestoreCoordinator.begin()
        assertTrue(RestoreCoordinator.restoring.value)

        assertEquals(2, begins)
        assertEquals(1, ends)
    }

    @Test
    fun `end without begin is a no-op`() {
        RestoreCoordinator.onBegin { begins++ }
        RestoreCoordinator.onEnd { ends++ }

        RestoreCoordinator.end()

        assertFalse(RestoreCoordinator.restoring.value)
        assertEquals(0, begins)
        assertEquals(0, ends)
    }

    @Test
    fun `listeners registered after a transition are not called for it`() {
        RestoreCoordinator.begin()
        RestoreCoordinator.onBegin { begins++ }
        RestoreCoordinator.onEnd { ends++ }

        RestoreCoordinator.end()

        assertEquals(0, begins)
        assertEquals(1, ends)
    }
}

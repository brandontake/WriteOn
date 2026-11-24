package com.ezpnix.writeon.core.util

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * REG-04: a superseded restore must not clear a newer restore's overlay.
 *
 * The token returned by [RestoreCoordinator.begin] is the only thing that may end that
 * transition; a stale failure path holding an older token is a no-op.
 */
class RestoreCoordinatorOwnershipTest {

    @Before
    fun setUp() {
        RestoreCoordinator.resetForTest()
    }

    @After
    fun tearDown() {
        RestoreCoordinator.resetForTest()
    }

    @Test
    fun `a stale token cannot end the current transition`() {
        val first = RestoreCoordinator.begin()
        assertNotNull(first)
        RestoreCoordinator.end(first)

        val second = RestoreCoordinator.begin()
        assertNotNull(second)
        assertTrue(RestoreCoordinator.restoring.value)

        RestoreCoordinator.end(first)

        assertTrue(
            "the superseded restore's failure must not uncover the new restore",
            RestoreCoordinator.restoring.value
        )

        RestoreCoordinator.end(second)
        assertFalse(RestoreCoordinator.restoring.value)
    }

    @Test
    fun `a begin while a transition is active issues no token`() {
        val owner = RestoreCoordinator.begin()
        assertNotNull(owner)

        val duplicate = RestoreCoordinator.begin()

        assertNull("a duplicate begin does not own the overlay", duplicate)
        RestoreCoordinator.end(duplicate)
        assertTrue(
            "ending with the null token must not clear the real owner's transition",
            RestoreCoordinator.restoring.value
        )

        RestoreCoordinator.end(owner)
        assertFalse(RestoreCoordinator.restoring.value)
    }

    @Test
    fun `the token-less end still works for the snapshot undo`() {
        RestoreCoordinator.begin()

        RestoreCoordinator.end()

        assertFalse(RestoreCoordinator.restoring.value)
    }
}

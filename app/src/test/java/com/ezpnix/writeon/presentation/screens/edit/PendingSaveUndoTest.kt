package com.ezpnix.writeon.presentation.screens.edit

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4-06/R5-06: the snackbar that carries "Note Saved - Undo" outlives the editor. When it times
 * out or is replaced, the stored action can never be invoked, and its closure holds the saved
 * note's plaintext. The generation token is what lets that dismissal clear its own slot without
 * erasing a newer save's action.
 */
class PendingSaveUndoTest {

    @After
    fun cleanup() {
        PendingSaveUndo.clear()
    }

    @Test
    fun `a stale dismissal does not clear a newer registration`() = runBlocking {
        var firstRuns = 0
        var secondRuns = 0
        val first = PendingSaveUndo.register(1) { firstRuns++ }
        PendingSaveUndo.register(2) { secondRuns++ }

        PendingSaveUndo.clearIf(first)

        assertTrue("the newer action must still run", PendingSaveUndo.undo())
        assertEquals("the stale snackbar must not have run its action", 0, firstRuns)
        assertEquals("the newer save is undone", 1, secondRuns)
        assertFalse("undo consumes the slot", PendingSaveUndo.undo())
    }

    @Test
    fun `the current generation's dismissal drops the plaintext closure`() = runBlocking {
        var runs = 0
        val token = PendingSaveUndo.register(3) { runs++ }

        PendingSaveUndo.clearIf(token)

        assertFalse("nothing is left to undo once the bar is gone", PendingSaveUndo.undo())
        assertEquals(0, runs)
    }

    @Test
    fun `another note's eviction does not clear this action`() = runBlocking {
        var runs = 0
        PendingSaveUndo.register(4) { runs++ }

        PendingSaveUndo.clearOwnedBy(5)
        assertTrue(PendingSaveUndo.undo())
        assertEquals(1, runs)
    }
}

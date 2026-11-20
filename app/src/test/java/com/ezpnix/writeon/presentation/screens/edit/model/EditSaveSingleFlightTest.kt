package com.ezpnix.writeon.presentation.screens.edit.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V-6: the gate every editor save path shares.
 *
 * The bug these pin down is not exotic: ON_STOP, the back press and Retry could each start a
 * `performSave`, and two transactions on the same fields inserted a new note twice and replaced the
 * undo point with a second snapshot. The loop case matters for a different reason - without the
 * re-run, text typed while the first write was committing was never written at all.
 */
class EditSaveSingleFlightTest {

    @Test
    fun `two callers arriving together share one execution`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SaveSingleFlight(scope)
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var runs = 0

            val first = flight.run {
                runs++
                entered.complete(Unit)
                gate.await()
                true
            }
            val second = flight.run {
                runs++
                false
            }

            // The second caller gets the write that is already running, not a transaction of its
            // own - that is the difference between one inserted row and two.
            assertSame(first, second)
            assertFalse(second.isCompleted)
            entered.await()
            assertEquals(1, runs)

            gate.complete(Unit)
            assertTrue(first.await())
            assertTrue(second.await())
            assertEquals(1, runs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a caller arriving during a run waits for it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SaveSingleFlight(scope)
            val gate = CompletableDeferred<Unit>()
            var runs = 0

            val running = flight.run {
                runs++
                gate.await()
                true
            }
            val waiting = flight.run {
                runs++
                false
            }

            // Nothing about the second call may progress the write; it only observes the first.
            gate.complete(Unit)
            assertTrue(waiting.await())
            assertTrue(running.await())
            assertEquals(1, runs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the slot is free again once a run finishes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SaveSingleFlight(scope)
            var runs = 0

            assertTrue(flight.run { runs++; true }.await())
            assertTrue(flight.run { runs++; true }.await())

            // A finished Deferred must not be handed out forever: the ON_STOP that follows a
            // committed back-save is exactly this sequence.
            assertEquals(2, runs)
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 5_000)
    fun `text typed during the write triggers a second run`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SaveSingleFlight(scope)
            var committed: Pair<String, String>? = null
            var fields = "title" to "first paragraph"
            var runs = 0

            val result = flight.runUntilCaughtUp(
                currentContent = { fields },
                committedContent = { committed },
                save = {
                    runs++
                    committed = fields
                    // The user keeps typing while Room commits the first version.
                    if (runs == 1) fields = "title" to "first paragraph, plus the last sentence"
                    true
                },
            )

            assertTrue(result)
            assertEquals(2, runs)
            assertEquals(fields, committed)
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 5_000)
    fun `a refused save is not retried in a loop`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SaveSingleFlight(scope)
            var runs = 0

            val result = flight.runUntilCaughtUp(
                currentContent = { "title" to "unlock me" },
                committedContent = { null },
                save = { runs++; false },
            )

            // A locked vault needs the user, not another transaction; retrying would spin.
            assertFalse(result)
            assertEquals(1, runs)
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 5_000)
    fun `a save that had nothing to write does not spin`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SaveSingleFlight(scope)
            var runs = 0

            val result = flight.runUntilCaughtUp(
                currentContent = { "" to "" },
                committedContent = { null },
                save = { runs++; true },
            )

            // Success without a commit is "nothing to store" (an empty new note). The loop stops so
            // the editor cannot spin on text that will never match a row that was never written.
            assertTrue(result)
            assertEquals(1, runs)
        } finally {
            scope.cancel()
        }
    }
}

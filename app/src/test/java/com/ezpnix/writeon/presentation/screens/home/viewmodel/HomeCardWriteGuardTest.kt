package com.ezpnix.writeon.presentation.screens.home.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R4-04: a Vault eviction between a card's read and its write used to be an uncaught
 * `IllegalStateException` on `Dispatchers.IO`, which crashes the process. The guard reports it and
 * lets the user retry; cancellation must still propagate.
 */
class HomeCardWriteGuardTest {

    @Test
    fun `a locked-vault failure is reported instead of thrown`() = runBlocking {
        val failure = IllegalStateException("Refusing to save a vault note while the vault is locked")
        var reported: Exception? = null

        val result = runGuardedCardWrite(
            onFailure = { reported = it },
            block = { throw failure },
        )

        assertEquals(null, result)
        assertSame("the guard hands the exact failure to the reporter", failure, reported)
    }

    @Test
    fun `a successful write is returned unchanged`() = runBlocking {
        var reported: Exception? = null

        val result = runGuardedCardWrite(
            onFailure = { reported = it },
            block = { 42 },
        )

        assertEquals(42, result)
        assertEquals(null, reported)
    }

    @Test
    fun `cancellation is not swallowed`() {
        var reported: Exception? = null
        try {
            runBlocking {
                runGuardedCardWrite(
                    onFailure = { reported = it },
                    block = { throw CancellationException("the editor was closed") },
                )
            }
            fail("cancellation must propagate to the caller")
        } catch (cancelled: CancellationException) {
            assertTrue(cancelled.message!!.contains("editor was closed"))
        }
        assertEquals("a cancelled save is not a reported failure", null, reported)
    }
}

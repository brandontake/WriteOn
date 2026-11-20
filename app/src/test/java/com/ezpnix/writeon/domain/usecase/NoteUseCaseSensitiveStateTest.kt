package com.ezpnix.writeon.domain.usecase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V-5: a lock has to beat a decrypt that is already running, not just the collector waiting on it.
 *
 * `clearSensitiveState()` cancels the collectors, but the decrypted list is produced inside a
 * `withContext(Dispatchers.Default)` block with no suspension point: cancellation arrives only
 * after that block returns, and the assignment that follows used to put the plaintext list back
 * into the state the lock had just emptied. `NoteLoadEpoch` is the guard, and these tests pin the
 * ordering rule directly - a database, a Keystore and a live collector would only obscure it.
 */
class NoteUseCaseSensitiveStateTest {

    @Test
    fun `a load that finishes after the lock does not publish`() = runBlocking {
        val epoch = NoteLoadEpoch()
        val published = mutableListOf<List<String>>()
        val decryptStarted = CompletableDeferred<Unit>()
        val finishDecrypt = CompletableDeferred<Unit>()

        val load = launch(Dispatchers.Default) {
            // The collector's capture before it hops to Default, then the CPU-bound part.
            val captured = epoch.current()
            decryptStarted.complete(Unit)
            finishDecrypt.await()
            if (epoch.isCurrent(captured)) published += listOf("decrypted body")
        }

        decryptStarted.await()
        // The lock: it runs while the decrypt above is still in flight.
        epoch.advance()
        finishDecrypt.complete(Unit)
        load.join()

        assertTrue("the post-lock decrypt must not republish plaintext", published.isEmpty())
    }

    @Test
    fun `a load that is not interrupted still publishes`() = runBlocking {
        val epoch = NoteLoadEpoch()
        val published = mutableListOf<List<String>>()
        val decryptStarted = CompletableDeferred<Unit>()
        val finishDecrypt = CompletableDeferred<Unit>()

        val load = launch(Dispatchers.Default) {
            val captured = epoch.current()
            decryptStarted.complete(Unit)
            finishDecrypt.await()
            if (epoch.isCurrent(captured)) published += listOf("decrypted body")
        }

        decryptStarted.await()
        finishDecrypt.complete(Unit)
        load.join()

        assertEquals(1, published.size)
    }

    @Test
    fun `advancing the generation invalidates every earlier capture`() {
        val epoch = NoteLoadEpoch()
        val captured = epoch.current()
        assertTrue(epoch.isCurrent(captured))

        epoch.advance()

        assertFalse(epoch.isCurrent(captured))
        assertTrue(epoch.isCurrent(epoch.current()))
    }
}

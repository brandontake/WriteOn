package com.ezpnix.writeon.presentation.screens.home.viewmodel

import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.presentation.screens.home.widgets.buildCardBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-09: a card checkbox tap is a read-modify-write against the latest body.
 *
 * The old handler rebuilt the note from the body captured when the card was composed, so two quick
 * taps both started from the same text and the last write won. The helper under test always reads
 * inside the same mutex the write is released by, which is what makes both taps survive; the fake
 * delays are there to make an unserialised implementation actually interleave.
 */
class HomeCheckboxToggleTest {

    @Test
    fun `two rapid toggles both survive in the stored body`() = runBlocking {
        val mutex = Mutex()
        var stored = Note(
            id = 1,
            name = "Card",
            description = "[ ] one\n[ ] two\n[ ] three",
        )

        suspend fun read(id: Int): Note? {
            // Snapshot before the delay: without the mutex both taps would read the original body
            // and the second write would drop the first tap, which is the bug being pinned.
            val snapshot = stored
            delay(5)
            return snapshot.takeIf { it.id == id }
        }

        suspend fun write(note: Note) {
            delay(5)
            stored = note
        }

        kotlinx.coroutines.coroutineScope {
            launch {
                toggleCheckboxInLatestBody(
                    noteId = 1,
                    lineNumber = 0,
                    text = "one",
                    checked = true,
                    mutex = mutex,
                    readLatest = { id -> read(id) },
                    write = { note -> write(note) },
                )
            }
            launch {
                toggleCheckboxInLatestBody(
                    noteId = 1,
                    lineNumber = 1,
                    text = "two",
                    checked = true,
                    mutex = mutex,
                    readLatest = { id -> read(id) },
                    write = { note -> write(note) },
                )
            }
        }

        assertEquals("[x] one\n[x] two\n[ ] three", stored.description)
    }

    @Test
    fun `a truncated card summary toggles the full stored body`() = runBlocking {
        val tail = (13..50).joinToString("\n") { "hidden-$it" }
        var stored = Note(id = 4, name = "Card", description = "[ ] one\n$tail")
        val summary = buildCardBody(stored.description)

        assertTrue("the card must be looking at a truncated summary", summary.isTruncated)
        assertTrue("the checkbox is inside the summary", summary.text.startsWith("[ ] one"))

        val toggled = toggleCheckboxInLatestBody(
            noteId = 4,
            lineNumber = 0,
            text = "one",
            checked = true,
            mutex = Mutex(),
            readLatest = { stored },
            write = { stored = it },
        )

        assertTrue(toggled)
        assertTrue(stored.description.startsWith("[x] one\n"))
        assertTrue("the lines the summary dropped must survive", stored.description.endsWith("hidden-50"))
        assertEquals("every line is still there", "[x] one\n$tail", stored.description)
    }

    @Test
    fun `a stale line leaves the body untouched`() = runBlocking {
        val mutex = Mutex()
        var stored = Note(id = 2, name = "Card", description = "[ ] changed")
        var writes = 0

        val toggled = toggleCheckboxInLatestBody(
            noteId = 2,
            lineNumber = 0,
            text = "one",
            checked = true,
            mutex = mutex,
            readLatest = { stored },
            write = { writes++; stored = it },
        )

        assertTrue("a stale request is not an error", !toggled)
        assertEquals(0, writes)
    }

    @Test
    fun `a card write waits for a flag-only write holding the shared lock`() = runBlocking {
        // R5-06: use the process-wide lock the production writers share, not a stand-in. This also
        // pins that Home and the widget are serialised against the editor's save commit.
        val mutex = com.ezpnix.writeon.core.util.NoteBodyEditLock.mutex
        var stored = Note(id = 8, name = "Card", description = "[ ] one", pinned = false)
        val flagHoldsLock = CompletableDeferred<Unit>()
        val releaseFlag = CompletableDeferred<Unit>()

        kotlinx.coroutines.coroutineScope {
            launch {
                // A pin write under the same lock, as NoteUseCase now does for the single-column
                // statements. R4-04: before that, the card's read could predate this write and its
                // whole-row write-back would restore pinned = false.
                mutex.withLock {
                    flagHoldsLock.complete(Unit)
                    releaseFlag.await()
                    stored = stored.copy(pinned = true)
                }
            }
            flagHoldsLock.await()
            val toggle = launch {
                toggleCheckboxInLatestBody(
                    noteId = 8,
                    lineNumber = 0,
                    text = "one",
                    checked = true,
                    mutex = mutex,
                    readLatest = { stored },
                    write = { stored = it },
                )
            }
            delay(30)
            assertTrue(
                "the card read-modify-write must not run while the flag write holds the lock",
                !toggle.isCompleted
            )
            releaseFlag.complete(Unit)
            toggle.join()
        }

        assertTrue("the pin survives the card write-back", stored.pinned)
        assertEquals("[x] one", stored.description)
    }
}

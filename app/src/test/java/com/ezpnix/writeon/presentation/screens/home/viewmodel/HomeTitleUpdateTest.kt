package com.ezpnix.writeon.presentation.screens.home.viewmodel

import com.ezpnix.writeon.domain.model.Note
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-09: a card title edit writes only the name.
 *
 * The card's title callback used to send `note.copy(name = ...)` back, so the body captured when the
 * card was composed replaced whatever the editor had saved since. The helper reads the latest row,
 * which is what keeps the newer body and changes nothing but the name.
 */
class HomeTitleUpdateTest {

    @Test
    fun `a title edit after a body change keeps the new body`() = runBlocking {
        val mutex = Mutex()
        // The card was composed with this body, then the editor changed it.
        var stored = Note(id = 7, name = "old title", description = "old body")
        stored = stored.copy(description = "body saved after the card was drawn")

        val updated = updateTitleInLatestNote(
            noteId = 7,
            name = "new title",
            mutex = mutex,
            readLatest = { stored },
            write = { stored = it },
        )

        assertTrue(updated)
        assertEquals("new title", stored.name)
        assertEquals(
            "the title edit must not resurrect the card's old body",
            "body saved after the card was drawn",
            stored.description
        )
    }

    @Test
    fun `an identical title writes nothing`() = runBlocking {
        var writes = 0
        val stored = Note(id = 8, name = "same", description = "body")

        val updated = updateTitleInLatestNote(
            noteId = 8,
            name = "same",
            mutex = Mutex(),
            readLatest = { stored },
            write = { writes++ },
        )

        assertTrue(updated)
        assertEquals(0, writes)
    }

    @Test
    fun `a missing note writes nothing`() = runBlocking {
        var writes = 0
        val updated = updateTitleInLatestNote(
            noteId = 9,
            name = "title",
            mutex = Mutex(),
            readLatest = { null },
            write = { writes++ },
        )

        assertFalse(updated)
        assertEquals(0, writes)
    }

    @Test
    fun `the helper shares the mutex with the checkbox toggle`() = runBlocking {
        val mutex = Mutex()
        var stored = Note(id = 10, name = "title", description = "[ ] one")
        val latest: suspend (Int) -> Note? = { id -> stored.takeIf { it.id == id } }

        coroutineScope {
            launch {
                toggleCheckboxInLatestBody(
                    noteId = 10,
                    lineNumber = 0,
                    text = "one",
                    checked = true,
                    mutex = mutex,
                    readLatest = latest,
                    write = { stored = it },
                )
            }
            launch {
                updateTitleInLatestNote(
                    noteId = 10,
                    name = "renamed",
                    mutex = mutex,
                    readLatest = latest,
                    write = { stored = it },
                )
            }
        }

        assertEquals("renamed", stored.name)
        assertEquals("[x] one", stored.description)
    }
}

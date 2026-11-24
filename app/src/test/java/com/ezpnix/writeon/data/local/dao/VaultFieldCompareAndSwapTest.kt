package com.ezpnix.writeon.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.data.local.database.NoteDatabase
import com.ezpnix.writeon.domain.model.Note
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAT-01: the vault re-seal write-back is a compare-and-swap, not an unconditional update.
 *
 * The sweep reads a snapshot of every vault row and can run for tens of seconds on the first
 * unlock of an old library. These tests pin the SQL half of the fix on a real in-memory Room
 * database: a row that still holds the bytes the sweep read is rewritten, and a row an editor
 * save replaced in between matches zero rows and keeps the saved body.
 */
@RunWith(RobolectricTestRunner::class)
class VaultFieldCompareAndSwapTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var database: NoteDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a row still holding the read bytes is resealed`() = runBlocking {
        database.noteDao().addNote(vaultNote(body = "v2:old-body"))

        val changed = database.compareAndSwapVaultFields(
            id = NOTE_ID,
            previousName = "v2:old-name",
            previousDescription = "v2:old-body",
            name = "v3:new-name",
            description = "v3:new-body",
        )

        assertEquals(1, changed)
        val stored = database.noteDao().getNoteById(NOTE_ID).first()
        assertEquals("v3:new-name", stored.name)
        assertEquals("v3:new-body", stored.description)
    }

    @Test
    fun `a row saved after the sweep's read is left alone`() = runBlocking {
        database.noteDao().addNote(vaultNote(body = "v2:old-body"))
        // The editor's save lands between the sweep's read and its write.
        database.noteDao().updateNote(
            vaultNote(body = "v3:body-written-by-the-editor").copy(updatedAt = 9)
        )

        val changed = database.compareAndSwapVaultFields(
            id = NOTE_ID,
            previousName = "v2:old-name",
            previousDescription = "v2:old-body",
            name = "v3:sweep-name",
            description = "v3:sweep-body",
        )

        assertEquals("0 rows is the sweep's 'skipped', never 'upgraded'", 0, changed)
        val stored = database.noteDao().getNoteById(NOTE_ID).first()
        assertEquals("v3:body-written-by-the-editor", stored.description)
    }

    @Test
    fun `a plain row never receives vault ciphertext`() = runBlocking {
        database.noteDao().addNote(
            Note(id = NOTE_ID, name = "plain", description = "plain body", encrypted = false)
        )

        val changed = database.compareAndSwapVaultFields(
            id = NOTE_ID,
            previousName = "plain",
            previousDescription = "plain body",
            name = "v3:new-name",
            description = "v3:new-body",
        )

        assertEquals(0, changed)
        val stored = database.noteDao().getNoteById(NOTE_ID).first()
        assertEquals("plain body", stored.description)
    }

    private fun vaultNote(body: String) = Note(
        id = NOTE_ID,
        name = "v2:old-name",
        description = body,
        encrypted = true,
    )

    private companion object {
        const val NOTE_ID = 71
    }
}

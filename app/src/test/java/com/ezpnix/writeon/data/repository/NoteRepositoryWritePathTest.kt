package com.ezpnix.writeon.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.data.local.database.NoteDatabaseProvider
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TST-04: the write/delete paths of [NoteRepositoryImpl] against real Room SQL.
 *
 * The existing DAO tests use hand-written fakes, which cannot see a wrong WHERE clause, a missing
 * transaction boundary or a column that a "partial" update actually rewrote. These tests build the
 * production [NoteDatabaseProvider] over Robolectric's SQLite, exercise the repository methods and
 * then read the raw columns through `openHelper.readableDatabase` - never through a DAO - so the
 * assertion is about what the database holds.
 *
 * Rows are inserted as `encrypted = true` vault notes and attachments with an empty caption. That
 * is deliberate: [NoteCrypto] has no Android Keystore under Robolectric, while
 * [com.ezpnix.writeon.data.local.database.EncryptedNoteDao] stores vault rows verbatim and skips
 * sealing an empty caption. The single-column SQL statements (the point of these tests) are the
 * same for vault and plain rows; `updatePlainDescription` is additionally exercised on a plain row
 * through the raw DAO, including its `encrypted = 0` guard.
 *
 * There is no fake attachment *store* in the production code: [NoteRepositoryImpl] returns the
 * rows it read and leaves the files to its caller. The delete test therefore creates real files,
 * asserts the repository deleted the rows but not the bytes, and then acts as the caller that
 * deletes the returned files.
 */
@RunWith(RobolectricTestRunner::class)
class NoteRepositoryWritePathTest {

    private lateinit var application: Application
    private lateinit var provider: NoteDatabaseProvider
    private lateinit var repository: NoteRepositoryImpl

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        provider = NoteDatabaseProvider(application)
        // The provider shares one connection per process (see its companion); close before wiping
        // the file so a connection from an earlier test cannot keep the old database open.
        provider.close()
        application.deleteDatabase(DatabaseConst.NOTES_DATABASE_FILE_NAME)
        repository = NoteRepositoryImpl(provider)
    }

    @After
    fun tearDown() {
        provider.close()
        application.deleteDatabase(DatabaseConst.NOTES_DATABASE_FILE_NAME)
    }

    @Test
    fun `save then delete removes exactly that note's attachment rows and returns them`() = runBlocking {
        val doomedFileOne = tempAttachmentFile("doomed-1.jpg")
        val doomedFileTwo = tempAttachmentFile("doomed-2.jpg")
        val keptFile = tempAttachmentFile("kept-1.jpg")

        val doomedId = repository.saveNoteWithAttachments(
            note = vaultNote("delete me"),
            upsertAttachments = listOf(
                attachment("doomed-1", doomedFileOne),
                attachment("doomed-2", doomedFileTwo),
            ),
            reviveAttachmentIds = emptyList(),
            trashAttachmentIds = emptyList(),
        )
        val keptId = repository.saveNoteWithAttachments(
            note = vaultNote("keep me"),
            upsertAttachments = listOf(attachment("kept-1", keptFile)),
            reviveAttachmentIds = emptyList(),
            trashAttachmentIds = emptyList(),
        )

        assertEquals("both attachment rows belong to the new note", 2, rawAttachmentsFor(doomedId).size)
        assertEquals(1, rawAttachmentsFor(keptId).size)

        val returned = repository.deleteNoteWithAttachments(Note(id = doomedId, name = "", description = ""))

        assertEquals(
            "the returned rows are exactly the files the caller has to delete",
            setOf("doomed-1", "doomed-2"),
            returned.map { it.id }.toSet(),
        )
        assertTrue("the deleted note's rows must be gone", rawAttachmentsFor(doomedId).isEmpty())
        assertEquals(
            "another note's rows must survive",
            listOf("kept-1"),
            rawAttachmentsFor(keptId).map { it.id },
        )
        assertNull("the deleted note row must be gone", rawNoteOrNull(doomedId))
        assertNotNull("the other note row must survive", rawNoteOrNull(keptId))

        // The repository deletes rows inside its transaction and only hands the files back; it must
        // never touch the bytes itself, so an interruption leaves recoverable files, not dead rows.
        assertTrue("the repository must not delete attachment files", doomedFileOne.isFile)
        assertTrue("the repository must not delete attachment files", doomedFileTwo.isFile)

        // This is the caller contract: delete what the repository returned.
        returned.forEach { File(it.path).delete() }
        assertFalse(doomedFileOne.exists())
        assertFalse(doomedFileTwo.exists())
        assertTrue("the kept note's file is untouched", keptFile.isFile)
    }

    @Test
    fun `CQ-08 saving a note deleted after the read inserts no orphan attachments`() = runBlocking {
        // The editor holds the row while another surface deletes it. Its save must not leave the
        // attachment rows behind for a note no screen can show or delete.
        val noteId = repository.saveNoteWithAttachments(
            note = vaultNote("deleted under the save"),
            upsertAttachments = emptyList(),
            reviveAttachmentIds = emptyList(),
            trashAttachmentIds = emptyList(),
        )
        repository.deleteNoteWithAttachments(Note(id = noteId, name = "", description = ""))
        assertNull(rawNoteOrNull(noteId))

        val outcome = runCatching {
            repository.saveNoteWithAttachments(
                note = vaultNote("deleted under the save").copy(id = noteId),
                upsertAttachments = listOf(attachment("orphan-after-delete", null)),
                reviveAttachmentIds = emptyList(),
                trashAttachmentIds = emptyList(),
            )
        }

        assertTrue("a save whose row update matched zero rows must fail", outcome.isFailure)
        assertTrue(
            "the attachment upsert must roll back with the missing row",
            rawAttachmentsFor(noteId).isEmpty(),
        )
        assertNull("the note row must not be resurrected", rawNoteOrNull(noteId))
    }

    @Test
    fun `the recycle-bin count is a real SQL aggregate over every trashed row`() = runBlocking {
        val first = provider.noteDao().addNoteReturningId(vaultNote("first")).toInt()
        val second = provider.noteDao().addNoteReturningId(vaultNote("second")).toInt()
        val alive = provider.noteDao().addNoteReturningId(vaultNote("alive")).toInt()

        repository.updateTrashed(first, true)
        repository.updateTrashed(second, true)

        assertEquals(
            "two trashed rows, the live one excluded",
            2,
            repository.getTrashedNoteCount().first(),
        )

        repository.updateTrashed(alive, true)
        assertEquals(3, repository.getTrashedNoteCount().first())
    }

    @Test
    fun `trashAttachmentIds trashes exactly those rows and leaves the others alive`() = runBlocking {
        val noteId = repository.saveNoteWithAttachments(
            note = vaultNote("trash probe"),
            upsertAttachments = listOf(attachment("gone", null), attachment("stays", null)),
            reviveAttachmentIds = emptyList(),
            trashAttachmentIds = emptyList(),
        )
        assertNull(rawAttachment("gone").trashedAt)
        assertNull(rawAttachment("stays").trashedAt)

        // A later save whose body no longer references "gone": DAT-02 recycles the row instead of
        // deleting it, so the save undo can restore the note without dead links.
        repository.saveNoteWithAttachments(
            note = vaultNote("trash probe").copy(id = noteId),
            upsertAttachments = emptyList(),
            reviveAttachmentIds = emptyList(),
            trashAttachmentIds = listOf("gone"),
        )

        val trashed = rawAttachment("gone")
        val alive = rawAttachment("stays")
        assertNotNull("the pruned row must carry a trashed_at stamp", trashed.trashedAt)
        assertNull("the still-referenced row must stay alive", alive.trashedAt)
        assertEquals("the row itself is kept for the recycle bin", 2, rawAttachmentCount())
    }

    @Test
    fun `updateTags updatePinned and updateTrashed change one column each`() = runBlocking {
        val noteId = repository.saveNoteWithAttachments(
            note = vaultNote("single column").copy(tags = listOf("original")),
            upsertAttachments = emptyList(),
            reviveAttachmentIds = emptyList(),
            trashAttachmentIds = emptyList(),
        )
        val before = rawNote(noteId)

        repository.updateTags(noteId, listOf("alpha", "beta"))
        repository.updatePinned(noteId, true)
        repository.updateTrashed(noteId, true)

        val after = rawNote(noteId)
        assertEquals("tags is the only column updateTags may change", """["alpha","beta"]""", after.tags)
        assertTrue("updatePinned must set pinned", after.pinned == 1)
        assertTrue("updateTrashed must set trashed", after.trashed == 1)

        assertEquals("the name must not move", before.name, after.name)
        assertEquals("the body must not move", before.body, after.body)
        assertEquals("created_at must not move", before.createdAt, after.createdAt)
        assertEquals("updated_at must not move", before.updatedAt, after.updatedAt)
        assertEquals("encrypted must not move", before.encrypted, after.encrypted)
    }

    @Test
    fun `updatePlainDescription rewrites only a plain row's body`() = runBlocking {
        val rawDao = provider.instance().noteDao()
        // Inserted through the raw DAO: a plain row is what updatePlainDescription is allowed to
        // rewrite, and the raw insert avoids the Keystore-backed wrapper under Robolectric.
        val noteId = rawDao.addNoteReturningId(
            Note(
                name = "plain note",
                description = "original body",
                encrypted = false,
                createdAt = 1_000L,
                updatedAt = 2_000L,
            )
        ).toInt()
        val before = rawNote(noteId)

        // The repository's wrapper seals the argument first; an empty string is the one value
        // NoteCrypto.seal returns without the Keystore, so this exercises the repository method.
        repository.updatePlainDescription(noteId, "")
        val afterEmpty = rawNote(noteId)
        assertEquals("the body is the only column changed", "", afterEmpty.body)
        assertEquals(before.name, afterEmpty.name)
        assertEquals(before.createdAt, afterEmpty.createdAt)
        assertEquals(before.updatedAt, afterEmpty.updatedAt)
        assertEquals(before.tags, afterEmpty.tags)

        // Non-empty text through the same generated statement (the wrapper adds only the seal).
        rawDao.updatePlainDescription(noteId, "rewritten body")
        val afterText = rawNote(noteId)
        assertEquals("rewritten body", afterText.body)
        assertEquals(before.name, afterText.name)
        assertEquals(before.createdAt, afterText.createdAt)
        assertEquals(before.updatedAt, afterText.updatedAt)

        // A vault row must never receive Keystore-sealed plaintext: the SQL carries `encrypted = 0`.
        val vaultId = repository.saveNoteWithAttachments(
            note = vaultNote("vault guard"),
            upsertAttachments = emptyList(),
            reviveAttachmentIds = emptyList(),
            trashAttachmentIds = emptyList(),
        )
        rawDao.updatePlainDescription(vaultId, "must not land")
        assertEquals(
            "the encrypted=0 guard must leave a vault row's ciphertext untouched",
            "sealed-body",
            rawNote(vaultId).body,
        )
    }

    @Test
    fun `attachment trash and note update commit in one transaction`() = runBlocking {
        val file = tempAttachmentFile("r604-attachment.jpg")
        val noteId = repository.saveNoteWithAttachments(
            note = vaultNote("r604"),
            upsertAttachments = listOf(attachment("r604-att", file)),
            reviveAttachmentIds = emptyList(),
            trashAttachmentIds = emptyList(),
        )
        val updated = vaultNote("r604-updated").copy(id = noteId)

        repository.setAttachmentTrashedAndUpdateNote("r604-att", 123L, updated)

        assertEquals(123L, rawAttachment("r604-att").trashedAt)
        assertEquals("r604-updated", rawNote(noteId).name)
    }

    @Test
    fun `saveNoteWithAttachments assigns the generated note id to every attachment`() = runBlocking {
        val noteId = repository.saveNoteWithAttachments(
            note = vaultNote("owner"),
            upsertAttachments = listOf(attachment("first", null), attachment("second", null)),
            reviveAttachmentIds = emptyList(),
            trashAttachmentIds = emptyList(),
        )
        assertTrue("the insert must report a real id", noteId > 0)
        assertEquals(
            "upserted rows must carry the note id, not 0",
            listOf(noteId, noteId),
            rawAttachmentsFor(noteId).map { it.noteId },
        )
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun vaultNote(name: String): Note = Note(
        name = name,
        description = "sealed-body",
        encrypted = true,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
        tags = listOf("keep"),
    )

    private fun attachment(id: String, file: File?): Attachment = Attachment(
        id = id,
        noteId = 0,
        type = Attachment.TYPE_IMAGE,
        // A path is metadata here; the delete test creates the file so "not deleted" is observable.
        path = file?.absolutePath ?: "/nonexistent/$id.jpg",
        caption = "",
        createdAt = 1_700_000_200_000L,
    )

    private fun tempAttachmentFile(name: String): File =
        File(application.cacheDir, "tst04-${System.nanoTime()}-$name").apply {
            writeText("attachment bytes")
        }

    private fun rawAttachmentsFor(noteId: Int): List<StoredAttachment> =
        provider.instance().openHelper.readableDatabase.query(
            "SELECT `attachment-id`, `note-id`, `path`, `trashed_at` FROM `attachments` WHERE `note-id` = ?",
            arrayOf(noteId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        StoredAttachment(
                            id = cursor.getString(0),
                            noteId = cursor.getInt(1),
                            path = cursor.getString(2),
                            trashedAt = if (cursor.isNull(3)) null else cursor.getLong(3),
                        )
                    )
                }
            }
        }

    private fun rawAttachment(id: String): StoredAttachment =
        provider.instance().openHelper.readableDatabase.query(
            "SELECT `attachment-id`, `note-id`, `path`, `trashed_at` FROM `attachments` WHERE `attachment-id` = ?",
            arrayOf(id)
        ).use { cursor ->
            assertTrue("attachment row $id is missing", cursor.moveToFirst())
            StoredAttachment(
                id = cursor.getString(0),
                noteId = cursor.getInt(1),
                path = cursor.getString(2),
                trashedAt = if (cursor.isNull(3)) null else cursor.getLong(3),
            )
        }

    private fun rawAttachmentCount(): Int =
        provider.instance().openHelper.readableDatabase.query("SELECT COUNT(*) FROM `attachments`")
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun rawNote(id: Int): StoredNote =
        rawNoteOrNull(id) ?: throw AssertionError("note row $id is missing")

    private fun rawNoteOrNull(id: Int): StoredNote? =
        provider.instance().openHelper.readableDatabase.query(
            "SELECT `note-name`, `note-description`, `created_at`, `updated_at`, `tags`, " +
                "`pinned`, `trashed`, `encrypted` FROM `notes-table` WHERE id = ?",
            arrayOf(id)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            StoredNote(
                name = cursor.getString(0),
                body = cursor.getString(1),
                createdAt = cursor.getLong(2),
                updatedAt = cursor.getLong(3),
                tags = cursor.getString(4),
                pinned = cursor.getInt(5),
                trashed = cursor.getInt(6),
                encrypted = cursor.getInt(7),
            )
        }

    private data class StoredNote(
        val name: String,
        val body: String,
        val createdAt: Long,
        val updatedAt: Long,
        val tags: String,
        val pinned: Int,
        val trashed: Int,
        val encrypted: Int,
    )

    private data class StoredAttachment(
        val id: String,
        val noteId: Int,
        val path: String,
        val trashedAt: Long?,
    )
}

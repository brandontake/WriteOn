package com.ezpnix.writeon.domain.usecase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.attachment.AttachmentIndex
import com.ezpnix.writeon.core.security.EncryptionHelper
import com.ezpnix.writeon.core.security.InMemoryVaultKeyStore
import com.ezpnix.writeon.core.widget.WidgetRefresher
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.repository.NoteRepository
import com.ezpnix.writeon.presentation.screens.home.viewmodel.toggleCheckboxInLatestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PERF-06: an unchanged vault row must not be decrypted again on the next list emission.
 *
 * `vaultRowDecryptions` counts cipher calls, so the assertions are about work avoided rather than
 * wall time. The cache entry is validated by `(id, updatedAt)`; a save that keeps the timestamp
 * (which is exactly what a home-card edit does) invalidates it, and a lock clears it.
 */
@RunWith(RobolectricTestRunner::class)
class NoteUseCaseVaultCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an unchanged vault row is decrypted once across emissions`() = runBlocking {
        val encryption = EncryptionHelper(StringBuilder()).apply { setPassword(PASSWORD) }
        val repository = FakeRepository(vaultNote(encryption))
        val useCase = newUseCase(repository, encryption)

        useCase.loadNotesOnce()
        val firstCount = useCase.vaultRowDecryptions
        assertEquals(1, firstCount)
        assertEquals("body", useCase.notes.single().description)

        useCase.loadNotesOnce()

        assertEquals("the second emission must hit the cache", firstCount, useCase.vaultRowDecryptions)
        assertEquals("body", useCase.notes.single().description)
    }

    @Test
    fun `clearing sensitive state drops every cached row`() = runBlocking {
        val encryption = EncryptionHelper(StringBuilder()).apply { setPassword(PASSWORD) }
        val useCase = newUseCase(FakeRepository(vaultNote(encryption)), encryption)

        useCase.loadNotesOnce()
        val firstCount = useCase.vaultRowDecryptions
        useCase.clearSensitiveState()
        useCase.loadNotesOnce()

        assertTrue(useCase.vaultRowDecryptions > firstCount)
    }

    @Test
    fun `a save invalidates the cached row even when updatedAt is unchanged`() = runBlocking {
        val encryption = EncryptionHelper(StringBuilder()).apply { setPassword(PASSWORD) }
        val repository = FakeRepository(vaultNote(encryption))
        val useCase = newUseCase(repository, encryption)

        useCase.loadNotesOnce()
        assertEquals("body", useCase.notes.single().description)

        // Same id, same updatedAt, same lengths - only the plaintext differs. Without the write
        // invalidation the cache would keep serving the previous body.
        useCase.addNote(useCase.notes.single().copy(description = "BODY"))

        useCase.loadNotesOnce()
        assertEquals("BODY", useCase.notes.single().description)
    }

    @Test
    fun `a locked vault is not cached`() = runBlocking {
        val encryption = EncryptionHelper(StringBuilder())
        val repository = FakeRepository(
            Note(
                id = 9,
                name = "cipher",
                description = "cipher",
                encrypted = true,
                updatedAt = 1,
            )
        )
        val useCase = newUseCase(repository, encryption)

        useCase.loadNotesOnce()
        val firstCount = useCase.vaultRowDecryptions
        useCase.loadNotesOnce()

        assertEquals(
            "LOADING results must not be reused after an unlock reloads the list",
            firstCount + 1,
            useCase.vaultRowDecryptions
        )
    }

    @Test
    fun `REG-03 a tag write that leaves updatedAt alone refreshes the cached row`() = runBlocking {
        val encryption = EncryptionHelper(StringBuilder()).apply { setPassword(PASSWORD) }
        val repository = FakeRepository(vaultNote(encryption))
        val useCase = newUseCase(repository, encryption)

        useCase.loadNotesOnce()
        assertEquals(emptyList<String>(), useCase.notes.single().tags)
        val decryptionsBefore = useCase.vaultRowDecryptions

        useCase.setTags(7, listOf("Work"))
        useCase.updatePinStatus(7, true)
        useCase.setTrashed(7, true)
        useCase.setTrashed(7, false)

        assertEquals(listOf("work"), repository.note.tags)
        assertEquals("the metadata writes must not stamp updated_at", 10L, repository.note.updatedAt)
        useCase.loadNotesOnce()

        val listed = useCase.notes.single()
        assertEquals("the list must show the new tag", listOf("work"), listed.tags)
        assertTrue("the list must show the new pin", listed.pinned)
        assertFalse("the list must show the final trash state", listed.trashed)
        assertTrue(
            "the old cached Note must not be reused after a metadata write",
            useCase.vaultRowDecryptions > decryptionsBefore,
        )
    }

    @Test
    fun `REG-03 a card toggle preserves metadata written without moving updatedAt`() = runBlocking {
        val encryption = EncryptionHelper(StringBuilder()).apply { setPassword(PASSWORD) }
        val repository = FakeRepository(vaultNote(encryption, body = "[ ] task"))
        val useCase = newUseCase(repository, encryption)

        // Fill the cache with the row as it was before the metadata write.
        useCase.loadNotesOnce()
        // A write that did not go through NoteUseCase cannot invalidate anything, and it keeps the
        // timestamp: the merge in getEditableNote is the safety net for exactly this case.
        repository.updateTags(7, listOf("kept"))
        repository.updatePinned(7, true)

        val editable = useCase.getEditableNote(7)
        assertEquals(listOf("kept"), editable?.tags)
        assertTrue("the pinned flag must come from the fresh row", editable?.pinned == true)

        val toggled = toggleCheckboxInLatestBody(
            noteId = 7,
            lineNumber = 0,
            text = "task",
            checked = true,
            mutex = Mutex(),
            readLatest = { id -> useCase.getEditableNote(id) },
            write = { note -> useCase.addNote(note) },
        )

        assertTrue(toggled)
        assertEquals("the checkbox write must keep the new tags", listOf("kept"), repository.note.tags)
        assertTrue("the checkbox write must keep the pin", repository.note.pinned)
        assertEquals(
            "[x] task",
            encryption.decrypt(repository.note.description).first,
        )
    }

    @Test
    fun `REG-08 a row whose title does not authenticate is hidden and cannot be edited`() = runBlocking {
        val keyStore = InMemoryVaultKeyStore()
        // Same library salt, different passphrase: the title is sealed by a key this session cannot
        // derive, while the body opens normally.
        val otherSession = EncryptionHelper(StringBuilder(), keyStore).apply {
            setPassword("another horse battery staple")
        }
        val encryption = EncryptionHelper(StringBuilder(), keyStore).apply { setPassword(PASSWORD) }
        val mixed = Note(
            id = 7,
            name = otherSession.encrypt("title"),
            description = encryption.encrypt("[ ] task"),
            encrypted = true,
            updatedAt = 10,
            tags = listOf("kept"),
            pinned = true,
        )
        val repository = FakeRepository(mixed)
        val useCase = newUseCase(repository, encryption)

        useCase.loadNotesOnce()

        assertTrue("a row with one unreadable column must not be listed", useCase.notes.isEmpty())
        assertEquals(1, useCase.hiddenVaultCount)
        assertNull("the editor must not receive a half-decrypted row", useCase.getEditableNote(7))

        val toggled = toggleCheckboxInLatestBody(
            noteId = 7,
            lineNumber = 0,
            text = "task",
            checked = true,
            mutex = Mutex(),
            readLatest = { id -> useCase.getEditableNote(id) },
            write = { note -> useCase.addNote(note) },
        )

        assertFalse("the card toggle has no editable row to start from", toggled)
        assertEquals(
            "the unreadable title ciphertext must be left exactly as it was",
            mixed.name,
            repository.note.name,
        )
    }

    @Test
    fun `REG-08 an empty vault column is a successful read not a failure`() = runBlocking {
        val encryption = EncryptionHelper(StringBuilder()).apply { setPassword(PASSWORD) }
        val repository = FakeRepository(
            Note(
                id = 7,
                name = encryption.encrypt("title"),
                description = "",
                encrypted = true,
                updatedAt = 10,
            )
        )
        val useCase = newUseCase(repository, encryption)

        useCase.loadNotesOnce()

        assertEquals(1, useCase.notes.size)
        assertEquals("", useCase.notes.single().description)
        assertEquals(0, useCase.hiddenVaultCount)
    }

    @Test
    fun `DAT-02 vault attachments are hidden while locked and visible while unlocked`() = runBlocking {
        val encryption = EncryptionHelper(StringBuilder())
        val live = Attachment(
            id = "vault-live",
            noteId = 7,
            type = Attachment.TYPE_IMAGE,
            path = "/filesDir/attachments/vault-live.jpg",
        )
        val pruned = live.copy(id = "vault-pruned", trashedAt = 55L)
        val repository = FakeRepository(
            note = Note(
                id = 7,
                name = "cipher",
                description = "cipher",
                encrypted = true,
                updatedAt = 10,
            ),
            attachments = listOf(live),
            trashedAttachments = listOf(pruned),
        )
        val useCase = newUseCase(repository, encryption)

        assertTrue(
            "a locked vault must not list its media",
            useCase.getLibraryAttachmentsFlow(Attachment.TYPE_IMAGE).first().isEmpty(),
        )
        assertTrue(
            "a locked vault must not list its recycled media",
            useCase.getTrashedAttachmentsFlow().first().isEmpty(),
        )

        encryption.setPassword(PASSWORD)
        useCase.refresh()

        assertEquals(
            listOf("vault-live"),
            useCase.getLibraryAttachmentsFlow(Attachment.TYPE_IMAGE).first().map { it.id },
        )
        assertEquals(
            "the pruned row must have a cleanup entry while the vault is open",
            listOf("vault-pruned"),
            useCase.getTrashedAttachmentsFlow().first().map { it.id },
        )
    }

    @Test
    fun `DAT-02 a restored vault attachment is re-linked in the decrypted body`() = runBlocking {
        val encryption = EncryptionHelper(StringBuilder()).apply { setPassword(PASSWORD) }
        val pruned = Attachment(
            id = "vault-pruned",
            noteId = 7,
            type = Attachment.TYPE_IMAGE,
            path = "/filesDir/attachments/vault-pruned.jpg",
            trashedAt = 55L,
        )
        val repository = FakeRepository(
            note = vaultNote(encryption, body = "Intro"),
            trashedAttachments = listOf(pruned),
        )
        val useCase = newUseCase(repository, encryption)

        useCase.restoreAttachment(pruned.id)

        assertNull("the row must leave the recycle bin", repository.trashedAttachments.single().trashedAt)
        assertEquals(
            "Intro\n![](attachment://vault-pruned)",
            encryption.decrypt(repository.note.description).first,
        )
    }

    private fun vaultNote(
        encryption: EncryptionHelper,
        name: String = "title",
        body: String = "body",
        tags: List<String> = emptyList(),
        pinned: Boolean = false,
    ): Note = Note(
        id = 7,
        name = encryption.encrypt(name),
        description = encryption.encrypt(body),
        encrypted = true,
        updatedAt = 10,
        tags = tags,
        pinned = pinned,
    )

    private fun newUseCase(repository: NoteRepository, encryption: EncryptionHelper): NoteUseCase =
        NoteUseCase(
            noteRepository = repository,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            encryptionHelper = encryption,
            attachmentIndex = object : AttachmentIndex {
                override fun put(attachments: Collection<Attachment>) = Unit
                override fun put(attachment: Attachment) = Unit
                override fun remove(ids: Collection<String>) = Unit
            },
            widgetRefresher = object : WidgetRefresher {
                override suspend fun refreshAll() = Unit
            },
            context = context,
        )

    private class FakeRepository(
        var note: Note,
        var attachments: List<Attachment> = emptyList(),
        var trashedAttachments: List<Attachment> = emptyList(),
    ) : NoteRepository {

        override fun getAllNotes(): Flow<List<Note>> = flowOf(listOf(note))

        override suspend fun getAllNotesSync(): List<Note> = listOf(note)

        override suspend fun addNote(note: Note) {
            this.note = note
        }

        override suspend fun updateNote(note: Note) {
            this.note = note
        }

        override suspend fun deleteNote(note: Note) = Unit

        override suspend fun saveNoteWithAttachments(
            note: Note,
            upsertAttachments: List<Attachment>,
            reviveAttachmentIds: List<String>,
            trashAttachmentIds: List<String>,
        ): Int {
            this.note = note
            return note.id
        }

        override suspend fun deleteNoteWithAttachments(note: Note): List<Attachment> = emptyList()

        override suspend fun updatePinned(id: Int, pinned: Boolean) {
            if (note.id == id) note = note.copy(pinned = pinned)
        }

        override suspend fun updateTags(id: Int, tags: List<String>) {
            if (note.id == id) note = note.copy(tags = tags)
        }

        override suspend fun updateTrashed(id: Int, trashed: Boolean) {
            if (note.id == id) note = note.copy(trashed = trashed)
        }

        override suspend fun updatePlainDescription(id: Int, description: String) = Unit

        override suspend fun updateVaultFields(
            id: Int,
            previousName: String,
            previousDescription: String,
            name: String,
            description: String,
        ): Int = 0

        override fun getNoteById(id: Int): Flow<Note> = flowOf(note)

        override fun getTrashedNotes(): Flow<List<Note>> = flowOf(emptyList())

        override fun getNotesByTag(tag: String): Flow<List<Note>> = flowOf(emptyList())

        override suspend fun getAllTags(): List<String> = emptyList()

        override suspend fun batchUpdateNotes(notes: List<Note>) = Unit

        override suspend fun sealLegacyPlaintextRows(): Int = 0

        override suspend fun sealLegacyCaptions(): Int = 0

        override suspend fun getAllAttachments(): List<Attachment> = attachments + trashedAttachments

        override suspend fun getAttachmentsForNote(noteId: Int): List<Attachment> =
            (attachments + trashedAttachments).filter { it.noteId == noteId }

        override suspend fun upsertAttachments(attachments: List<Attachment>) = Unit

        override suspend fun assignAttachmentsToNote(noteId: Int, ids: List<String>) = Unit

        override suspend fun deleteAttachments(ids: List<String>) = Unit

        override suspend fun getAttachmentById(id: String): Attachment? =
            (attachments + trashedAttachments).firstOrNull { it.id == id }

        override fun observeAttachmentsByType(type: String): Flow<List<Attachment>> =
            flowOf(attachments.filter { it.type == type })

        override fun observeTrashedAttachments(): Flow<List<Attachment>> = flowOf(trashedAttachments)

        override suspend fun setAttachmentTrashed(ids: List<String>, trashedAt: Long?) {
            attachments = attachments.map { attachment ->
                if (attachment.id in ids) attachment.copy(trashedAt = trashedAt) else attachment
            }
            trashedAttachments = trashedAttachments.map { attachment ->
                if (attachment.id in ids) attachment.copy(trashedAt = trashedAt) else attachment
            }
        }
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
    }
}

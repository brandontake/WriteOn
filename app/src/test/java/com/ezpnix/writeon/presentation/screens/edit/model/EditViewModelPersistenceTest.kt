package com.ezpnix.writeon.presentation.screens.edit.model

import android.app.Application
import android.os.Looper
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.attachment.AttachmentIndex
import com.ezpnix.writeon.core.attachment.AttachmentSyntax
import com.ezpnix.writeon.core.security.EncryptionHelper
import com.ezpnix.writeon.core.widget.WidgetRefresher
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.repository.NoteRepository
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.presentation.screens.edit.PendingSaveUndo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * DAT-02/DAT-03 at the editor boundary: what a save does to attachment rows, and what an
 * undecryptable vault row must never make the editor write.
 *
 * The repository fake holds rows in maps and applies the same trash/revive semantics the Room
 * implementation does, so the test is about [EditViewModel]'s plan and undo orchestration rather
 * than SQL. [PendingSaveUndo.undo] is used for the undo half because it is exactly the path the
 * snackbar takes - it outlives the editor's scope.
 *
 * The editor writes on `Dispatchers.Main.immediate` (the save scope). Robolectric pauses its main
 * looper, so each test body runs on a worker thread while the test thread keeps pumping that
 * looper; a plain `runBlocking` on the test thread would deadlock the first hop back from IO.
 */
@RunWith(RobolectricTestRunner::class)
class EditViewModelPersistenceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        PendingSaveUndo.clear()
    }

    @Test
    fun `a pruned recording is trashed and undo brings the row and file back`() = pumpMainLooper {
        val audio = temporaryFolder.newFile("memo.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val repository = FakeNoteRepository()
        val body = "intro\n${AttachmentSyntax.audio("aud-1", "memo")}\n"
        val note = Note(id = 41, name = "Note", description = body, updatedAt = 1)
        repository.seed(note, audioRow("aud-1", note.id, audio))
        val viewModel = newViewModel(repository, EncryptionHelper(StringBuilder()))

        viewModel.setupNoteData(note.id)
        settleMainTasks()
        // The user deletes the attachment's line with the cursor and saves.
        viewModel.updateNoteDescription(TextFieldValue("intro\n"))
        assertTrue(viewModel.saveBeforeExit(note.id))

        assertEquals(listOf("aud-1"), repository.lastTrashedAttachmentIds)
        assertNotNull(
            "the save must move the row to the recycle bin, not delete it",
            repository.attachments["aud-1"]?.trashedAt
        )
        assertTrue("the audio file must survive the save", audio.isFile)

        assertTrue(PendingSaveUndo.undo())

        assertNull(
            "undo restores the row the save pruned",
            repository.attachments["aud-1"]?.trashedAt
        )
        assertEquals(
            "undo restores the note row",
            body,
            repository.notes[note.id]?.description
        )
        assertTrue("undo never deletes the file", audio.isFile)
    }

    @Test
    fun `without undo the pruned row stays trashed and its file stays on disk`() = pumpMainLooper {
        val audio = temporaryFolder.newFile("memo.m4a").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val repository = FakeNoteRepository()
        val body = "intro\n${AttachmentSyntax.audio("aud-2", "memo")}\n"
        val note = Note(id = 42, name = "Note", description = body, updatedAt = 1)
        repository.seed(note, audioRow("aud-2", note.id, audio))
        val viewModel = newViewModel(repository, EncryptionHelper(StringBuilder()))

        viewModel.setupNoteData(note.id)
        settleMainTasks()
        viewModel.updateNoteDescription(TextFieldValue("intro\n"))
        assertTrue(viewModel.saveBeforeExit(note.id))

        assertNotNull(repository.attachments["aud-2"]?.trashedAt)
        assertTrue("the save must not delete the pruned row's file", audio.isFile)
        assertEquals("intro\n", repository.notes[note.id]?.description)
    }

    @Test
    fun `an empty vault title still loads the body and exit writes nothing`() = pumpMainLooper {
        val encryption = EncryptionHelper(StringBuilder()).apply { setPassword("vault-pass") }
        val repository = FakeNoteRepository()
        // New vault notes can be saved without a title; the description column is the only
        // ciphertext and it is perfectly readable.
        val note = Note(
            id = 51,
            name = "",
            description = encryption.encrypt("hello body"),
            encrypted = true,
            updatedAt = 2,
        )
        repository.seed(note)
        val viewModel = newViewModel(repository, encryption)

        viewModel.setupNoteData(note.id)
        settleMainTasks()

        assertEquals("hello body", viewModel.noteDescription.value.text)
        assertTrue("there is nothing to write", viewModel.saveBeforeExit(note.id))
        assertTrue("the row must not be rewritten", repository.savedNotes.isEmpty())
        assertFalse(
            "the note must not be moved to the trash",
            repository.notes.getValue(note.id).trashed
        )
    }

    @Test
    fun `an undecryptable vault column refuses the save instead of blanking the row`() =
        pumpMainLooper {
            val encryption = EncryptionHelper(StringBuilder()).apply { setPassword("vault-pass") }
            val repository = FakeNoteRepository()
            val note = Note(
                id = 52,
                name = "",
                description = "not-a-sealed-value",
                encrypted = true,
                updatedAt = 3,
            )
            repository.seed(note)
            val viewModel = newViewModel(repository, encryption)

            viewModel.setupNoteData(note.id)
            settleMainTasks()

            assertTrue(
                "the refusal must not trap the user in the editor",
                viewModel.saveBeforeExit(note.id)
            )
            assertTrue("no note row may be written", repository.savedNotes.isEmpty())
            assertFalse(
                "the unreadable row must not be moved to the trash",
                repository.notes.getValue(note.id).trashed
            )
            assertEquals(
                "the stored ciphertext stays byte-for-byte",
                "not-a-sealed-value",
                repository.notes.getValue(note.id).description
            )
        }

    /**
     * Runs [block] on a worker while the test thread pumps Robolectric's paused main looper.
     *
     * The ViewModel's save scope is `Dispatchers.Main.immediate`; a `runBlocking` on the test
     * thread itself would block the looper the save is about to resume on.
     */
    private fun pumpMainLooper(block: suspend () -> Unit) {
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                runBlocking { block() }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        while (worker.isAlive) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        failure.get()?.let { throw it }
    }

    /** Lets the editor's Main-scoped collectors run between two assertions. */
    private suspend fun settleMainTasks() {
        repeat(20) { delay(5) }
    }

    private fun audioRow(id: String, noteId: Int, file: java.io.File) = Attachment(
        id = id,
        noteId = noteId,
        type = Attachment.TYPE_AUDIO,
        path = file.absolutePath,
        caption = "memo",
        createdAt = 1,
    )

    private fun newViewModel(
        repository: FakeNoteRepository,
        encryption: EncryptionHelper,
    ): EditViewModel {
        val useCase = NoteUseCase(
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
            context = application,
        )
        return EditViewModel(application, useCase, encryption, SavedStateHandle())
    }

    /** In-memory repository with the attachment recycle-bin semantics the real one applies. */
    private class FakeNoteRepository : NoteRepository {

        val notes = LinkedHashMap<Int, Note>()
        val attachments = LinkedHashMap<String, Attachment>()
        val savedNotes = mutableListOf<Note>()
        var lastTrashedAttachmentIds: List<String> = emptyList()
            private set
        private var nextId = 1000

        fun seed(note: Note, vararg rows: Attachment) {
            notes[note.id] = note
            rows.forEach { attachments[it.id] = it }
        }

        override fun getAllNotes(): Flow<List<Note>> =
            flowOf(notes.values.filterNot { it.trashed })

        override suspend fun getAllNotesSync(): List<Note> =
            notes.values.filterNot { it.trashed }

        override suspend fun addNote(note: Note) {
            val stored = if (note.id == 0) note.copy(id = nextId++) else note
            notes[stored.id] = stored
            savedNotes += stored
        }

        override suspend fun updateNote(note: Note) {
            notes[note.id] = note
            savedNotes += note
        }

        override suspend fun deleteNote(note: Note) {
            notes.remove(note.id)
        }

        override suspend fun saveNoteWithAttachments(
            note: Note,
            upsertAttachments: List<Attachment>,
            reviveAttachmentIds: List<String>,
            trashAttachmentIds: List<String>,
        ): Int {
            val stored = if (note.id == 0) note.copy(id = nextId++) else note
            notes[stored.id] = stored
            savedNotes += stored
            upsertAttachments.forEach { attachments[it.id] = it.copy(noteId = stored.id) }
            reviveAttachmentIds.forEach { id ->
                attachments[id]?.let { attachments[id] = it.copy(trashedAt = null) }
            }
            if (trashAttachmentIds.isNotEmpty()) {
                lastTrashedAttachmentIds = trashAttachmentIds
                val now = System.currentTimeMillis()
                trashAttachmentIds.forEach { id ->
                    attachments[id]?.let { attachments[id] = it.copy(trashedAt = now) }
                }
            }
            return stored.id
        }

        override suspend fun deleteNoteWithAttachments(note: Note): List<Attachment> {
            val removed = attachments.values.filter { it.noteId == note.id }
            removed.forEach { attachments.remove(it.id) }
            notes.remove(note.id)
            return removed
        }

        override suspend fun updatePinned(id: Int, pinned: Boolean) {
            notes[id]?.let { notes[id] = it.copy(pinned = pinned) }
        }

        override suspend fun updateTags(id: Int, tags: List<String>) {
            notes[id]?.let { notes[id] = it.copy(tags = tags) }
        }

        override suspend fun updateTrashed(id: Int, trashed: Boolean) {
            notes[id]?.let { notes[id] = it.copy(trashed = trashed) }
        }

        override suspend fun updatePlainDescription(id: Int, description: String) {
            notes[id]?.let { notes[id] = it.copy(description = description) }
        }

        override suspend fun updateVaultFields(
            id: Int,
            previousName: String,
            previousDescription: String,
            name: String,
            description: String,
        ): Int {
            val current = notes[id] ?: return 0
            if (!current.encrypted || current.name != previousName ||
                current.description != previousDescription
            ) {
                return 0
            }
            notes[id] = current.copy(name = name, description = description)
            return 1
        }

        override fun getNoteById(id: Int): Flow<Note> = flow {
            notes[id]?.let { emit(it) }
        }

        override fun getTrashedNotes(): Flow<List<Note>> =
            flowOf(notes.values.filter { it.trashed })

        override fun getNotesByTag(tag: String): Flow<List<Note>> =
            flowOf(notes.values.filter { note -> note.tags.any { it == tag } })

        override suspend fun getAllTags(): List<String> =
            notes.values.flatMap { it.tags }.distinct()

        override suspend fun batchUpdateNotes(notes: List<Note>) {
            notes.forEach { this.notes[it.id] = it }
        }

        override suspend fun sealLegacyPlaintextRows(): Int = 0

        override suspend fun sealLegacyCaptions(): Int = 0

        override suspend fun getAllAttachments(): List<Attachment> = attachments.values.toList()

        override suspend fun getAttachmentsForNote(noteId: Int): List<Attachment> =
            attachments.values.filter { it.noteId == noteId }

        override suspend fun upsertAttachments(attachments: List<Attachment>) {
            attachments.forEach { this.attachments[it.id] = it }
        }

        override suspend fun assignAttachmentsToNote(noteId: Int, ids: List<String>) {
            ids.forEach { id ->
                attachments[id]?.let { attachments[id] = it.copy(noteId = noteId) }
            }
        }

        override suspend fun deleteAttachments(ids: List<String>) {
            ids.forEach { attachments.remove(it) }
        }

        override suspend fun getAttachmentById(id: String): Attachment? = attachments[id]

        override fun observeAttachmentsByType(type: String): Flow<List<Attachment>> =
            flowOf(attachments.values.filter { it.type == type })

        override fun observeTrashedAttachments(): Flow<List<Attachment>> =
            flowOf(attachments.values.filter { it.trashedAt != null })

        override suspend fun setAttachmentTrashed(ids: List<String>, trashedAt: Long?) {
            ids.forEach { id ->
                attachments[id]?.let { attachments[id] = it.copy(trashedAt = trashedAt) }
            }
        }
    }
}

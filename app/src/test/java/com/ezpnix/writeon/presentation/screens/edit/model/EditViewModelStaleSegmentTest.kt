package com.ezpnix.writeon.presentation.screens.edit.model

import android.app.Application
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.attachment.AttachmentIndex
import com.ezpnix.writeon.core.security.EncryptionHelper
import com.ezpnix.writeon.core.widget.WidgetRefresher
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.repository.NoteRepository
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.presentation.screens.edit.segments.AttachmentSegment
import com.ezpnix.writeon.presentation.screens.edit.segments.TextSegment
import com.ezpnix.writeon.presentation.screens.edit.segments.segmentize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * REG-09 at the editor boundary: a disposed segment's late callback must not rewrite the note.
 *
 * The body below is exactly the shape the bug needed: an attachment between two text segments.
 * Once the attachment's line is deleted the body has no markers, so `currentTextSegment` used to
 * answer the stale `text:1` key with the whole-body segment and `onSegmentChange` replaced every
 * character with that field's text.
 */
@RunWith(RobolectricTestRunner::class)
class EditViewModelStaleSegmentTest {

    @Test
    fun `a stale key after the last attachment is deleted leaves the body alone`() {
        val viewModel = newViewModel()
        val withAttachment = "before\n![](attachment://a1b2c3d4)\nafter"
        viewModel.updateNoteDescription(TextFieldValue(withAttachment))
        val segments = segmentize(withAttachment)
        val stale = segments
            .filterIsInstance<TextSegment>()
            .first { it.key == "text:after:attachment:a1b2c3d4" }
        val attachment = segments
            .filterIsInstance<AttachmentSegment>()
            .single()

        // The user deletes the attachment line; the remaining body is one field, "text:0".
        viewModel.deleteAttachment(attachment)
        assertEquals("before\nafter", viewModel.noteDescription.value.text)

        // The disposed segment still delivers its last onValueChange with the old key.
        viewModel.onSegmentChange(stale, TextFieldValue("stale replacement"))

        assertEquals(
            "a stale callback must not destroy the preceding body",
            "before\nafter",
            viewModel.noteDescription.value.text
        )
    }

    @Test
    fun `a stale key on a plain body is ignored, not applied to the whole body`() {
        val viewModel = newViewModel()
        viewModel.updateNoteDescription(TextFieldValue("plain body"))

        val stale = TextSegment(
            startLine = 0,
            endLine = 0,
            startOffset = 0,
            endOffset = 0,
            text = "",
            key = "text:1"
        )
        viewModel.onSegmentChange(stale, TextFieldValue("other text"))

        assertEquals("plain body", viewModel.noteDescription.value.text)
        assertTrue(viewModel.noteDescription.value.selection.end <= "plain body".length)
    }

    @Test
    fun `a vault eviction clears the fields, both history stacks and the save undo`() {
        val viewModel = newViewModel()
        viewModel.updateNoteName(TextFieldValue("secret title"))
        viewModel.updateNoteDescription(TextFieldValue("secret"))
        viewModel.updateNoteDescription(TextFieldValue("secret body"))
        // Produce a redo entry too: undo moves the body snapshot into the redo stack.
        viewModel.undo()
        assertTrue(viewModel.canUndoText)
        assertTrue(viewModel.canRedoText)

        viewModel.markVaultLocked()

        assertEquals("", viewModel.noteName.value.text)
        assertEquals("", viewModel.noteDescription.value.text)
        assertEquals("undo history must not keep the plaintext", false, viewModel.canUndoText)
        assertEquals("redo history must not keep the plaintext", false, viewModel.canRedoText)
        assertEquals(false, viewModel.canUndoSave)

        // R2-04: the toolbar could still deliver a queued undo/redo after the clear.
        viewModel.undo()
        viewModel.redo()
        assertEquals("", viewModel.noteDescription.value.text)
    }

    private fun newViewModel(): EditViewModel {
        val application: Application = ApplicationProvider.getApplicationContext()
        val useCase = NoteUseCase(
            noteRepository = UnusedNoteRepository,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            encryptionHelper = EncryptionHelper(StringBuilder()),
            attachmentIndex = NoopAttachmentIndex,
            widgetRefresher = NoopWidgetRefresher,
            context = application,
        )
        return EditViewModel(
            application = application,
            noteUseCase = useCase,
            encryption = EncryptionHelper(StringBuilder()),
            savedStateHandle = SavedStateHandle(),
        )
    }

    /** Never called: the test drives the fields directly through the ViewModel's public API. */
    private object UnusedNoteRepository : NoteRepository {
        override fun getAllNotes(): Flow<List<Note>> = emptyFlow()
        override suspend fun getAllNotesSync(): List<Note> = error("unused")
        override suspend fun addNote(note: Note) = error("unused")
        override suspend fun updateNote(note: Note) = error("unused")
        override suspend fun deleteNote(note: Note) = error("unused")
        override suspend fun saveNoteWithAttachments(
            note: Note,
            upsertAttachments: List<Attachment>,
            reviveAttachmentIds: List<String>,
            trashAttachmentIds: List<String>,
        ): Int = error("unused")

        override suspend fun deleteNoteWithAttachments(note: Note): List<Attachment> = error("unused")
        override suspend fun updatePinned(id: Int, pinned: Boolean) = error("unused")
        override suspend fun updateTags(id: Int, tags: List<String>) = error("unused")
        override suspend fun updateTrashed(id: Int, trashed: Boolean) = error("unused")
        override suspend fun updatePlainDescription(id: Int, description: String) = error("unused")
        override suspend fun updateVaultFields(
            id: Int,
            previousName: String,
            previousDescription: String,
            name: String,
            description: String,
        ): Int = error("unused")

        override fun getNoteById(id: Int): Flow<Note> = emptyFlow()
        override fun getTrashedNotes(): Flow<List<Note>> = emptyFlow()
        override fun getNotesByTag(tag: String): Flow<List<Note>> = emptyFlow()
        override suspend fun getAllTags(): List<String> = error("unused")
        override suspend fun batchUpdateNotes(notes: List<Note>) = error("unused")
        override suspend fun sealLegacyPlaintextRows(): Int = 0
        override suspend fun sealLegacyCaptions(): Int = 0
        override suspend fun getAllAttachments(): List<Attachment> = error("unused")
        override suspend fun getAttachmentsForNote(noteId: Int): List<Attachment> = error("unused")
        override suspend fun upsertAttachments(attachments: List<Attachment>) = error("unused")
        override suspend fun assignAttachmentsToNote(noteId: Int, ids: List<String>) = error("unused")
        override suspend fun deleteAttachments(ids: List<String>) = error("unused")
        // The deletion of the line calls trashAttachment; an unknown id makes it return before it
        // touches any other row, which is all this test needs.
        override suspend fun getAttachmentById(id: String): Attachment? = null
        override fun observeAttachmentsByType(type: String): Flow<List<Attachment>> = emptyFlow()
        override fun observeTrashedAttachments(): Flow<List<Attachment>> = emptyFlow()
        override suspend fun setAttachmentTrashed(ids: List<String>, trashedAt: Long?) = error("unused")
    }

    private object NoopAttachmentIndex : AttachmentIndex {
        override fun put(attachments: Collection<Attachment>) = Unit
        override fun put(attachment: Attachment) = Unit
        override fun remove(ids: Collection<String>) = Unit
    }

    private object NoopWidgetRefresher : WidgetRefresher {
        override suspend fun refreshAll() = Unit
    }
}

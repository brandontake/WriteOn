package com.ezpnix.writeon.domain.usecase

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.glance.appwidget.updateAll
import com.ezpnix.writeon.core.attachment.AttachmentFiles
import com.ezpnix.writeon.data.repository.NoteRepositoryImpl
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.presentation.components.DecryptionResult
import com.ezpnix.writeon.presentation.components.EncryptionHelper
import com.ezpnix.writeon.presentation.components.markdown.AttachmentRegistry
import com.ezpnix.writeon.widget.NotesWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class NoteUseCase @Inject constructor(
    private val noteRepository: NoteRepositoryImpl,
    private val coroutineScope: CoroutineScope,
    private val encryptionHelper: EncryptionHelper,
    @ApplicationContext private val context: Context
) {
    var notes: List<Note> by mutableStateOf(emptyList())
        private set
    var tags: List<String> by mutableStateOf(emptyList())
        private set
    var decryptionResult: DecryptionResult by mutableStateOf(DecryptionResult.LOADING)
    private var observeKeysJob: Job? = null
    private var observeTagsJob: Job? = null

    fun observe() {
        observeNotes()
        observeTags()
        coroutineScope.launch { refreshAttachments() }
    }

    /**
     * Fills [AttachmentRegistry] with every id -> path pair.
     *
     * Note bodies only store ids, so without this a freshly started process renders every image and
     * recording as "unavailable" until that note happens to load its own rows. The registry is a
     * Compose state map, so screens already on screen pick the values up on the next frame.
     */
    suspend fun refreshAttachments() {
        val attachments = withContext(Dispatchers.IO) { noteRepository.getAllAttachments() }
        AttachmentRegistry.put(attachments)
    }

    suspend fun saveAttachments(items: List<Attachment>) {
        if (items.isEmpty()) return
        withContext(Dispatchers.IO) { noteRepository.upsertAttachments(items) }
        AttachmentRegistry.put(items)
    }

    suspend fun attachmentsForNote(noteId: Int): List<Attachment> =
        withContext(Dispatchers.IO) { noteRepository.getAttachmentsForNote(noteId) }

    /**
     * Hands rows written under the placeholder note id 0 to the note Room has just created.
     *
     * The DAO only rewrites unclaimed rows, so replaying this for a note that already owns its
     * attachments cannot take them away from another note.
     */
    suspend fun assignAttachments(noteId: Int, ids: List<String>) {
        if (noteId == 0 || ids.isEmpty()) return
        withContext(Dispatchers.IO) { noteRepository.assignAttachmentsToNote(noteId, ids) }
    }

    suspend fun deleteAttachments(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) { noteRepository.deleteAttachments(ids) }
    }

    /**
     * Loads the notes once and stops.
     *
     * Widgets need this: [observe] starts collectors that call `NotesWidget().updateAll` on every
     * emission, so a collector started from inside a widget's own composition refreshed the widget,
     * which recomposed and started the collector again. Reading once outside the composition ends
     * that loop.
     */
    suspend fun loadNotesOnce() {
        val sourceNotes = withContext(Dispatchers.IO) { noteRepository.getAllNotes().first() }
        notes = withContext(Dispatchers.Default) { processNotes(sourceNotes) }
    }

    private fun processNotes(sourceNotes: List<Note>): List<Note> {
        val hasUnencryptedNotes = sourceNotes.any { !it.encrypted }
        if (!hasUnencryptedNotes && sourceNotes.isNotEmpty()) {
            this.decryptionResult = DecryptionResult.EMPTY
        }
        return sourceNotes.mapNotNull { note ->
            if (note.encrypted) {
                val (decryptedNote, status) = decryptNote(note)
                this.decryptionResult = status
                if (status == DecryptionResult.SUCCESS) decryptedNote else null
            } else {
                note
            }
        }
    }

    private fun observeNotes() {
        observeKeysJob?.cancel()
        observeKeysJob = coroutineScope.launch {
            getAllNotes().collectLatest { sourceNotes ->
                // Decrypting every note touches the Keystore, so it must stay off the main thread.
                val processedNotes = withContext(Dispatchers.Default) { processNotes(sourceNotes) }
                val previousIds = notes.map { it.id }
                this@NoteUseCase.notes = processedNotes
                // Refreshing on an unchanged list made the widget recompose for nothing.
                if (previousIds != processedNotes.map { it.id }) {
                    NotesWidget().updateAll(context)
                }
            }
        }
    }

    private fun observeTags() {
        observeTagsJob?.cancel()
        observeTagsJob = coroutineScope.launch {
            noteRepository.getAllNotes().collectLatest { notes ->
                val newTags = notes.flatMap { it.tags }
                    .map { it.trim().lowercase() }
                    .distinct()
                    .sorted()
                    .filter { it.isNotBlank() }
                tags = newTags
            }
        }
    }

    private fun encryptNote(note: Note): Note {
        if (!note.encrypted) return note
        // Without a loaded vault passphrase, sealing the text would write ciphertext under an empty
        // key. EncryptionHelper.decrypt short-circuits to LOADING while the vault is empty, so that
        // note could never be read again and would silently drop out of the list. Keeping the text
        // in plain sight is recoverable; losing it is not. This path is only reached from the note
        // editor and never from the widget, which reads through NoteCrypto instead.
        if (encryptionHelper.isPasswordEmpty()) return note.copy(encrypted = false)
        return note.copy(
            name = encryptionHelper.encrypt(note.name),
            description = encryptionHelper.encrypt(note.description),
            encrypted = true
        )
    }

    private fun decryptNote(note: Note): Pair<Note, DecryptionResult> {
        val (decryptedName, nameResult) = encryptionHelper.decrypt(note.name)
        val (decryptedDescription, descriptionResult) = encryptionHelper.decrypt(note.description)
        return if (note.encrypted) {
            Pair(
                note.copy(
                    name = decryptedName ?: "",
                    description = decryptedDescription ?: "",
                ), descriptionResult
            )
        } else {
            Pair(note, DecryptionResult.SUCCESS)
        }
    }

    fun updateNotes(newNotes: List<Note>) {
        notes = processNotes(newNotes)
    }

    fun updateTags(newTags: List<String>) {
        tags = newTags
    }

    fun getAllNotesFlow(): Flow<List<Note>> = getAllNotes()

    private fun getAllNotes(): Flow<List<Note>> {
        return noteRepository.getAllNotes()
    }

    fun getNotesByTagFlow(tag: String): Flow<List<Note>> {
        return noteRepository.getNotesByTag(tag.trim().lowercase())
    }

    /**
     * Writes [note] back exactly as it was stored.
     *
     * [addNote] is the ordinary write path: it seals a vault note again and stamps nothing, but it is
     * still the wrong tool for an undo, because the row being restored is already in the form the
     * database must keep. Going through the repository writes those bytes untouched, which is what
     * makes a vault note, a plain note with tags, and a trashed flag all come back unchanged.
     */
    suspend fun restoreNote(note: Note) {
        noteRepository.updateNote(note)
    }

    suspend fun addNote(note: Note) {
        val noteToSave = if (note.encrypted) {
            encryptNote(note.copy(tags = note.tags.map { it.trim().lowercase() }.distinct()))
        } else {
            note.copy(tags = note.tags.map { it.trim().lowercase() }.distinct())
        }
        if (noteToSave.id == 0) {
            noteRepository.addNote(noteToSave)
        } else {
            noteRepository.updateNote(noteToSave)
        }
    }

    suspend fun batchUpdateNotes(notes: List<Note>) {
        val notesToSave = notes.map { note ->
            if (note.encrypted) encryptNote(note.copy(tags = note.tags.map { it.trim().lowercase() }.distinct())) else note
        }
        noteRepository.batchUpdateNotes(notesToSave)
    }

    suspend fun addTagToNote(noteId: Int, tag: String) {
        val trimmedTag = tag.trim().lowercase()
        if (trimmedTag.isNotBlank()) {
            val note = noteRepository.getNoteById(noteId).firstOrNull() ?: return
            if (trimmedTag !in note.tags.map { it.lowercase() }) {
                val updatedNote = note.copy(tags = note.tags + trimmedTag)
                noteRepository.updateNote(updatedNote)
            }
        }
    }

    suspend fun deleteTagFromNotes(tag: String) {
        val trimmedTag = tag.trim().lowercase()
        val allNotes = noteRepository.getAllNotes().first()
        val updatedNotes = allNotes.map { note ->
            val hasTag = trimmedTag in note.tags.map { it.lowercase() }
            if (hasTag) {
                val newTags = note.tags.filterNot { it.lowercase() == trimmedTag }
                note.copy(tags = newTags)
            } else {
                note
            }
        }.filter { note -> note.tags != allNotes.find { it.id == note.id }?.tags }

        if (updatedNotes.isNotEmpty()) {
            noteRepository.batchUpdateNotes(updatedNotes)
        }

        // Recompute from what the database actually holds rather than from the pre-update list, so
        // the tag list is right whether or not there was anything to update.
        val freshNotes = noteRepository.getAllNotes().first()
        val computedTags = freshNotes.flatMap { it.tags }
            .map { it.trim().lowercase() }
            .distinct()
            .sorted()
            .filter { it.isNotBlank() }
        updateTags(computedTags)
    }

    fun pinNote(note: Note) {
        coroutineScope.launch(NonCancellable + Dispatchers.IO) {
            addNote(note)
        }
    }

    fun deleteNoteById(id: Int) {
        coroutineScope.launch(NonCancellable + Dispatchers.IO) {
            val noteToDelete = noteRepository.getNoteById(id).firstOrNull() ?: return@launch
            deleteAssociatedFiles(noteToDelete)
            noteRepository.deleteNote(noteToDelete)
        }
    }

    fun getNoteById(id: Int): Flow<Note> {
        return noteRepository.getNoteById(id)
    }

    fun getLastNoteId(onResult: (Long?) -> Unit) {
        coroutineScope.launch(NonCancellable + Dispatchers.IO) {
            val lastNoteId = noteRepository.getLastNoteId()
            withContext(Dispatchers.Main) {
                onResult(lastNoteId)
            }
        }
    }

    suspend fun updatePinStatus(noteId: Int, pinned: Boolean) {
        // Tolerate a missing row: the editor pins through toggleNotePin, and for a note that has not
        // been inserted yet the id is still 0. Flow.first() on an absent row throws
        // NoSuchElementException, which escaped an uncaught viewModelScope coroutine and killed the
        // process while the user was still typing.
        val noteToUpdate = noteRepository.getNoteById(noteId).firstOrNull() ?: return
        val updatedNote = noteToUpdate.copy(pinned = pinned)
        noteRepository.updateNote(updatedNote)
    }

    fun moveToTrash(note: Note) {
        coroutineScope.launch(NonCancellable + Dispatchers.IO) {
            val trashedNote = note.copy(trashed = true)
            noteRepository.updateNote(trashedNote)
        }
    }

    fun restoreFromTrash(note: Note) {
        coroutineScope.launch(NonCancellable + Dispatchers.IO) {
            val restoredNote = note.copy(trashed = false)
            noteRepository.updateNote(restoredNote)
        }
    }

    fun permanentlyDeleteNoteById(id: Int) {
        coroutineScope.launch(NonCancellable + Dispatchers.IO) {
            val noteToDelete = noteRepository.getNoteById(id).firstOrNull() ?: return@launch
            deleteAssociatedFiles(noteToDelete)
            noteRepository.deleteNote(noteToDelete)
        }
    }

    /**
     * Removes everything a deleted note owned: attachment rows, the files they point at, and - for
     * bodies that still carry 1.x syntax - the recordings the old regex can find.
     *
     * Attachment rows are authoritative now, but the regex fallback stays because a vault note is
     * only rewritten when it is opened, so a password-protected note deleted from the trash can
     * still hold `?audio(...)` paths in its decrypted description.
     */
    private suspend fun deleteAssociatedFiles(note: Note) {
        try {
            val attachments = noteRepository.getAttachmentsForNote(note.id)
            if (attachments.isNotEmpty()) {
                AttachmentFiles.deleteAll(context, attachments)
                noteRepository.deleteAttachments(attachments.map { it.id })
                AttachmentRegistry.remove(attachments.map { it.id })
            }

            val text = "${note.name} ${note.description}"
            val legacyPaths = com.ezpnix.writeon.presentation.screens.edit.components.AudioRecorderManager.extractAudioPaths(text)
            if (legacyPaths.isNotEmpty()) {
                com.ezpnix.writeon.presentation.screens.edit.components.AudioRecorderManager.deleteAudioFiles(legacyPaths, context)
            }
        } catch (_: Throwable) {}
    }

    fun getTrashedNotesFlow(): Flow<List<Note>> = noteRepository.getTrashedNotes()
}
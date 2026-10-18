package com.ezpnix.writeon.domain.usecase

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.glance.appwidget.updateAll
import com.ezpnix.writeon.data.repository.NoteRepositoryImpl
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.presentation.components.DecryptionResult
import com.ezpnix.writeon.presentation.components.EncryptionHelper
import com.ezpnix.writeon.widget.NotesWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
        return if (note.encrypted) {
            note.copy(
                name = encryptionHelper.encrypt(note.name),
                description = encryptionHelper.encrypt(note.description),
                encrypted = true
            )
        } else {
            note
        }
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
            val note = noteRepository.getNoteById(noteId).first()
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
            val noteToDelete = noteRepository.getNoteById(id).first()
            deleteAssociatedAudios(noteToDelete)
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
        val noteToUpdate = noteRepository.getNoteById(noteId).first()
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
            val noteToDelete = noteRepository.getNoteById(id).first()
            deleteAssociatedAudios(noteToDelete)
            noteRepository.deleteNote(noteToDelete)
        }
    }

    private fun deleteAssociatedAudios(note: Note) {
        try {
            val text = "${note.name} ${note.description}"
            val paths = com.ezpnix.writeon.presentation.screens.edit.components.AudioRecorderManager.extractAudioPaths(text)
            if (paths.isNotEmpty()) {
                com.ezpnix.writeon.presentation.screens.edit.components.AudioRecorderManager.deleteAudioFiles(paths, context)
            }
        } catch (_: Throwable) {}
    }

    fun getTrashedNotesFlow(): Flow<List<Note>> = noteRepository.getTrashedNotes()
}
package com.ezpnix.writeon.presentation.screens.edit.model

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.presentation.components.DecryptionResult
import com.ezpnix.writeon.presentation.components.EncryptionHelper
import com.ezpnix.writeon.presentation.screens.edit.components.AudioRecorderManager
import com.ezpnix.writeon.presentation.screens.edit.components.UndoRedoState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EditViewModel @Inject constructor(
    private val application: Application,
    private val noteUseCase: NoteUseCase,
    private val encryption: EncryptionHelper
) : ViewModel() {
    private val _noteName = mutableStateOf(TextFieldValue())
    val noteName: State<TextFieldValue> get() = _noteName

    private val _isDescriptionInFocus = mutableStateOf(false)
    val isDescriptionInFocus: State<Boolean> get() = _isDescriptionInFocus

    private val _isEncrypted = mutableStateOf(false)
    val isEncrypted: State<Boolean> get() = _isEncrypted

    private val _noteDescription = mutableStateOf(TextFieldValue())
    val noteDescription: State<TextFieldValue> get() = _noteDescription

    private val _noteId = mutableIntStateOf(0)
    val noteId: State<Int> get() = _noteId

    private val _noteCreatedTime = mutableLongStateOf(System.currentTimeMillis())
    val noteCreatedTime: State<Long> get() = _noteCreatedTime

    private val _noteUpdatedTime = mutableLongStateOf(0L)
    val noteUpdatedTime: State<Long> get() = _noteUpdatedTime

    private val _isNoteInfoVisible = mutableStateOf(false)
    val isNoteInfoVisible: State<Boolean> get() = _isNoteInfoVisible

    private val _isEditMenuVisible = mutableStateOf(false)
    val isEditMenuVisible: State<Boolean> get() = _isEditMenuVisible

    private val _isPinned = mutableStateOf(false)
    val isPinned: State<Boolean> get() = _isPinned

    private val _isImageSaveRequested = mutableStateOf(false)
    val isImageSaveRequested: State<Boolean> get() = _isImageSaveRequested

    private val _originalNote = mutableStateOf<Note?>(null)
    private val undoRedoState = UndoRedoState()

    private var noteJob: Job? = null

    fun requestImageSave() {
        _isImageSaveRequested.value = true
    }

    fun clearImageSaveRequest() {
        _isImageSaveRequested.value = false
    }

    fun saveNote(id: Int) {
        viewModelScope.launch {
            // Pinning is already persisted by [toggleNotePin]. Rewriting it here compared
            // isPinned.value against _isPinned.value - the same state object - so the branch could
            // never be taken, and it aimed at the placeholder id 0 for a brand-new note anyway.

            val hasContent =
                noteName.value.text.isNotBlank() || noteDescription.value.text.isNotBlank()
            // An empty brand-new note is not worth storing, but an existing note must still be
            // written when the user clears every character, or the previous text silently survives.
            if (hasContent || id != 0) {
                val currentText = "${noteName.value.text} ${noteDescription.value.text}"
                val currentAudioPaths = AudioRecorderManager.extractAudioPaths(currentText)
                _originalNote.value?.let { old ->
                    val oldText = "${old.name} ${old.description}"
                    val oldAudioPaths = AudioRecorderManager.extractAudioPaths(oldText)
                    val removedAudioPaths = oldAudioPaths - currentAudioPaths
                    if (removedAudioPaths.isNotEmpty()) {
                        AudioRecorderManager.deleteAudioFiles(
                            removedAudioPaths,
                            application.applicationContext
                        )
                    }
                }

                val now = System.currentTimeMillis()
                // Fall back to the stored row rather than a blank Note: when the editor is saved
                // before getNoteById has emitted, _originalNote is still null and the old code
                // replaced the whole row with tags = emptyList(), silently dropping the note's
                // tags. Reading the row keeps every field this screen does not edit.
                val base = _originalNote.value ?: if (id != 0) {
                    runCatching { noteUseCase.getNoteById(id).first() }.getOrNull()
                } else {
                    null
                }
                // A note may only be flagged encrypted when a vault passphrase is actually loaded,
                // otherwise EncryptionHelper would seal it with an empty key and no screen in the
                // app can decrypt it again.
                val encrypted = isEncrypted.value && !encryption.isPasswordEmpty()
                val note = base?.copy(
                    name = noteName.value.text,
                    description = noteDescription.value.text,
                    pinned = isPinned.value,
                    encrypted = encrypted,
                    createdAt = if (noteCreatedTime.value != 0L) noteCreatedTime.value else now,
                    updatedAt = now
                ) ?: Note(
                    id = id,
                    name = noteName.value.text,
                    description = noteDescription.value.text,
                    pinned = isPinned.value,
                    encrypted = encrypted,
                    createdAt = if (noteCreatedTime.value != 0L) noteCreatedTime.value else now,
                    updatedAt = now,
                    tags = emptyList()
                )
                noteUseCase.addNote(note)
            }

            fetchLastNoteAndUpdate()
        }
    }

    private fun syncNote(note: Note) {
        _originalNote.value = note
        if (note.encrypted) {
            val (name, nameStatus) = encryption.decrypt(note.name)
            val (description, descriptionStatus) = encryption.decrypt(note.description)
            if (nameStatus == DecryptionResult.SUCCESS && descriptionStatus == DecryptionResult.SUCCESS) {
                updateNoteName(TextFieldValue(name!!, selection = TextRange(name.length)))
                updateNoteDescription(TextFieldValue(description!!, selection = TextRange(description.length)))
            }
        } else {
            updateNoteName(TextFieldValue(note.name, selection = TextRange(note.name.length)))
            updateNoteDescription(TextFieldValue(note.description, selection = TextRange(note.description.length)))
        }
        updateNoteCreatedTime(note.createdAt)
        _noteUpdatedTime.longValue = note.updatedAt
        updateNoteId(note.id)
        updateNotePin(note.pinned)
        updateIsEncrypted(note.encrypted)
    }

    /**
     * Follows the stored note for [id] and keeps the editor fields in step with it.
     *
     * The collector is cancelled before a new one is started. It used to be launched and forgotten,
     * so calling this again for the same note left a second live collector writing the same fields
     * from the same flow, and there was no way to stop observing a note the editor had moved on from.
     */
    fun setupNoteData(id: Int = noteId.value) {
        noteJob?.cancel()
        noteJob = null
        if (id != 0) {
            noteJob = viewModelScope.launch {
                noteUseCase.getNoteById(id).collectLatest { note ->
                    syncNote(note)
                }
            }
        }
    }

    private fun fetchLastNoteAndUpdate() {
        if (noteName.value.text.isNotEmpty() || noteDescription.value.text.isNotBlank()) {
            if (noteId.value == 0) {
                viewModelScope.launch {
                    noteUseCase.getLastNoteId { lastId ->
                        viewModelScope.launch {
                            setupNoteData(lastId?.toInt() ?: 1)
                        }
                    }
                }
            }
        }
    }

    fun toggleEditMenuVisibility(value: Boolean) {
        _isEditMenuVisible.value = value
    }

    fun toggleNoteInfoVisibility(value: Boolean) {
        _isNoteInfoVisible.value = value
    }

    fun toggleIsDescriptionInFocus(value: Boolean) {
        _isDescriptionInFocus.value = value
    }

    fun toggleNotePin(value: Boolean) {
        _isPinned.value = value
        viewModelScope.launch {
            noteUseCase.updatePinStatus(noteId.value, value)
        }
    }

    fun updateNoteName(newName: TextFieldValue) {
        _noteName.value = newName
        undoRedoState.onInput(newName)
    }

    fun updateIsEncrypted(value: Boolean) {
        _isEncrypted.value = value
    }

    fun updateNoteDescription(newDescription: TextFieldValue) {
        _noteDescription.value = newDescription
        undoRedoState.onInput(newDescription)
    }

    private fun updateNoteCreatedTime(newTime: Long) {
        _noteCreatedTime.longValue = newTime
    }

    private fun updateNotePin(pinned: Boolean) {
        _isPinned.value = pinned
    }

    fun updateNoteId(newId: Int) {
        _noteId.intValue = newId
    }

    fun deleteNote(id: Int) {
        noteUseCase.deleteNoteById(id = id)
    }

    fun undo() {
        undoRedoState.undo()
        _noteDescription.value = undoRedoState.input
    }

    fun redo() {
        undoRedoState.redo()
        _noteDescription.value = undoRedoState.input
    }

    private fun isSelectorAtStartOfNonEmptyLine(): Boolean {
        val text = _noteDescription.value.text
        val selectionStart = _noteDescription.value.selection.start
        if (selectionStart == 0) {
            return true
        }
        return text[selectionStart - 1] == '\n'
    }

    private fun getIntRangeForCurrentLine(): IntRange {
        val text = _noteDescription.value.text
        val selectionStart = _noteDescription.value.selection.start
        val selectionEnd = _noteDescription.value.selection.end
        var lineStart = selectionStart
        var lineEnd = selectionEnd
        while (lineStart > 0 && text[lineStart - 1] != '\n') {
            lineStart--
        }
        while (lineEnd < text.length && text[lineEnd] != '\n') {
            lineEnd++
        }
        return IntRange(lineStart, lineEnd - 1)
    }

    fun insertNumberedText(newLine: Boolean = true) {
        val text = _noteDescription.value.text
        val currentCaretPosition = _noteDescription.value.selection.start
        val lastNumber = getLastNumberBeforeCaret(text, currentCaretPosition)
        if (lastNumber != null && isSeparated(text, currentCaretPosition)) {
            _currentNumber.value = 1
        } else {
            _currentNumber.value = lastNumber?.plus(1) ?: 1
        }
        val textToInsert = "${_currentNumber.value}."
        insertText(textToInsert, newLine = newLine)
        _currentNumber.value++
    }

    fun getLastNumberBeforeCaret(text: String, caretPosition: Int): Int? {
        val regex = Regex("""(\d+)\.""")
        val matches = regex.findAll(text.substring(0, caretPosition))
        val numbers = matches.map { it.groupValues[1].toInt() }.toList()
        return numbers.lastOrNull()
    }

    fun isSeparated(text: String, caretPosition: Int): Boolean {
        val beforeCaret = text.substring(0, caretPosition)
        return beforeCaret.endsWith("\n\n") || beforeCaret.trim().isEmpty()
    }

    private val _currentNumber = mutableStateOf(1)
    val currentNumber: State<Int> get() = _currentNumber

    fun insertText(insertText: String, offset: Int = 1, newLine: Boolean = true) {
        val currentText = _noteDescription.value.text
        val resultSelectionIndex: Int
        val rangeOfCurrentLine = getIntRangeForCurrentLine()
        val updatedText = if (!rangeOfCurrentLine.isEmpty()) {
            val currentLineContents = currentText.substring(rangeOfCurrentLine)
            @Suppress("NAME_SHADOWING") val newLine = if (isSelectorAtStartOfNonEmptyLine()) {
                insertText + currentLineContents
            } else {
                if (newLine) {
                    currentLineContents + "\n" + insertText
                } else {
                    currentLineContents + insertText
                }
            }
            resultSelectionIndex = rangeOfCurrentLine.first + newLine.length - 1
            currentText.replaceRange(rangeOfCurrentLine, newLine)
        } else {
            resultSelectionIndex = (currentText + insertText).length
            currentText + insertText
        }
        _noteDescription.value = TextFieldValue(
            text = updatedText,
            selection = TextRange(resultSelectionIndex + offset)
        )
    }
}
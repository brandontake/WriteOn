package com.ezpnix.writeon.presentation.screens.home.viewmodel

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ezpnix.writeon.R
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.presentation.components.DecryptionResult
import com.ezpnix.writeon.presentation.components.EncryptionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    val encryptionHelper: EncryptionHelper,
    val noteUseCase: NoteUseCase,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    var selectedNotes = mutableStateListOf<Note>()
    private val _isDeleteMode = mutableStateOf(false)
    val isDeleteMode: State<Boolean> get() = _isDeleteMode
    private val _isPasswordPromptVisible = mutableStateOf(false)
    val isPasswordPromptVisible: State<Boolean> get() = _isPasswordPromptVisible
    private val _isVaultMode = mutableStateOf(false)
    val isVaultMode: State<Boolean> get() = _isVaultMode
    private val _searchQuery = mutableStateOf("")
    val searchQuery: State<String> get() = _searchQuery
    var showTagDialog = mutableStateOf(false)
    var showRemoveTagDialog = mutableStateOf(false)
    var newTagName = mutableStateOf("")
    var selectedTagsToRemove = mutableStateListOf<String>()
    var currentFilter = mutableStateOf("all")

    init {
        noteUseCase.observe()
    }

    fun toggleIsDeleteMode(enabled: Boolean) {
        _isDeleteMode.value = enabled
    }

    fun toggleIsVaultMode(enabled: Boolean) {
        _isVaultMode.value = enabled
        if (!enabled) {
            noteUseCase.decryptionResult = DecryptionResult.LOADING
        }
        noteUseCase.observe()
    }

    fun toggleIsPasswordPromptVisible(enabled: Boolean) {
        _isPasswordPromptVisible.value = enabled
    }

    fun changeSearchQuery(newValue: String) {
        _searchQuery.value = newValue
    }

    fun setFilter(filter: String) {
        currentFilter.value = filter
    }

    fun pinOrUnpinNotes() {
        // Every selected note is set to the same target so a mixed selection pins as a group.
        val pinTo = selectedNotes.any { !it.pinned }
        updateSelectedNotes { it.copy(pinned = pinTo) }
    }

    fun pinOrUnpinNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            noteUseCase.batchUpdateNotes(listOf(note.copy(pinned = !note.pinned)))
            launch(Dispatchers.Main) {
                noteUseCase.observe()
            }
        }
    }

    fun moveSelectedNotesToTrash() {
        updateSelectedNotes { it.copy(trashed = true) }
    }

    /**
     * Shared write path for the selection toolbar: applies [update] to a snapshot of the current
     * selection, persists it as one batch and refreshes the list on the main thread, clearing the
     * selection afterwards. Pin, trash and restore all used to carry their own copy of this
     * coroutine scaffolding.
     */
    private fun updateSelectedNotes(update: (Note) -> Note) = mutateSelection { notes ->
        noteUseCase.batchUpdateNotes(notes.map(update))
    }

    /** Same write path for toolbar actions that act per note instead of persisting a batch. */
    private fun forEachSelectedNote(action: suspend (Note) -> Unit) = mutateSelection { notes ->
        notes.forEach { action(it) }
    }

    private fun mutateSelection(write: suspend (List<Note>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            write(selectedNotes.toList())
            launch(Dispatchers.Main) {
                selectedNotes.clear()
                noteUseCase.observe()
            }
        }
    }

    fun addTagToSelectedNotes() {
        val trimmedTag = newTagName.value.trim().lowercase()
        if (trimmedTag.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                val updatedNotes = selectedNotes.map { note ->
                    if (trimmedTag !in note.tags.map { it.lowercase() }) {
                        note.copy(tags = note.tags + trimmedTag)
                    } else {
                        note
                    }
                }.filter { it.tags != selectedNotes.find { n -> n.id == it.id }?.tags }
                if (updatedNotes.isNotEmpty()) {
                    noteUseCase.batchUpdateNotes(updatedNotes)
                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.tag_added_count, trimmedTag, updatedNotes.size),
                            Toast.LENGTH_SHORT
                        ).show()
                        selectedNotes.clear()
                        newTagName.value = ""
                        noteUseCase.observe()
                    }
                } else {
                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.tag_already_exists, trimmedTag),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.tag_name_empty),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun removeTagsFromSelectedNotes() {
        if (selectedTagsToRemove.isEmpty()) {
            Toast.makeText(
                context,
                context.getString(R.string.tag_none_selected),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val updatedNotes = selectedNotes.map { note ->
                val updatedTags = note.tags.filterNot { it.lowercase() in selectedTagsToRemove.map { it.lowercase() } }
                if (updatedTags != note.tags) {
                    note.copy(tags = updatedTags)
                } else {
                    note
                }
            }.filter { it.tags != selectedNotes.find { n -> n.id == it.id }?.tags }
            if (updatedNotes.isNotEmpty()) {
                noteUseCase.batchUpdateNotes(updatedNotes)
                // The tag index has to be rebuilt from the persisted rows, so re-read them instead
                // of patching in memory. `first()` is a suspend call, so this reads the flow
                // without blocking a thread the way `runBlocking` did.
                val currentNotes = noteUseCase.getAllNotesFlow().first()
                val newTags = currentNotes.flatMap { it.tags }.map { it.trim().lowercase() }.distinct().sorted()
                noteUseCase.updateTags(newTags)
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.tag_removed_count, selectedTagsToRemove.size, updatedNotes.size),
                        Toast.LENGTH_SHORT
                    ).show()
                    selectedNotes.clear()
                    selectedTagsToRemove.clear()
                    noteUseCase.observe()
                }
            } else {
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.tag_none_removed),
                        Toast.LENGTH_SHORT
                    ).show()
                    selectedNotes.clear()
                    selectedTagsToRemove.clear()
                    noteUseCase.observe()
                }
            }
        }
    }

    fun deleteTag(tag: String) {
        viewModelScope.launch(Dispatchers.IO) {
            noteUseCase.deleteTagFromNotes(tag)
            launch(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (noteUseCase.tags.contains(tag.lowercase())) context.getString(R.string.tag_still_in_use, tag) else context.getString(R.string.tag_deleted, tag),
                    Toast.LENGTH_SHORT
                ).show()
                if (currentFilter.value.lowercase() == tag.lowercase()) {
                    setFilter("all")
                }
                noteUseCase.observe()
            }
        }
    }

    fun restoreNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            noteUseCase.restoreFromTrash(note)
            launch(Dispatchers.Main) {
                noteUseCase.observe()
            }
        }
    }

    fun permanentlyDeleteNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            noteUseCase.permanentlyDeleteNoteById(note.id)
            launch(Dispatchers.Main) {
                noteUseCase.observe()
            }
        }
    }

    fun restoreSelectedNotes() {
        updateSelectedNotes { it.copy(trashed = false) }
    }

    fun permanentlyDeleteSelectedNotes() {
        forEachSelectedNote { noteUseCase.permanentlyDeleteNoteById(it.id) }
    }
}
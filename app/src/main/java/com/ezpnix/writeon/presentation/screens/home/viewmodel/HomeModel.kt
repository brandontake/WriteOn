package com.ezpnix.writeon.presentation.screens.home.viewmodel

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ezpnix.writeon.R
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.core.security.EncryptionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    val encryptionHelper: EncryptionHelper,
    private val noteUseCase: NoteUseCase,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    var selectedNotes = mutableStateListOf<Note>()
    private val _isDeleteMode = mutableStateOf(false)
    val isDeleteMode: State<Boolean> get() = _isDeleteMode
    /**
     * Set once the user has tried a vault password this session, so the lock banner can say
     * "wrong password" instead of silently staying where it is.
     */
    private val _vaultUnlockAttempted = mutableStateOf(false)
    val vaultUnlockAttempted: State<Boolean> get() = _vaultUnlockAttempted
    private val _searchQuery = mutableStateOf("")
    val searchQuery: State<String> get() = _searchQuery
    var showTagDialog = mutableStateOf(false)
    var showRemoveTagDialog = mutableStateOf(false)
    var newTagName = mutableStateOf("")
    var selectedTagsToRemove = mutableStateListOf<String>()
    var currentFilter = mutableStateOf("all")

    // T4: the home composable reads these through its ViewModel instead of reaching into the use
    // case. The underlying properties are still Compose state, so recomposition behaviour is
    // unchanged; what changes is that presentation no longer knows about NoteUseCase.
    val notes: List<Note> get() = noteUseCase.notes
    val tags: List<String> get() = noteUseCase.tags
    val hiddenVaultCount: Int get() = noteUseCase.hiddenVaultCount
    val decryptionResult get() = noteUseCase.decryptionResult
    val decryptFailedCount get() = noteUseCase.decryptFailedCount
    val trashedNotesFlow get() = noteUseCase.getTrashedNotesFlow()
    val trashedAttachmentsFlow get() = noteUseCase.getTrashedAttachmentsFlow()

    init {
        noteUseCase.observe()
    }

    /** Saves an edited card body. Used by the home feed's inline edits. */
    fun saveNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) { noteUseCase.addNote(note) }
    }

    fun toggleIsDeleteMode(enabled: Boolean) {
        _isDeleteMode.value = enabled
    }

    /**
     * Loads a vault passphrase for this session and re-reads the note list.
     *
     * The passphrase lives in memory only (EncryptionHelper holds a StringBuilder, not persisted
     * state), so it has to be re-entered after every process death - the same contract the vault
     * had before its UI was removed. Re-running [NoteUseCase.observe] re-decrypts every row with
     * the new passphrase; rows that still fail keep counting toward the lock banner, which is how
     * a wrong password is reported.
     */
    fun unlockVault(password: String) {
        _vaultUnlockAttempted.value = true
        encryptionHelper.setPassword(password)
        noteUseCase.observe()
        // A4: the unlock is the only moment the passphrase is in memory, so it is also the only
        // moment a note still sealed with the pre-PBKDF2 (single SHA-256) layout can be re-sealed
        // with the current one. Without this the weak layout survived until the user happened to
        // open *and save* that exact note. The sweep authenticates every row before writing, so a
        // wrong passphrase leaves all of them byte-for-byte untouched.
        viewModelScope.launch(Dispatchers.IO) {
            val upgraded = noteUseCase.upgradeLegacyVaultRows()
            if (upgraded > 0) {
                Log.i(TAG, "Re-sealed $upgraded vault note(s) with the current PBKDF2 layout")
            }
        }
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
        // Flag-only writes: writing the whole row back would restore the body this screen read
        // before the editor saved.
        mutateSelection { notes -> notes.forEach { noteUseCase.updatePinStatus(it.id, pinTo) } }
    }

    fun pinOrUnpinNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            noteUseCase.updatePinStatus(note.id, !note.pinned)
            launch(Dispatchers.Main) {
                noteUseCase.observe()
            }
        }
    }

    fun moveSelectedNotesToTrash() {
        mutateSelection { notes -> notes.forEach { noteUseCase.setTrashed(it.id, true) } }
    }

    /**
     * U9: trashes one note and calls [onTrashed] only after the write committed.
     *
     * The old fire-and-forget call let an "undo" run before the trash flag landed, so the undo
     * restored a row that the late write then trashed again. The callback is also what lets the
     * home screen offer a snackbar only once the move is real.
     */
    fun trashNote(note: Note, onTrashed: (Note) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            noteUseCase.setTrashed(note.id, true)
            launch(Dispatchers.Main) { onTrashed(note) }
        }
    }

    /** Same contract as [trashNote] for a multi-selection. */
    fun trashNotes(notes: List<Note>, onTrashed: (List<Note>) -> Unit) {
        val snapshot = notes.toList()
        viewModelScope.launch(Dispatchers.IO) {
            snapshot.forEach { noteUseCase.setTrashed(it.id, true) }
            launch(Dispatchers.Main) {
                selectedNotes.clear()
                onTrashed(snapshot)
            }
        }
    }

    /** Undo for [trashNote]/[trashNotes]: puts the rows back and refreshes the list afterwards. */
    fun restoreTrashedNotes(notes: List<Note>) {
        if (notes.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            notes.forEach { noteUseCase.setTrashed(it.id, false) }
            launch(Dispatchers.Main) { noteUseCase.observe() }
        }
    }

    /** Shared write path for toolbar actions that act per note instead of persisting a batch. */
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
                // Same id -> tags snapshot as removeTagsFromSelectedNotes: comparing against a map
                // costs one lookup per note instead of a `find` that rescans the whole selection.
                val previousTagsById = HashMap<Int, List<String>>()
                selectedNotes.forEach { note ->
                    if (note.id !in previousTagsById) previousTagsById[note.id] = note.tags
                }
                val updatedNotes = selectedNotes.map { note ->
                    if (trimmedTag !in note.tags.map { it.lowercase() }) {
                        note.copy(tags = note.tags + trimmedTag)
                    } else {
                        note
                    }
                }.filter { it.tags != previousTagsById[it.id] }
                if (updatedNotes.isNotEmpty()) {
                    // Tags only; the bodies stay as the database has them.
                    updatedNotes.forEach { noteUseCase.setTags(it.id, it.tags) }
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
            // `selectedTagsToRemove` used to be lowercased inside the per-tag predicate, so a note
            // with five tags and three selected allocated fifteen strings, and the "did anything
            // change" check ran a linear `find` for every note. Both are one pass now; the map
            // keeps the first match for an id, exactly like the `find` it replaces.
            val removedTags = selectedTagsToRemove.mapTo(HashSet()) { it.lowercase() }
            val previousTagsById = HashMap<Int, List<String>>()
            selectedNotes.forEach { note ->
                if (note.id !in previousTagsById) previousTagsById[note.id] = note.tags
            }
            val updatedNotes = selectedNotes.map { note ->
                val updatedTags = note.tags.filterNot { it.lowercase() in removedTags }
                if (updatedTags != note.tags) {
                    note.copy(tags = updatedTags)
                } else {
                    note
                }
            }.filter { it.tags != previousTagsById[it.id] }
            if (updatedNotes.isNotEmpty()) {
                // Tags only; never write back a body from this selection snapshot.
                updatedNotes.forEach { noteUseCase.setTags(it.id, it.tags) }
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

    /** Puts one trashed attachment back into its note and refreshes the id -> path registry. */
    fun restoreAttachment(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            noteUseCase.restoreAttachment(id)
            launch(Dispatchers.Main) { noteUseCase.refreshAttachments() }
        }
    }

    /**
     * Moves one attachment shown on a home card to the recycle bin.
     *
     * The use case also removes the `attachment://` line from the note body, so the card stops
     * showing the recording and reopening the note does not resurrect it. The list is refreshed
     * afterwards because the note itself changed, not only the attachment row.
     */
    fun deleteAttachment(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            noteUseCase.trashAttachment(id)
            launch(Dispatchers.Main) { noteUseCase.observe() }
        }
    }

    /** Empties one attachment from the recycle bin: row and file both go. */
    fun permanentlyDeleteAttachment(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            noteUseCase.permanentlyDeleteAttachment(id)
        }
    }

    fun restoreSelectedNotes() {
        // Flag-only write: restoring from the trash must not rewrite any body from this snapshot.
        mutateSelection { notes -> notes.forEach { noteUseCase.setTrashed(it.id, false) } }
    }

    fun permanentlyDeleteSelectedNotes() {
        forEachSelectedNote { noteUseCase.permanentlyDeleteNoteById(it.id) }
    }

    private companion object {
        const val TAG = "HomeViewModel"
    }
}
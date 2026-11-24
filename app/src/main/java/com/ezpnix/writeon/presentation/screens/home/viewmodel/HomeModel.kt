package com.ezpnix.writeon.presentation.screens.home.viewmodel

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.security.DecryptionResult
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.core.security.EncryptionHelper
import com.ezpnix.writeon.core.util.NoteBodyEditLock
import com.ezpnix.writeon.presentation.lock.LockManager
import com.ezpnix.writeon.presentation.screens.edit.model.CheckboxLineToggle
import com.ezpnix.writeon.presentation.screens.edit.model.EditDraftStore
import com.ezpnix.writeon.presentation.screens.home.widgets.NoteSearchState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Synthetic filter value for notes that have no tags.
 *
 * `currentFilter` holds a tag name for normal tag rows, so this value must not collide with a real
 * tag. A leading NUL cannot survive `String.trim()`/normal tag entry and is not shown anywhere.
 */
const val FILTER_UNTAGGED = "\u0000untagged"

/** Whether [note] belongs to the drawer's current filter. */
internal fun noteMatchesFilter(note: Note, filter: String): Boolean = when (filter) {
    "all" -> true
    "pinned" -> note.pinned
    FILTER_UNTAGGED -> note.tags.isEmpty()
    // The tag index is lowercased, while a row migrated from an older build can still carry the
    // original casing; matching trimmed and case-insensitively keeps the row and the filter in step.
    else -> note.tags.any { it.trim().equals(filter, ignoreCase = true) }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    val encryptionHelper: EncryptionHelper,
    private val noteUseCase: NoteUseCase,
    private val lockManager: LockManager,
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

    /**
     * PERF-04: the notes matching [searchQuery], computed off the main thread and debounced.
     *
     * The grid used to run `filterNotes` in composition on every query change, which put a
     * whole-library scan of names and bodies on the UI thread for the first character and every
     * backspace. The scan now runs after the typing settles, on [Dispatchers.Default], and the
     * previous result stays published while the next one is computed.
     *
     * Seeded from the list [NoteUseCase] already holds: starting at `emptyList()` made the first
     * frame after the library loaded (or after a rotation/navigation back) draw the "no notes"
     * placeholder until the reconstructed pipeline caught up. A blank query skips the pipeline
     * entirely - see [observeSearch] and [changeSearchQuery].
     *
     * The tag filter and sort still belong to the screen: they apply on top of this narrowed list.
     */
    private val _filteredNotes = mutableStateOf(noteUseCase.notes)
    val filteredNotes: State<List<Note>> get() = _filteredNotes

    /** Guards the narrowing state below; both collectors map on Default and can overlap. */
    private val searchStateLock = Any()
    private var searchState: NoteSearchState? = null
    private var searchStateNotes: List<Note>? = null

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
    /**
     * PERF-07: collected by the drawer for its badge. Lazily kept as one instance so a
     * recomposition of the drawer cannot restart the collection with a brand-new cold flow.
     */
    val trashedNoteCountFlow: Flow<Int> by lazy { noteUseCase.getTrashedNoteCountFlow() }
    val trashedAttachmentsFlow get() = noteUseCase.getTrashedAttachmentsFlow()

    /**
     * Serialises card edits (checkbox toggles and title renames) so a second tap or rename reads
     * the text the first one wrote.
     */
    /**
     * R3-04: shared with the launcher widget so a card tap and a widget tap cannot both start from
     * the same stale body. The editor's own save has its own single-flight.
     */
    private val cardEditMutex = NoteBodyEditLock.mutex

    init {
        // PERF-3: the one collector pair for the whole app. Room's own emission is what refreshes
        // the list after a pin/tag/delete, so those actions no longer restart it (see the methods
        // below); restarting was re-querying and re-decrypting the library after every change.
        noteUseCase.observe()
        observeSearch()
        observeLockState()
    }

    /**
     * SEC-09: this screen holds decrypted copies the lock manager does not know about.
     *
     * [selectedNotes] and the narrowed search result are `Note` objects with plaintext names and
     * bodies, captured before the lock. `NoteUseCase.clearSensitiveState` drops its own list, but
     * these references would keep the same strings alive. Both are cleared the moment the app locks
     * or the Vault session ends; the shared collectors repopulate the feed after an unlock.
     */
    private fun observeLockState() {
        viewModelScope.launch {
            lockManager.isLocked.collect { locked ->
                if (locked) clearDecryptedSelection()
            }
        }
        viewModelScope.launch {
            lockManager.vaultSessionActive.collect { active ->
                if (!active) clearDecryptedSelection()
            }
        }
    }

    private fun clearDecryptedSelection() {
        selectedNotes.clear()
        selectedTagsToRemove.clear()
        _filteredNotes.value = emptyList()
        // The search index is another copy of the decrypted list. Dropping it under the same lock
        // the pipeline uses means a scan that is halfway through cannot publish into a home screen
        // the lock has already cleared.
        synchronized(searchStateLock) {
            searchState = null
            searchStateNotes = null
        }
    }

    /**
     * PERF-04: the debounced, off-main search pipeline.
     *
     * A blank query never reaches the scan: the full list is published in the same collector turn,
     * so the frame that first sees a loaded library is not the "no notes" placeholder. Anything
     * typed waits [SEARCH_DEBOUNCE_MS] so a burst of keystrokes runs one scan instead of one per
     * character. `collectLatest` keeps the previous result on screen until the new scan publishes,
     * and the scan itself only walks the library on Default.
     */
    private fun observeSearch() {
        viewModelScope.launch {
            snapshotFlow { noteUseCase.notes to _searchQuery.value }
                .distinctUntilChanged()
                .collectLatest { (notes, query) ->
                    if (query.isBlank()) {
                        // No narrowing to do: hand the whole list over without an extra dispatch.
                        _filteredNotes.value = notes
                        return@collectLatest
                    }
                    delay(SEARCH_DEBOUNCE_MS)
                    val filtered = withContext(Dispatchers.Default) {
                        synchronized(searchStateLock) {
                            // The narrowing state belongs to one snapshot of the list; a new list
                            // invalidates every result computed from the previous one.
                            if (searchStateNotes !== notes) {
                                searchState = NoteSearchState(notes)
                                searchStateNotes = notes
                            }
                            searchState!!.filter(query)
                        }
                    }
                    _filteredNotes.value = filtered
                }
        }
    }

    /** Saves an edited card body. Used by the home feed's inline edits. */
    fun saveNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            guardCardWrite {
                // R3-06: sealing a vault row must not interleave with an unlock that temporarily loads
                // a candidate passphrase into the process-wide EncryptionHelper.
                if (note.encrypted) {
                    lockManager.withVaultUnlock { noteUseCase.addNote(note) }
                } else {
                    noteUseCase.addNote(note)
                }
            }
        }
    }

    /**
     * R4-04: a card write must never take the process down.
     *
     * `NoteUseCase.addNote` refuses to seal a vault row once the session's passphrase is gone
     * (`encryptNote`'s `check`), and a Vault eviction can land between the card's read and its
     * write. The widget already guarded its write; these paths did not, so that race surfaced as
     * an uncaught `IllegalStateException` on `Dispatchers.IO` - a crash. A failure is also worth
     * telling the user about: the tap did not save.
     */
    private suspend fun guardCardWrite(block: suspend () -> Unit): Boolean =
        runGuardedCardWrite(
            onFailure = { error ->
                Log.w(TAG, "Could not save the card edit", error)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.msg_note_save_failed, Toast.LENGTH_SHORT).show()
                }
            },
            block = block,
        ) != null

    /**
     * UX-09: renames a note from its card without rewriting the body the card captured.
     *
     * The title field's checkbox edit used to go through [saveNote] with
     * `note.copy(name = ...)`, so a body changed since the card was composed was written back from
     * that old snapshot. This reads the latest row and changes only the name.
     *
     * R3-06: the read-modify-write runs under the same Vault-unlock lock as the editor's save, so a
     * concurrent unlock cannot make it seal the row with a candidate passphrase.
     */
    fun updateNoteTitle(noteId: Int, name: String) {
        if (noteId == 0) return
        viewModelScope.launch(Dispatchers.IO) {
            guardCardWrite {
                lockManager.withVaultUnlock {
                    updateTitleInLatestNote(
                        noteId = noteId,
                        name = name,
                        mutex = cardEditMutex,
                        readLatest = { id -> noteUseCase.getEditableNote(id) },
                        write = { note -> noteUseCase.addNote(note) },
                    )
                }
            }
        }
    }

    /**
     * UX-09: toggles one checkbox of a card against the latest stored body.
     *
     * A card captured its note (and summary) when it was composed, so two quick taps both rewrote
     * the same stale text and only the last one survived. This reads the row again inside a mutex,
     * toggles the requested line there and writes that body back; [toggleCheckboxInLatestBody]
     * holds the ordering so the second tap cannot read before the first write committed.
     *
     * R3-06: held under the Vault-unlock lock for the same reason as [updateNoteTitle].
     */
    fun toggleNoteCheckbox(noteId: Int, lineNumber: Int, text: String, checked: Boolean) {
        if (noteId == 0) return
        viewModelScope.launch(Dispatchers.IO) {
            guardCardWrite {
                lockManager.withVaultUnlock {
                    toggleCheckboxInLatestBody(
                        noteId = noteId,
                        lineNumber = lineNumber,
                        text = text,
                        checked = checked,
                        mutex = cardEditMutex,
                        readLatest = { id -> noteUseCase.getEditableNote(id) },
                        write = { note -> noteUseCase.addNote(note) },
                    )
                }
            }
        }
    }

    fun toggleIsDeleteMode(enabled: Boolean) {
        _isDeleteMode.value = enabled
    }

    /**
     * Loads a vault passphrase for this session and re-reads the note list.
     *
     * The passphrase lives in memory only (EncryptionHelper holds a StringBuilder, not persisted
     * state), so it has to be re-entered after every process death - the same contract the vault
     * had before its UI was removed. [NoteUseCase.refresh] forces the list through the new
     * passphrase; rows that still fail keep counting toward the lock banner, which is how a wrong
     * password is reported.
     *
     * SEC-02/SEC-13: the candidate is verified inside the process-wide Vault unlock lock, against a
     * stored Vault row, before it is kept. Running the whole set-verify-settle sequence under that
     * lock (and tracking the passphrase this attempt installed) is what keeps a failed Home guess
     * from removing a passphrase the editor's dialog just verified and saved with. Rejected guesses
     * feed the persisted throttle, so the banner cannot be used as a fast oracle.
     */
    fun unlockVault(password: String) {
        val throttle = lockManager.vaultAttempts
        val waitSeconds = throttle.remainingSeconds()
        if (waitSeconds > 0) {
            // The banner has no countdown of its own; the toast carries the same message the
            // editor's dialog shows inline.
            Toast.makeText(
                context,
                context.getString(R.string.lock2_vault_throttled, waitSeconds),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        _vaultUnlockAttempted.value = true
        val attempt = ++vaultUnlockAttempt
        viewModelScope.launch {
            lockManager.withVaultUnlock {
                // A second attempt while the first waited for the lock wins: only the newest
                // candidate may be kept or settled.
                if (attempt != vaultUnlockAttempt) return@withVaultUnlock
                // The earlier attempt may have started a lockout while this one queued.
                if (throttle.isLockedOut()) return@withVaultUnlock
                // SEC-02: remember what this attempt replaced. A failure restores that session
                // instead of blindly clearing whatever is loaded by then.
                val previous = lockManager.beginVaultAttempt(password)
                // Raw rows on purpose: EncryptedNoteDao hands vault rows through verbatim, so the
                // description is the ciphertext decrypt() can authenticate the candidate against.
                val sample = withContext(Dispatchers.IO) {
                    runCatching {
                        noteUseCase.getAllNotesFlow().first()
                            .firstOrNull { it.encrypted && (it.description.isNotBlank() || it.name.isNotBlank()) }
                    }.getOrNull()
                }
                val ciphertext = sample?.let { if (it.description.isNotBlank()) it.description else it.name }
                val verified = if (ciphertext == null) {
                    // No Vault row exists to authenticate against (nothing was ever sealed); keeping
                    // the passphrase is harmless and matches the old behaviour.
                    true
                } else {
                    withContext(Dispatchers.Default) {
                        encryptionHelper.decrypt(ciphertext).second == DecryptionResult.SUCCESS
                    }
                }
                // A newer attempt arrived while this one was verifying; settle this candidate so
                // the newer one starts from a clean ownership record.
                if (attempt != vaultUnlockAttempt) {
                    lockManager.endVaultAttempt(password, previous, success = false)
                    return@withVaultUnlock
                }
                lockManager.endVaultAttempt(password, previous, success = verified)
                // Not observe(): the collectors are already running while the app is unlocked, so
                // the idempotent observe would keep the rows that were hidden by the missing
                // password.
                noteUseCase.refresh()
                if (!verified) {
                    // R3-07: the throttle persists with commit(); keep that disk write off Main.
                    withContext(Dispatchers.IO) { throttle.registerFailure() }
                    return@withVaultUnlock
                }
                withContext(Dispatchers.IO) { throttle.reset() }
                // A4/PERF-2: the unlock is the only moment the passphrase is in memory, so it is
                // also the only moment rows still sealed by an earlier build (`v2:` or the
                // pre-PBKDF2 single SHA-256 layout) can be re-sealed into the `v3` envelope.
                // Without this, unlocking a large library kept paying one 120k-iteration PBKDF2 per
                // note. The sweep authenticates every row before writing, so a wrong passphrase
                // leaves all of them byte-for-byte untouched, and on the second unlock it does
                // nothing (every row already starts with `v3:`).
                withContext(Dispatchers.IO) {
                    val upgraded = noteUseCase.upgradeLegacyVaultRows()
                    if (upgraded > 0) {
                        Log.i(TAG, "Re-sealed $upgraded vault note(s) into the envelope (v3) layout")
                    }
                }
            }
        }
    }

    /** Guards [unlockVault] against a slower, older verification completing last. */
    private var vaultUnlockAttempt = 0

    fun changeSearchQuery(newValue: String) {
        _searchQuery.value = newValue
        // PERF-04: clearing the field shows the whole library in this frame instead of waiting for
        // the collector to observe the new query. Done under a blank query only; a narrowing query
        // keeps the previous result until its scan finishes.
        if (newValue.isBlank()) _filteredNotes.value = notes
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
        // Flag-only write. The shared collector receives Room's emission and updates the list;
        // observe() after every pin used to restart both collectors and re-decrypt everything.
        viewModelScope.launch(Dispatchers.IO) {
            noteUseCase.updatePinStatus(note.id, !note.pinned)
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

    /** Undo for [trashNote]/[trashNotes]: puts the rows back; Room's emission restores the list. */
    fun restoreTrashedNotes(notes: List<Note>) {
        if (notes.isEmpty()) return
        // Room emits the un-trashed rows; no explicit re-observe needed.
        viewModelScope.launch(Dispatchers.IO) {
            notes.forEach { noteUseCase.setTrashed(it.id, false) }
        }
    }

    /** Shared write path for toolbar actions that act per note instead of persisting a batch. */
    private fun forEachSelectedNote(action: suspend (Note) -> Unit) = mutateSelection { notes ->
        notes.forEach { action(it) }
    }

    private fun mutateSelection(write: suspend (List<Note>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            write(selectedNotes.toList())
            // Only the selection state needs clearing; the list itself follows Room's emission.
            launch(Dispatchers.Main) {
                selectedNotes.clear()
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
                        // The tag index follows the same Room emission as the list.
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
                }
            }
        }
    }

    fun deleteTags(tags: List<String>) {
        // TST-05: the toast/filter rules live in a pure policy so every branch is unit-testable.
        // The remaining-tag index is only final after the write, so the plan is built on Main once
        // the deletion has updated it.
        val targets = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            noteUseCase.deleteTagsFromNotes(targets)
            launch(Dispatchers.Main) {
                val plan = planTagDeletion(targets, currentFilter.value, noteUseCase.tags)
                val message = when (val feedback = plan.feedback) {
                    TagDeletionFeedback.None -> null
                    is TagDeletionFeedback.Single -> if (feedback.stillInUse) {
                        context.getString(R.string.tag_still_in_use, feedback.tag)
                    } else {
                        context.getString(R.string.tag_deleted, feedback.tag)
                    }
                    TagDeletionFeedback.Multiple -> context.getString(R.string.tags_deleted)
                }
                if (message != null) {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                if (plan.resetFilterToAll) setFilter("all")
            }
        }
    }

    fun restoreNote(note: Note) {
        // The use case owns the write scope; Room's emission restores the row in the list.
        noteUseCase.restoreFromTrash(note)
    }

    fun permanentlyDeleteNote(note: Note) {
        noteUseCase.permanentlyDeleteNoteById(note.id)
        // PERF-05: permanent deletion also drops an unsaved editor draft. A trash/undo cycle must
        // leave it alone - the note may come back and the text was never written anywhere else.
        // NonCancellable for the same reason the deletion uses it: the user can confirm and leave
        // the recycle bin before this file delete is dispatched, and the id could then be reused by
        // a later note while the old draft is still on disk.
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            EditDraftStore.clearFor(context, note.id)
        }
    }

    /** Puts one trashed attachment back into its note and refreshes the id -> path registry. */
    fun restoreAttachment(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // R5-03: a vault attachment is decrypted and re-sealed in this call. The unlock lock
            // keeps a concurrent unlock's candidate passphrase from sealing the row, and the guard
            // turns a Vault eviction between the read and the seal into a message instead of an
            // uncaught IllegalStateException. Refresh only after a real write.
            val restored = guardCardWrite {
                lockManager.withVaultUnlock { noteUseCase.restoreAttachment(id) }
            }
            if (restored) {
                guardCardWrite {
                    withContext(Dispatchers.Main) { noteUseCase.refreshAttachments() }
                }
            }
        }
    }

    /**
     * Moves one attachment shown on a home card to the recycle bin.
     *
     * The use case also removes the `attachment://` line from the note body, so the card stops
     * showing the recording and reopening the note does not resurrect it. The shared collector
     * re-emits the changed note, which is why no explicit re-observe is needed.
     *
     * R5-03: the vault branch rewrites the decrypted body and seals it again; it takes the unlock
     * lock and the card guard for the same reasons as [restoreAttachment].
     */
    fun deleteAttachment(id: String) {
        // The body changes with the attachment row, so the shared collector re-emits the note.
        viewModelScope.launch(Dispatchers.IO) {
            guardCardWrite {
                lockManager.withVaultUnlock { noteUseCase.trashAttachment(id) }
            }
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
        forEachSelectedNote {
            noteUseCase.permanentlyDeleteNoteById(it.id)
            // PERF-05: this runs on Dispatchers.IO (see mutateSelection), so the file delete does
            // not touch the main thread.
            EditDraftStore.clearFor(context, it.id)
        }
    }

    private companion object {
        const val TAG = "HomeViewModel"

        /** PERF-04: long enough to swallow a typing burst, short enough to feel live. */
        const val SEARCH_DEBOUNCE_MS = 180L
    }
}

/**
 * R4-04: runs a card write, reporting a non-cancellation failure instead of letting it escape.
 *
 * `NoteUseCase.addNote` refuses to seal a vault row once the Vault session's passphrase is gone,
 * and the eviction can land between the card's read and this write. Launched on
 * `Dispatchers.IO`, that `IllegalStateException` used to reach the default handler and kill the
 * process. Cancellation still propagates: a destroyed ViewModel is not a failed save.
 *
 * Top-level so the guard itself is testable without a ViewModel, a database or Android.
 */
internal suspend fun <T> runGuardedCardWrite(
    onFailure: suspend (Exception) -> Unit,
    block: suspend () -> T,
): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    onFailure(error)
    null
}

/**
 * UX-09: the read-modify-write behind a card checkbox tap.
 *
 * [readLatest] is called inside [mutex], after the previous toggle's [write] returned, so a rapid
 * pair of taps both survive: the second starts from the body the first stored instead of from the
 * summary its card captured at composition. A line that no longer matches [text] (the note was
 * edited in the editor meanwhile) is left alone - [CheckboxLineToggle.toggle] returns null and
 * nothing is overwritten.
 *
 * Kept top-level and parameter-driven so the ordering is testable with a map and a mutex, without
 * a database, a ViewModel or Android.
 *
 * @return true when a line was toggled (or was already in the requested state).
 */
internal suspend fun toggleCheckboxInLatestBody(
    noteId: Int,
    lineNumber: Int,
    text: String,
    checked: Boolean,
    mutex: Mutex,
    readLatest: suspend (Int) -> Note?,
    write: suspend (Note) -> Unit,
): Boolean = mutex.withLock {
    val latest = readLatest(noteId) ?: return@withLock false
    val updated = CheckboxLineToggle.toggle(latest.description, lineNumber, text, checked)
        ?: return@withLock false
    if (updated == latest.description) return@withLock true
    write(latest.copy(description = updated))
    true
}

/**
 * UX-09: the read-modify-write behind a card title edit.
 *
 * The card builds its title callback from the `Note` it composed with, so writing
 * `note.copy(name = ...)` back sent that snapshot's body along with the new name - a body change
 * made in the editor since the card was drawn was silently replaced. [readLatest] runs inside
 * [mutex], which is shared with the checkbox toggle, so a title edit and a checkbox tap cannot each
 * read before the other writes, and only [Note.name] is changed.
 *
 * Top-level and parameter-driven so the ordering and the field scope are pinned by a plain JVM test.
 *
 * @return true when the latest row already had [name] or was written.
 */
internal suspend fun updateTitleInLatestNote(
    noteId: Int,
    name: String,
    mutex: Mutex,
    readLatest: suspend (Int) -> Note?,
    write: suspend (Note) -> Unit,
): Boolean = mutex.withLock {
    val latest = readLatest(noteId) ?: return@withLock false
    if (latest.name == name) return@withLock true
    write(latest.copy(name = name))
    true
}
package com.ezpnix.writeon.presentation.screens.edit.model

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.attachment.AttachmentFiles
import com.ezpnix.writeon.core.attachment.AttachmentSyntax
import com.ezpnix.writeon.data.local.database.LegacyBodyMigration
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.core.security.DecryptFailureRegistry
import com.ezpnix.writeon.core.security.DecryptionResult
import com.ezpnix.writeon.core.security.EncryptionHelper
import com.ezpnix.writeon.presentation.components.markdown.AttachmentRegistry
import com.ezpnix.writeon.presentation.screens.edit.PendingSaveUndo
import com.ezpnix.writeon.presentation.screens.edit.components.UndoRedoState
import com.ezpnix.writeon.presentation.screens.edit.segments.AttachmentSegment
import com.ezpnix.writeon.presentation.screens.edit.segments.TextSegment
import com.ezpnix.writeon.presentation.screens.edit.segments.segmentize
import com.ezpnix.writeon.presentation.share.ShareIntake
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What the editor's most recent save attempt is doing, rendered as the compact status line.
 *
 * The editor used to save only on ON_STOP and report failures with a Toast: the user had no way to
 * tell whether the last paragraph made it to the database, and a dismissed Toast was the only trace
 * of a failed write (UX-1). [Error] carries the string resource the indicator shows next to Retry,
 * so a refusal (an undecryptable row) and an I/O failure both stay on screen.
 *
 * [VaultLocked] is a refusal too, but one the user can resolve: the vault passphrase was evicted
 * while the note was open, so the plain text in the fields has nowhere to go. It is a separate
 * state because a Retry cannot succeed without the passphrase, and because the screen must show the
 * unlock dialog instead of treating the write as done (V-3).
 */
sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data object Saved : SaveState
    data class Error(val messageRes: Int) : SaveState
    data object VaultLocked : SaveState
}

@HiltViewModel
class EditViewModel @Inject constructor(
    private val application: Application,
    private val noteUseCase: NoteUseCase,
    private val encryption: EncryptionHelper,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _noteName = mutableStateOf(TextFieldValue())
    val noteName: State<TextFieldValue> get() = _noteName

    private val _isDescriptionInFocus = mutableStateOf(false)
    val isDescriptionInFocus: State<Boolean> get() = _isDescriptionInFocus

    private val _isEncrypted = mutableStateOf(false)
    val isEncrypted: State<Boolean> get() = _isEncrypted

    private val _noteDescription = mutableStateOf(TextFieldValue())
    val noteDescription: State<TextFieldValue> get() = _noteDescription

    /**
     * Where the segmented editor should put the caret after an operation that recreates fields
     * (inserting or deleting an attachment line). Null means "no pending request"; the field that
     * contains the offset consumes it after taking focus.
     */
    private val _focusRequestOffset = mutableStateOf<Int?>(null)
    val focusRequestOffset: State<Int?> get() = _focusRequestOffset

    /** True while the experimental segmented editor is on screen, so insertion can use its rules. */
    private var segmentedEditing = false

    private val _noteId = mutableIntStateOf(0)
    val noteId: State<Int> get() = _noteId

    private val _noteCreatedTime = mutableLongStateOf(System.currentTimeMillis())
    val noteCreatedTime: State<Long> get() = _noteCreatedTime

    private val _noteUpdatedTime = mutableLongStateOf(0L)
    val noteUpdatedTime: State<Long> get() = _noteUpdatedTime

    /** Save progress surfaced to the editor's status line (UX-1). */
    private val _saveState = mutableStateOf<SaveState>(SaveState.Idle)
    val saveState: State<SaveState> get() = _saveState

    /**
     * True when the password the user typed for the unlock dialog was rejected (V-3).
     *
     * Kept apart from [saveState] so the dialog can stay open and show the error in place: a wrong
     * password leaves [SaveState.VaultLocked] exactly as it was, only with this flag set.
     */
    private val _vaultUnlockError = mutableStateOf(false)
    val vaultUnlockError: State<Boolean> get() = _vaultUnlockError

    /**
     * True when the refused save belongs to a brand-new vault note (`id == 0`).
     *
     * Such a note has no stored ciphertext, so there is no way to tell a correct passphrase from a
     * wrong one. Accepting one blindly would seal the note under a key nobody can reproduce, and
     * saving it as plain text would defeat the vault - so the screen offers neither "unlock" nor a
     * plaintext write, only "unlock from Home" or "discard" (V-3).
     */
    private val _vaultUnlockUnverifiable = mutableStateOf(false)
    val vaultUnlockUnverifiable: Boolean get() = _vaultUnlockUnverifiable.value

    /**
     * Whether the last committed save also moved the note to the recycle bin.
     *
     * Clearing every character is a save, but it is not the same event as "saved": the snackbar has
     * to say "Moved to trash" or the note looks deleted without explanation (UX-2).
     */
    private val _lastSaveMovedToTrash = mutableStateOf(false)
    val lastSaveMovedToTrash: Boolean get() = _lastSaveMovedToTrash.value

    private val _isNoteInfoVisible = mutableStateOf(false)
    val isNoteInfoVisible: State<Boolean> get() = _isNoteInfoVisible

    private val _isEditMenuVisible = mutableStateOf(false)
    val isEditMenuVisible: State<Boolean> get() = _isEditMenuVisible

    private val _isPinned = mutableStateOf(false)
    val isPinned: State<Boolean> get() = _isPinned

    private val _isImageSaveRequested = mutableStateOf(false)
    val isImageSaveRequested: State<Boolean> get() = _isImageSaveRequested

    private val _originalNote = mutableStateOf<Note?>(null)
    private val nameUndoRedoState = UndoRedoState()
    private val descriptionUndoRedoState = UndoRedoState()

    private var noteJob: Job? = null

    /**
     * Unsaved text, mirrored into [SavedStateHandle] shortly after the user stops typing.
     *
     * The editor only writes on ON_STOP, so a process death mid-sentence (crash, low-memory kill,
     * an update taking the process down) lost everything typed since the last save. The draft
     * survives both a configuration change and a system-initiated process death, and is offered
     * back in [setupNoteData] before the first database emission - the dirty gate in [syncNote]
     * then treats it as a local edit and keeps it instead of the stored text. It is cleared on a
     * successful save, so the draft only ever holds text the database does not have.
     */
    private fun startDraftPersistence() {
        viewModelScope.launch {
            snapshotFlow { noteName.value.text to noteDescription.value.text }
                .distinctUntilChanged()
                .collect { (name, body) ->
                    // SEC-7: a vault note's plaintext must never reach SavedStateHandle. The draft
                    // is a convenience for plain notes only; writing sealed text there would put
                    // it in the system's saved-instance bundle in clear text, outside the vault.
                    // Clear first so a draft left by an earlier, still-plain session cannot linger.
                    if (_isEncrypted.value) {
                        clearDraft()
                        return@collect
                    }
                    // Wait out the typing burst: the handle is written into the saved instance
                    // state on every change, and doing that per keystroke is measurable jank.
                    delay(DRAFT_DEBOUNCE_MS)
                    // The note can become encrypted during the debounce (a vault row opened from
                    // the list); re-check before the write, not after.
                    if (_isEncrypted.value) {
                        clearDraft()
                        return@collect
                    }
                    savedStateHandle[KEY_DRAFT_NOTE_ID] = noteId.value
                    savedStateHandle[KEY_DRAFT_NAME] = name
                    savedStateHandle[KEY_DRAFT_DESCRIPTION] = body
                }
        }
    }

    private fun clearDraft() {
        savedStateHandle.remove<Int>(KEY_DRAFT_NOTE_ID)
        savedStateHandle.remove<String>(KEY_DRAFT_NAME)
        savedStateHandle.remove<String>(KEY_DRAFT_DESCRIPTION)
    }

    /** Puts an unsaved draft back into the fields, if one exists for [id]. */
    private fun restoreDraftFor(id: Int) {
        // SEC-7: never re-offer plaintext for a vault note. If a draft survived from before the
        // note was sealed, drop it instead of painting the body over the locked editor.
        if (_isEncrypted.value) {
            clearDraft()
            return
        }
        if (savedStateHandle.get<Int>(KEY_DRAFT_NOTE_ID) != id) return
        val name = savedStateHandle.get<String>(KEY_DRAFT_NAME).orEmpty()
        val body = savedStateHandle.get<String>(KEY_DRAFT_DESCRIPTION).orEmpty()
        if (name.isEmpty() && body.isEmpty()) return
        _noteName.value = TextFieldValue(name, selection = TextRange(name.length))
        _noteDescription.value = TextFieldValue(body, selection = TextRange(body.length))
    }

    fun requestImageSave() {
        _isImageSaveRequested.value = true
    }

    fun clearImageSaveRequest() {
        _isImageSaveRequested.value = false
    }

    /**
     * Attachments this editor produced but has not written yet.
     *
     * Kept here rather than in Compose state: they only matter to [saveNote], while the preview
     * needs the id -> path pair immediately, which is what [AttachmentRegistry] is for. A picture
     * picked in a note that is never saved leaves a real file behind, so [finishAttachmentPlan] also
     * prunes the ones the final body stopped referencing.
     */
    private val pendingAttachments = LinkedHashMap<String, Attachment>()

    /** Notes whose unattributed attachment rows have already been claimed. */
    private val claimedNoteIds = mutableSetOf<Int>()

    /**
     * What the most recent save replaced.
     *
     * [savedId] is the id Room assigned when the save created a new row. It is captured here
     * because `noteId.value` is only updated by the collector after the insert, so an undo tapped
     * before that emission used to read 0 and delete nothing (DAT-12).
     */
    private data class SavePoint(
        val noteId: Int,
        val previous: Note?,
        val saved: Note,
        val savedId: Int = 0
    )

    /**
     * Scope for writes that must survive the screen.
     *
     * ON_STOP is the fallback save and it fires while the destination is torn down. Launching that
     * save in [viewModelScope] meant `onCleared` could cancel it between the last keystroke and
     * Room committing - the exact data loss DAT-9 describes. This scope is not cancelled by the
     * ViewModel, and the transaction additionally runs under [NonCancellable].
     */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The one write-once-at-a-time gate every save path shares (V-6).
     *
     * ON_STOP, the retry button and the awaited back-navigation save used to each launch their own
     * `performSave`. A back press landing while ON_STOP was still writing ran two transactions
     * against the same text: a new note could be inserted twice, and the undo point of the first
     * write was replaced by the second. [SaveSingleFlight] hands the second caller the write that is
     * already running, and its loop re-runs it when the text changed under the write.
     */
    private val saveFlight = SaveSingleFlight(saveScope)

    /**
     * The id Room assigned to a note this editor created.
     *
     * The loop in [saveUntilCaughtUp] and an ON_STOP that arrives mid-insert both still carry the
     * placeholder id 0. Resolving it here is what makes the repeat run an update of the row Room
     * just created instead of a second insert of the same note (V-6).
     */
    private var committedNoteId = 0

    /**
     * Text the last committed save wrote.
     *
     * Used to skip the ON_STOP that immediately follows a successful back-navigation save: a
     * second write would bump `updatedAt` again and replace the undo point with an identical one.
     */
    private var lastSavedContent: Pair<String, String>? = null

    /**
     * True when the fields no longer match the last loaded or saved text.
     *
     * The status line uses it so a "Saved" label disappears with the first new keystroke instead of
     * claiming text that is not in the database yet.
     */
    val isDirty: Boolean get() = lastSavedContent != currentContent()

    private fun currentContent(): Pair<String, String> =
        noteName.value.text to noteDescription.value.text

    /**
     * The last save, kept only so it can be taken back.
     *
     * The editor saves on ON_STOP, and ON_STOP is not always a deliberate exit. Holding the previous
     * row for ten seconds turns the one unrecoverable mistake - saving a half-edited note or
     * saving when the user meant to go back - into a single tap.
     */
    private var savePoint: SavePoint? = null

    /** Bumped on every save and every undo, so the screen can restart its countdown. */
    private val _undoToken = mutableIntStateOf(0)
    val undoToken: Int
        get() = _undoToken.intValue

    val canUndoSave: Boolean
        get() = savePoint != null

    fun insertImageAttachment(path: String) {
        val attachment = Attachment(
            id = AttachmentSyntax.newId(),
            noteId = noteId.value,
            type = Attachment.TYPE_IMAGE,
            path = path,
            createdAt = System.currentTimeMillis()
        )
        registerPendingAttachment(attachment)
        val line = AttachmentSyntax.image(attachment.id)
        if (segmentedEditing) insertAttachmentAtCaret(line) else insertBlock(line)
    }

    fun insertAudioAttachment(path: String, caption: String) {
        val resolvedCaption = caption.trim().ifBlank {
            runCatching { File(path).nameWithoutExtension }.getOrDefault("")
        }
        val attachment = Attachment(
            id = AttachmentSyntax.newId(),
            noteId = noteId.value,
            type = Attachment.TYPE_AUDIO,
            path = path,
            caption = resolvedCaption,
            createdAt = System.currentTimeMillis()
        )
        registerPendingAttachment(attachment)
        val line = AttachmentSyntax.audio(attachment.id, resolvedCaption)
        if (segmentedEditing) insertAttachmentAtCaret(line) else insertBlock(line)
    }

    private fun registerPendingAttachment(attachment: Attachment) {
        pendingAttachments[attachment.id] = attachment
        // Render now, persist later. The registry is a Compose state map, so the picture appears as
        // soon as the body text gains its line.
        AttachmentRegistry.put(attachment)
    }

    /**
     * Fallback save: ON_STOP, or the retry button next to an error.
     *
     * Runs on [saveScope] rather than [viewModelScope], so clearing the ViewModel cannot cancel the
     * write the user's last keystrokes depend on (DAT-9). [performSave] additionally wraps the
     * transaction in [NonCancellable], and [saveFlight] makes this write share the one a back press
     * or another ON_STOP may already have started.
     */
    fun saveNote(id: Int) {
        // Back navigation has already committed this exact text; the ON_STOP that follows would
        // only bump updatedAt and replace the undo point with an identical one.
        if (lastSavedContent == currentContent()) return
        saveScope.launch { saveUntilCaughtUp(id) }
    }

    /**
     * Saves and suspends until the write finished, so navigation can wait for it before popping
     * the destination (UX-1/DAT-9). True when the note is stored or there was nothing to store.
     *
     * False keeps the editor open. That is a refusal the user can act on (a locked vault, see
     * [unlockVaultAndRetry]) as well as a write failure - both used to be indistinguishable from a
     * successful save here, which is how a locked vault note lost its text on the way out (V-3).
     */
    suspend fun saveBeforeExit(id: Int = noteId.value): Boolean {
        // The row was loaded, or the last save committed this exact text: leaving needs no write,
        // and a second one would only bump updatedAt and register an undo of identical text.
        if (lastSavedContent == currentContent()) {
            _saveState.value = SaveState.Idle
            return true
        }
        // Wait on the save scope instead of merely running here. A rotation cancels the caller's
        // composition scope, but the write and its status update must still finish, or the new
        // composition would sit on a stale "Saving…" after the row was actually stored.
        return saveUntilCaughtUp(id)
    }

    /** Explicit retry for the error shown by the status line. */
    fun retrySave() {
        saveScope.launch { saveUntilCaughtUp(noteId.value) }
    }

    /**
     * Persists the latest text, including text typed while a write was already running (V-6).
     *
     * Two callers can never run two transactions: the second one awaits the write in flight and
     * then writes again only if the fields moved on under it. Without that second pass, "save, keep
     * typing, back" stored the text from before those keystrokes.
     */
    private suspend fun saveUntilCaughtUp(id: Int): Boolean =
        saveFlight.runUntilCaughtUp(
            currentContent = ::currentContent,
            committedContent = { lastSavedContent },
            save = { performSave(id) },
        )

    private fun failSave(messageRes: Int): Boolean {
        _saveState.value = SaveState.Error(messageRes)
        return false
    }

    /**
     * The one write path. Deliberately not launched directly: callers pick the scope, and the
     * transaction runs uncancellable. [saveFlight] guarantees that no two calls are active at once.
     */
    private suspend fun performSave(requestedId: Int): Boolean {
        // The loop in [saveUntilCaughtUp] and an ON_STOP that arrived mid-insert can still carry
        // the placeholder id 0 for a note Room already created; write the row it created instead of
        // inserting a second one (V-6).
        val id = if (requestedId != 0) requestedId else committedNoteId
        _saveState.value = SaveState.Saving
        // A row whose stored text this process could not decrypt only exists here as a
        // placeholder (see EncryptedNoteDao). Saving would overwrite the ciphertext that is
        // the user's only chance of recovery with display text, so refuse before any plan,
        // snapshot or transaction is built. This is a refusal, not a write failure: no Retry can
        // ever succeed, so report it and still let the user leave instead of trapping the editor.
        if (id != 0 && DecryptFailureRegistry.isFailed(id)) {
            Toast.makeText(
                application,
                application.getString(R.string.decrypt_failed_cannot_save),
                Toast.LENGTH_LONG
            ).show()
            _saveState.value = SaveState.Idle
            return true
        }
        try {
            // Pinning is already persisted by [toggleNotePin]. Rewriting it here compared
            // isPinned.value against _isPinned.value - the same state object - so the branch could
            // never be taken, and it aimed at the placeholder id 0 for a brand-new note anyway.

            val hasContent =
                noteName.value.text.isNotBlank() || noteDescription.value.text.isNotBlank()
            // An empty brand-new note is not worth storing, but an existing note must still be
            // written when the user clears every character, or the previous text silently survives.
            if (!hasContent && id == 0) {
                // Nothing was typed: do not create an empty row, and do not claim "Saved" for a
                // write that never happened.
                _saveState.value = SaveState.Idle
                return true
            }
            // An existing note whose row has not emitted yet shows empty fields, and the awaited
            // back-save runs earlier than the old ON_STOP did. Writing now would replace the load
            // with a blank row, so treat that frame as nothing to save.
            if (!hasContent && _originalNote.value == null) {
                _saveState.value = SaveState.Idle
                return true
            }
            // A vault note keeps its 1.x body until it is opened: the 7 -> 8 migration cannot
            // decrypt it, so the upgrade happens here, on plain text, the first time the note is
            // saved. The rewrite is idempotent, which is what makes running it on every save the
            // simplest way to catch vault notes, restored backups and hand-pasted legacy text.
            val rewritten = LegacyBodyMigration.rewrite(noteDescription.value.text, id)
            if (rewritten.changed) {
                // Rows the rewrite produced join the editor's own pending attachments, so the
                // code below writes one consistent set.
                rewritten.attachments.forEach { pendingAttachments[it.id] = it }
                val caret = noteDescription.value.selection.end.coerceAtMost(rewritten.body.length)
                _noteDescription.value = TextFieldValue(
                    text = rewritten.body,
                    selection = TextRange(caret)
                )
            }
            val description = rewritten.body

            val now = System.currentTimeMillis()
            // Fall back to the stored row rather than a blank Note: when the editor is saved
            // before getNoteById has emitted, _originalNote is still null and the old code
            // replaced the whole row with tags = emptyList(), silently dropping the note's
            // tags. Reading the row keeps every field this screen does not edit.
            val base = _originalNote.value ?: if (id != 0) {
                noteUseCase.getNoteById(id).firstOrNull()
                    ?: throw IllegalStateException("Note no longer exists; refusing to save over missing data")
            } else {
                null
            }
            // A vault note may only be rewritten while its passphrase is loaded: saving with an
            // empty key would replace the password-sealed body with ciphertext nobody can open,
            // and the old fallback of "save it as plaintext instead" quietly removed the
            // protection the user asked for. Refuse and warn.
            //
            // The condition must not depend on [base] alone: when the row could not be read
            // (null base — first frame not loaded, or the shared connection was closed), the
            // code below falls back to a full-row insert with `encrypted = false`, which is
            // exactly the write that used to overwrite a vault note's ciphertext with
            // plaintext. The editor's own encrypted flag covers that hole.
            val lockedVault = encryption.isPasswordEmpty() &&
                (_isEncrypted.value || base?.encrypted == true)
            if (lockedVault) {
                // V-3: this used to Toast and return true, which let saveBeforeExit pop the editor.
                // A vault note has no SavedStateHandle draft (SEC-7), so the text typed since the
                // lock was simply gone. Refuse without reporting success: the screen keeps the
                // editor open and shows the unlock dialog, and false is what stops the navigation.
                //
                // The `_isEncrypted` half must not depend on the id: a brand-new vault note
                // (`id == 0`) with no stored row used to slip past the `base?.encrypted` check and
                // be inserted with `encrypted = false` - a plaintext note created from the vault.
                _vaultUnlockUnverifiable.value = id == 0
                _saveState.value = SaveState.VaultLocked
                return false
            }
            // A note may only be flagged encrypted when a vault passphrase is actually loaded,
            // otherwise EncryptionHelper would seal it with an empty key and no screen in the
            // app can decrypt it again.
            val encrypted = isEncrypted.value && !encryption.isPasswordEmpty()
            // Capture the name with the body below: the fields can change again while the write is
            // in flight, and the save point / lastSavedContent must describe what was actually
            // written, not whatever the user typed next.
            val savedName = noteName.value.text
            val note = base?.copy(
                name = savedName,
                description = description,
                pinned = isPinned.value,
                encrypted = encrypted,
                createdAt = if (noteCreatedTime.value != 0L) noteCreatedTime.value else now,
                updatedAt = now
            ) ?: Note(
                id = id,
                name = savedName,
                description = description,
                pinned = isPinned.value,
                encrypted = encrypted,
                createdAt = if (noteCreatedTime.value != 0L) noteCreatedTime.value else now,
                updatedAt = now,
                tags = emptyList()
            )
            // Captured before the write: [note] is about to replace this row.
            val point = SavePoint(noteId = id, previous = base, saved = note)
            savePoint = point
            // Plan first, write once: the note row and every attachment row change land in a
            // single Room transaction, so a failure or a cancelled coroutine can no longer
            // leave "new body, old rows" (or the reverse). File deletion happens after the
            // commit - an orphan file is recoverable, a row whose file is already gone is not.
            val plan = planAttachments(description, note.id, prune = description.isNotEmpty())
            // The transaction must complete even if the screen is being torn down. NonCancellable
            // is what makes the ON_STOP fallback trustworthy when the ViewModel is cleared while
            // Room is still writing (DAT-9).
            val savedId = withContext(NonCancellable) {
                val writtenId = noteUseCase.saveNoteWithAttachments(
                    note = note,
                    upsertAttachments = plan.upsert,
                    reviveAttachmentIds = plan.reviveIds,
                    deleteAttachmentIds = plan.removeRows.map { it.id },
                )
                // Clearing the body sends the note to the recycle bin; it does not mean "destroy
                // every picture and recording it ever held". [plan] already kept the rows and
                // files (prune is off for an empty body) so a restore or the undo still finds
                // them. The version just written is the one that lands in the trash.
                if (!hasContent) {
                    noteUseCase.moveToTrash(
                        if (note.id != 0) note else note.copy(id = writtenId)
                    )
                }
                writtenId
            }
            if (savedId > 0) {
                // DAT-12: Room assigns a new note's id inside the call above. The save point is
                // what Undo reads, so it has to learn the id here - waiting for noteId.value to
                // catch up left a window where undo deleted id 0 and silently did nothing.
                savePoint = point.copy(savedId = savedId)
                // Same id for the queued re-save: a second run for the text typed during this
                // write must update this row, not insert the note again (V-6).
                committedNoteId = savedId
                // The collector sets this a frame later; doing it now closes the window in which a
                // save still resolves the note as id 0.
                updateNoteId(savedId)
            }
            if (savedId > 0 && id == 0) {
                setupNoteData(savedId)
            }
            // Only after the commit: deleting rows' files before the transaction could leave rows
            // pointing at bytes that are already gone.
            finishAttachmentPlan(plan)
            lastSavedContent = savedName to description
            // The database now holds this text; the draft would only re-offer it after a
            // restore of a note that no longer needs it.
            clearDraft()
            // Bounded history: after a committed save the stored row is the undo target (the
            // save-point bar), so keeping every pre-save snapshot only pins whole documents
            // in memory for a feature that must not offer them any more.
            nameUndoRedoState.clearTo(_noteName.value)
            descriptionUndoRedoState.clearTo(_noteDescription.value)
            // UX-2: clearing every character is still a save, but it is also a move to the
            // recycle bin. The screen reads this flag to show that second fact instead of the
            // generic "saved" message.
            _lastSaveMovedToTrash.value = !hasContent
            _undoToken.intValue++
            _vaultUnlockError.value = false
            _saveState.value = SaveState.Saved
            // The global snackbar can be tapped after this destination is popped, when
            // viewModelScope is already cancelled. Register the database half separately so
            // the action keeps working for the snackbar's lifetime (U2). The point carries the
            // id Room assigned, not the still-empty noteId (DAT-12).
            savePoint?.let { committed ->
                PendingSaveUndo.register {
                    performUndo(committed)
                    // The snackbar can be tapped while the editor is still alive (a save made on
                    // ON_STOP without navigating). Clear the status so it does not keep claiming a
                    // save the user just took back.
                    if (savePoint === committed) savePoint = null
                    _lastSaveMovedToTrash.value = false
                    _saveState.value = SaveState.Idle
                }
            }
            return true
        } catch (e: CancellationException) {
            // Never swallow cancellation: the coroutine is being torn down (screen closed),
            // and swallowing it would keep a dead scope alive pretending to work. The DB write
            // has already finished if it was reached, because it runs under NonCancellable.
            throw e
        } catch (e: Exception) {
            // Keep the editor open: the status line shows the failure with a Retry action, so a
            // full disk or a closed connection is no longer a Toast that disappears (UX-1).
            savePoint = null
            return failSave(R.string.editor_fix_save_failed)
        }
    }

    /**
     * V-3: the save was refused because the vault passphrase is no longer loaded.
     *
     * The text is plain, in memory, and deliberately in no draft (SEC-7), so the refusal must not
     * pop the editor. The only two honest ways out are an unlock that *verifies*, or an explicit
     * discard; this is the unlock half.
     *
     * The passphrase is checked against the row the editor loaded before it is kept. Merely storing
     * it would let a typo seal the note under a key nothing can reproduce, and the row's ciphertext
     * is the only thing that can tell the two cases apart. On success the retry goes through the
     * same single-flight save path as every other write, so the unlock cannot start a second
     * transaction behind a save that is already running.
     *
     * @return true only when the text is now stored (or there was nothing to store). False with
     * [vaultUnlockError] set means the password was wrong; false with an Error [saveState] means the
     * unlock worked but the write did not, and the status line's Retry is the next step.
     */
    suspend fun unlockVaultAndRetry(password: String): Boolean {
        // A brand-new vault note has no ciphertext to authenticate the passphrase against. Sealing
        // it with an unverified key makes a note only that wrong key can open, and writing it as a
        // plaintext row is exactly the loss this dialog exists to prevent - so there is nothing to
        // accept here. The screen does not offer the password field in this case.
        if (noteId.value == 0 || _vaultUnlockUnverifiable.value) return false
        // The row can still be null when the save happens before the first emission. Read it rather
        // than reject a correct passphrase just because the collector is late. A failed read is a
        // save failure, not a wrong password, and must not throw out of the dialog's coroutine.
        val stored = try {
            _originalNote.value?.description
                ?: noteUseCase.getNoteById(noteId.value).firstOrNull()?.description
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failSave(R.string.editor_fix_save_failed)
        }
        if (stored.isNullOrBlank()) return false
        // Temporarily load the candidate key: decrypt() is the only check that distinguishes "this
        // is the vault" from "this is some password".
        encryption.setPassword(password)
        val (_, status) = encryption.decrypt(stored)
        if (status != DecryptionResult.SUCCESS) {
            // Leave the vault exactly as locked as it was. A wrong guess must not stay in memory
            // where the next list read would try (and fail) to decrypt with it.
            encryption.removePassword()
            _vaultUnlockError.value = true
            _saveState.value = SaveState.VaultLocked
            return false
        }
        _vaultUnlockError.value = false
        return saveUntilCaughtUp(noteId.value)
    }

    /**
     * V-3: the user chose to lose the unsaved text rather than unlock.
     *
     * Recording the fields as "last saved" is deliberate, not a claim that they reached the
     * database: the destination is about to pop, its ON_STOP would otherwise run the refused save
     * again, and if the vault happened to be unlocked by then it would write text the user just
     * discarded. The stored row keeps the last committed text.
     */
    fun discardVaultLockedSave() {
        lastSavedContent = currentContent()
        _vaultUnlockError.value = false
        _saveState.value = SaveState.Idle
    }

    /**
     * Takes back the last save.
     *
     * Restoring goes through [NoteUseCase.restoreNote] rather than [saveNote]: the stored row is put
     * back byte for byte, including updatedAt, while a vault note keeps the already-sealed description
     * that only that path can write. A note that the save created is deleted, along with the
     * attachments the save had claimed for it.
     */
    fun undoLastSave() {
        val point = savePoint ?: return
        savePoint = null
        _undoToken.intValue++
        PendingSaveUndo.clear()
        _lastSaveMovedToTrash.value = false
        _saveState.value = SaveState.Idle
        viewModelScope.launch { performUndo(point) }
    }

    /**
     * The database and field work behind [undoLastSave].
     *
     * Shared with `PendingSaveUndo`: the snackbar invokes it directly, because by the time the
     * user taps Undo the editor destination (and its viewModelScope) may already be gone.
     */
    private suspend fun performUndo(point: SavePoint) {
        val previous = point.previous
        if (previous != null) {
            noteUseCase.restoreNote(previous)
            // Forced: undo's whole purpose is to put the stored text back even though it
            // differs from what the fields hold right now.
            syncNote(previous, forceText = true)
        } else {
            // DAT-12: prefer the id Room assigned at save time. noteId.value is only updated by
            // the collector afterwards, so reading it here was a race that ended in
            // deleteNoteById(0) and an undo that appeared to do nothing.
            val savedId = point.savedId.takeIf { it != 0 }
                ?: point.saved.id.takeIf { it != 0 }
                ?: noteId.value.takeIf { it != 0 }
            if (savedId == null) {
                // Nothing was ever created: say so rather than leave the user believing the note
                // was undone. The global snackbar has no other way to report this.
                Toast.makeText(
                    application,
                    application.getString(R.string.editor_fix_undo_unavailable),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            noteUseCase.deleteNoteById(savedId)
            _originalNote.value = null
            _noteId.value = 0
            _noteCreatedTime.value = System.currentTimeMillis()
            _noteUpdatedTime.longValue = 0L
            _noteName.value = TextFieldValue("")
            _noteDescription.value = TextFieldValue("")
            _isPinned.value = false
        }
    }

    /**
     * Plain text of a stored row, or null while a vault row cannot be decrypted.
     *
     * Used only for deciding whether the editor fields still match the row; the row itself stays
     * sealed in [_originalNote] so the save path writes it back untouched.
     */
    private fun storedText(note: Note): Pair<String, String>? {
        if (!note.encrypted) return note.name to note.description
        val (name, nameStatus) = encryption.decrypt(note.name)
        val (description, descriptionStatus) = encryption.decrypt(note.description)
        return if (nameStatus == DecryptionResult.SUCCESS && descriptionStatus == DecryptionResult.SUCCESS) {
            name!! to description!!
        } else {
            null
        }
    }

    /**
     * Applies a database emission to the editor.
     *
     * Pinning, tagging or trashing a note makes Room emit its whole row. Writing that row's text
     * into the fields unconditionally replaced whatever the user had typed since the write began -
     * a pin tap in the middle of a sentence erased that sentence (P0-2). Metadata is always
     * applied; the text only moves when the fields hold exactly the stored text. A first row for a
     * brand-new editor still loads normally, and [forceText] is used by undo, where putting the
     * stored text back is the whole point.
     */
    private fun syncNote(note: Note, forceText: Boolean = false) {
        val stored = storedText(note)
        val previous = _originalNote.value
        val sameName = stored != null && _noteName.value.text == stored.first
        val sameDescription = stored != null && _noteDescription.value.text == stored.second
        val fieldsHaveDraft = _noteName.value.text.isNotEmpty() ||
            _noteDescription.value.text.isNotEmpty()
        val hasLocalEdits = !forceText && stored != null && (!sameName || !sameDescription) &&
            (previous != null || fieldsHaveDraft)

        _originalNote.value = note
        if (stored != null && !hasLocalEdits) {
            // The fields now hold exactly what is stored, so a later ON_STOP or back press has no
            // new text to write. Recording it here lets those paths skip the write instead of
            // bumping updatedAt and registering an undo that would restore identical text.
            lastSavedContent = stored
            // Equal text is left alone rather than re-assigned: assigning a TextFieldValue moves
            // the caret to the end, so a metadata emission would otherwise jump the cursor while
            // the user was typing.
            if (!sameName) {
                updateNoteName(TextFieldValue(stored.first, selection = TextRange(stored.first.length)))
            }
            if (!sameDescription) {
                updateNoteDescription(
                    TextFieldValue(stored.second, selection = TextRange(stored.second.length))
                )
            }
        }
        updateNoteCreatedTime(note.createdAt)
        _noteUpdatedTime.longValue = note.updatedAt
        updateNoteId(note.id)
        updateNotePin(note.pinned)
        updateIsEncrypted(note.encrypted)
        claimAttachments(note.id)
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
        // The draft is restored for *every* id, including 0. A brand-new note has no database row
        // to fall back on, so "wrote half a sentence, process died" is exactly the case the draft
        // exists for - and gating this behind `id != 0` meant new-note drafts were written but
        // never read back. The dirty gate in syncNote then sees fields that differ from the stored
        // text and leaves them alone (for id 0 there is no collector at all).
        restoreDraftFor(id)
        if (id != 0) {
            noteJob = viewModelScope.launch {
                noteUseCase.getNoteById(id).collectLatest { note ->
                    syncNote(note)
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
        nameUndoRedoState.onInput(newName)
    }

    fun updateIsEncrypted(value: Boolean) {
        _isEncrypted.value = value
        // SEC-7: a vault note must not keep a plaintext draft in SavedStateHandle. Clearing here
        // also covers the frame before setupNoteData restores/collects anything, and a stale
        // draft left by a session where the same row was still plain.
        if (value) clearDraft()
    }

    fun updateNoteDescription(newDescription: TextFieldValue) {
        _noteDescription.value = newDescription
        descriptionUndoRedoState.onInput(newDescription)
    }

    /**
     * U3: text changed from the rendered preview (a checkbox tap), not by typing.
     *
     * The old call built a fresh `TextFieldValue(text = it)`, whose default selection is offset 0;
     * every checkbox tap therefore jumped the caret to the start of the note. Keep the current
     * selection, clamped when the new text happens to be shorter.
     */
    fun updateNoteDescriptionFromRendered(text: String) {
        val current = _noteDescription.value
        if (current.text == text) return
        val start = current.selection.start.coerceIn(0, text.length)
        val end = current.selection.end.coerceIn(0, text.length)
        updateNoteDescription(
            current.copy(text = text, selection = TextRange(start, end))
        )
    }

    /**
     * SPL-1: toggles a checkbox reported by the split preview against the **live** body.
     *
     * The split pane deliberately renders a debounced snapshot, so the old "rebuild the body from
     * this snapshot" path could erase characters typed after the snapshot was sampled. This method
     * only replaces the matching marker line in the current text; when the checkbox line itself
     * changed in the meantime, [CheckboxLineToggle] returns null and nothing is written.
     *
     * @return true when a line was toggled, false when the request was stale.
     */
    fun toggleCheckboxFromRendered(lineNumber: Int, text: String, checked: Boolean): Boolean {
        val current = _noteDescription.value
        val updated = CheckboxLineToggle.toggle(current.text, lineNumber, text, checked) ?: return false
        if (updated == current.text) return true
        // Preserve the caret exactly where the editor had it; the toggle never reorders text.
        val start = current.selection.start.coerceIn(0, updated.length)
        val end = current.selection.end.coerceIn(0, updated.length)
        updateNoteDescription(current.copy(text = updated, selection = TextRange(start, end)))
        return true
    }

    /** Told by the segmented editor so attachment insertion can follow its caret rules. */
    fun setSegmentedEditing(value: Boolean) {
        segmentedEditing = value
        if (!value) _focusRequestOffset.value = null
    }

    /** A field calls this after taking focus for a pending insertion/deletion. */
    fun consumeFocusRequest(offset: Int) {
        if (_focusRequestOffset.value == offset) {
            _focusRequestOffset.value = null
        }
    }

    /**
     * True when the segment ends on the CR of a CRLF line whose LF is the separator that
     * [assemble] inserts before the next attachment. That CR is part of the stored body but not
     * part of what the line looks like: the reader strips it with `lines()`, and a Compose text
     * field would show it as an extra line break or a control character. A CR at the end of the
     * whole body is real content, so it is not hidden.
     */
    private fun hasHiddenBoundaryCr(
        segment: TextSegment,
        text: String,
        start: Int,
        end: Int
    ): Boolean =
        !segment.synthetic &&
            end > start &&
            end < text.length &&
            text[end - 1] == '\r' &&
            text[end] == '\n'

    /**
     * The value for one text segment: the substring plus the part of the full selection and IME
     * composition that falls inside it. Passing the composition through is what keeps a Chinese or
     * Japanese IME's composing region alive while the field's text is a substring of the body.
     */
    fun segmentValue(segment: TextSegment): TextFieldValue {
        val full = _noteDescription.value
        val text = full.text
        val start = segment.startOffset.coerceIn(0, text.length)
        val end = segment.endOffset.coerceIn(start, text.length)
        val local = text.substring(start, end)
        val hidesBoundaryCr = hasHiddenBoundaryCr(segment, text, start, end)
        val display = if (hidesBoundaryCr) local.dropLast(1) else local

        fun map(offset: Int): Int = (offset - start).coerceIn(0, display.length)

        val composition = full.composition?.takeIf {
            it.start >= start && it.end <= end && it.start <= it.end
        }?.let {
            TextRange(map(it.start), map(it.end))
        }
        return TextFieldValue(
            text = display,
            selection = TextRange(map(full.selection.start), map(full.selection.end)),
            composition = composition
        )
    }

    /**
     * Applies one text field's edit back to the single Markdown string.
     *
     * A field can report more than one edit before recomposition (fast typing, an IME composing
     * region, a paste), so the segment captured by its last composition is stale by the second
     * call. Look the segment up by its stable key in the body as it is now: the first edit has
     * already been written there, and replacing the new range with the field's newer value keeps
     * the two in step. If the key is gone (an attachment operation removed that text), fall back
     * to the captured offsets, clamped so the result is still a valid string.
     */
    fun onSegmentChange(segment: TextSegment, newValue: TextFieldValue) {
        val full = _noteDescription.value
        val text = full.text
        val current = segmentize(text)
            .filterIsInstance<TextSegment>()
            .firstOrNull { it.key == segment.key }
            ?: segment
        val start = current.startOffset.coerceIn(0, text.length)
        val end = current.endOffset.coerceIn(start, text.length)

        // The synthetic field after a final attachment has no newline of its own. Typing into it
        // must open a line first, otherwise the attachment token and the new text would share a
        // line and the block would disappear.
        val needsLineBreak = current.synthetic &&
            newValue.text.isNotEmpty() &&
            start == end &&
            start > 0 &&
            text[start - 1] != '\n'
        // A bare CR at the end is half of a CRLF whose LF was deleted; complete it instead of
        // appending another CRLF (which would leave the attachment line ending in "\r\r\n").
        val prefix = when {
            !needsLineBreak -> ""
            text[start - 1] == '\r' -> "\n"
            else -> lineBreakFor(text)
        }
        // The field never sees the CR that closes a CRLF line at a segment boundary (see
        // segmentValue). Keep it in place instead of replacing the whole segment range, or every
        // edit to a line above an attachment would silently turn that line's CRLF into LF.
        val contentEnd = if (hasHiddenBoundaryCr(current, text, start, end)) end - 1 else end
        val updated = text.replaceRange(start, contentEnd, prefix + newValue.text)
        val shift = prefix.length
        val selection = TextRange(
            (start + shift + newValue.selection.start).coerceIn(0, updated.length),
            (start + shift + newValue.selection.end).coerceIn(0, updated.length)
        )
        val composition = newValue.composition?.let {
            TextRange(
                (start + shift + it.start).coerceIn(0, updated.length),
                (start + shift + it.end).coerceIn(0, updated.length)
            )
        }
        commitDescription(updated, selection, composition)
    }

    /**
     * Inserts an attachment at the caret rather than at the end of the current line. On a blank
     * line the block replaces the blank line; inside a paragraph it splits the paragraph into two
     * text segments with the block between them, and always leaves a text segment after it.
     */
    private fun insertAttachmentAtCaret(rawLine: String) {
        val value = _noteDescription.value
        val text = value.text
        val start = value.selection.min.coerceIn(0, text.length)
        val end = value.selection.max.coerceIn(start, text.length)
        val lineBreak = lineBreakFor(text)
        val before = text.substring(0, start)
        val after = text.substring(end)

        // Do not treat a bare CR as a finished line break: it is the first half of a CRLF whose
        // LF was deleted, so append only the missing LF.
        val lead = when {
            before.isEmpty() || before.endsWith("\n") -> ""
            before.endsWith("\r") -> "\n"
            else -> lineBreak
        }
        val afterStartsAtLine = after.isEmpty() ||
            after.startsWith("\n") ||
            after.startsWith("\r\n")
        val trail = if (afterStartsAtLine && after.isNotEmpty()) "" else lineBreak

        val updated = before + lead + rawLine + trail + after
        val afterRaw = start + lead.length + rawLine.length
        val caret = when {
            after.startsWith("\r\n") -> afterRaw + 2
            after.startsWith("\n") -> afterRaw + 1
            else -> afterRaw + trail.length
        }.coerceIn(0, updated.length)

        commitDescription(updated, TextRange(caret))
        requestFocusInText(caret)
    }

    /** Removes an attachment's whole line, including one adjacent line break. */
    fun deleteAttachment(segment: AttachmentSegment) {
        val text = _noteDescription.value.text
        var start = segment.startOffset.coerceIn(0, text.length)
        var end = segment.endOffset.coerceIn(start, text.length)
        if (end < text.length && text[end] == '\n') {
            end++
        } else if (start > 0 && text[start - 1] == '\n') {
            start--
            if (start > 0 && text[start - 1] == '\r') start--
        }
        val updated = text.removeRange(start, end)
        val caret = start.coerceIn(0, updated.length)
        commitDescription(updated, TextRange(caret))
        requestFocusInText(caret)
        // The row itself leaves through the recycle bin, so the operation stays reversible until
        // the trash is emptied. Rows that never reached the database (picked in this session) have
        // nothing to trash and are still pruned as abandoned by [finishAttachmentPlan].
        viewModelScope.launch { noteUseCase.trashAttachment(segment.attachmentId, rewriteBody = false) }
    }

    /**
     * Deletes an attachment from a rendered preview: the line leaves the body at once and the row
     * moves to the recycle bin. [commitDescription] keeps the change undoable; if the note is saved
     * while the line is back, [planAttachments] un-trashes the row it finds referenced again.
     */
    fun trashAttachmentById(id: String) {
        val value = _noteDescription.value
        val text = value.text
        if (id !in AttachmentSyntax.idsIn(text)) return
        val updated = AttachmentSyntax.removeId(text, id)
        val caret = value.selection.min.coerceIn(0, updated.length)
        commitDescription(updated, TextRange(caret))
        // Vault bodies are only rewritten by the save path, so the row is pruned there instead of
        // kept as a recycle-bin entry the manager could never show.
        if (!_isEncrypted.value) {
            viewModelScope.launch { noteUseCase.trashAttachment(id, rewriteBody = false) }
        }
    }

    /** Replaces an attachment's line with edited Markdown (one line; newlines are flattened). */
    fun replaceAttachmentRaw(segment: AttachmentSegment, raw: String) {
        replaceAttachmentLine(segment, raw, focus = true)
    }

    fun renameAudioAttachment(segment: AttachmentSegment, caption: String) {
        replaceAttachmentLine(
            segment,
            AttachmentSyntax.audio(segment.attachmentId, caption),
            focus = false
        )
    }

    private fun replaceAttachmentLine(
        segment: AttachmentSegment,
        raw: String,
        focus: Boolean
    ) {
        val line = raw
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
        if (line.isEmpty()) {
            deleteAttachment(segment)
            return
        }
        val text = _noteDescription.value.text
        val start = segment.startOffset.coerceIn(0, text.length)
        val end = segment.endOffset.coerceIn(start, text.length)
        // segment.raw includes the CR of a CRLF line. Replacing the whole range would silently
        // turn that one line into LF, so keep the original line ending.
        val carriageReturn = if (segment.raw.endsWith('\r')) "\r" else ""
        val updated = text.replaceRange(start, end, line + carriageReturn)
        val caret = (start + line.length + carriageReturn.length)
            .coerceIn(0, updated.length)
        commitDescription(updated, TextRange(caret))
        // If the edited line is still an attachment it is drawn as a block, and there is no text
        // field at the caret. Keeping a request there would leave it pending until some later edit
        // happened to move a text field over that offset.
        val stillAttachment = AttachmentSyntax.IMAGE_LINE.matchEntire(line) != null ||
            AttachmentSyntax.AUDIO_LINE.matchEntire(line) != null
        if (focus && !stillAttachment) requestFocusInText(caret)
    }

    private fun commitDescription(
        text: String,
        selection: TextRange,
        composition: TextRange? = null
    ) {
        _noteDescription.value = TextFieldValue(text, selection, composition)
        // Undo snapshots do not need the IME composition, and keeping a stale one there lets undo
        // hand TextFieldValue a composition range that no longer exists in the restored text.
        descriptionUndoRedoState.onInput(TextFieldValue(text, selection))
    }

    private fun lineBreakFor(text: String): String =
        if (text.contains("\r\n")) "\r\n" else "\n"

    /**
     * Only text segments own a field. A structural edit can leave the caret at the start of an
     * attachment line (inserting a block just above it, or deleting a block between two blocks),
     * where no field would consume a focus request. Snap to the nearest text segment, preferring
     * the one before the caret, so the keyboard opens next to the edit instead of staying closed
     * with an unconsumed request.
     */
    private fun requestFocusInText(offset: Int) {
        val text = _noteDescription.value.text
        val clamped = offset.coerceIn(0, text.length)
        val textSegments = segmentize(text).filterIsInstance<TextSegment>()
        if (textSegments.isEmpty()) {
            _focusRequestOffset.value = null
            return
        }
        if (textSegments.any { clamped in it.startOffset..it.endOffset }) {
            _focusRequestOffset.value = clamped
            return
        }
        val target = textSegments.lastOrNull { it.endOffset <= clamped }
            ?: textSegments.firstOrNull { it.startOffset >= clamped }
            ?: textSegments.first()
        _focusRequestOffset.value = clamped.coerceIn(target.startOffset, target.endOffset)
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
        descriptionUndoRedoState.undo()
        _noteDescription.value = descriptionUndoRedoState.input
        if (segmentedEditing) {
            requestFocusInText(_noteDescription.value.selection.end)
        }
    }

    fun redo() {
        descriptionUndoRedoState.redo()
        _noteDescription.value = descriptionUndoRedoState.input
        if (segmentedEditing) {
            requestFocusInText(_noteDescription.value.selection.end)
        }
    }

    private fun lineStartIndex(text: String, position: Int): Int {
        var index = position.coerceIn(0, text.length)
        while (index > 0 && text[index - 1] != '\n') index--
        return index
    }

    private fun lineEndIndex(text: String, position: Int): Int {
        var index = position.coerceIn(0, text.length)
        while (index < text.length && text[index] != '\n') index++
        return index
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

    /**
     * Wraps the current selection in [prefix]/[suffix] instead of dropping bare markers at the
     * caret. An empty selection still has to produce something usable, so the pair is inserted and
     * the caret parked between the halves - which is where the text about to be typed belongs. A
     * non-empty selection is wrapped and left selected, so styles can be stacked.
     */
    fun applyFormat(prefix: String, suffix: String = prefix) {
        val value = noteDescription.value
        val text = value.text
        val start = value.selection.min.coerceIn(0, text.length)
        val end = value.selection.max.coerceIn(0, text.length)
        val selected = text.substring(start, end)
        val updated = text.replaceRange(start, end, prefix + selected + suffix)
        val selection = if (start == end) {
            TextRange(start + prefix.length)
        } else {
            TextRange(start + prefix.length, start + prefix.length + selected.length)
        }
        _noteDescription.value = TextFieldValue(text = updated, selection = selection)
    }

    /**
     * Escapes the markdown syntax inside the selection, or drops a single backslash at the caret
     * when nothing is selected - the "type the character first, then escape it" flow.
     *
     * Escaping is what makes a literal `*`, `_` or `#` possible at all: the renderer treats those
     * characters as delimiters and never prints them, so text that needs one has to opt out
     * explicitly. Characters that are already escaped are left alone, so pressing twice is safe.
     */
    fun applyEscape() {
        val value = noteDescription.value
        val text = value.text
        val start = value.selection.min.coerceIn(0, text.length)
        val end = value.selection.max.coerceIn(0, text.length)
        if (start == end) {
            insertText("\\", newLine = false)
            return
        }

        val selected = text.substring(start, end)
        val escaped = buildString {
            selected.forEachIndexed { index, char ->
                val alreadyEscaped = index > 0 && selected[index - 1] == '\\'
                if (char in ESCAPABLE_CHARACTERS && !alreadyEscaped) append('\\')
                append(char)
            }
        }
        if (escaped == selected) return

        val updated = text.replaceRange(start, end, escaped)
        _noteDescription.value = TextFieldValue(
            text = updated,
            selection = TextRange(start, (start + escaped.length).coerceAtMost(updated.length))
        )
    }

    /**
     * Toggles a four-space indent on every line the selection touches. When all of them are already
     * indented the indent is removed instead, so one button walks a paragraph in and back out.
     */
    fun applyIndent() {
        val value = noteDescription.value
        val text = value.text
        val selectionStart = value.selection.min.coerceIn(0, text.length)
        val selectionEnd = value.selection.max.coerceIn(0, text.length)
        val blockStart = lineStartIndex(text, selectionStart)
        val blockEnd = lineEndIndex(text, selectionEnd)

        val lines = text.substring(blockStart, blockEnd).split("\n")
        val indented = lines.filter { it.isNotBlank() }
        if (indented.isEmpty()) return
        val outdent = indented.all { it.startsWith(INDENT) }

        val updatedLines = lines.map { line ->
            when {
                line.isBlank() -> line
                outdent -> line.removePrefix(INDENT)
                else -> INDENT + line
            }
        }
        val updatedBlock = updatedLines.joinToString("\n")
        val updated = text.replaceRange(blockStart, blockEnd, updatedBlock)
        val delta = if (outdent) -INDENT.length else INDENT.length
        val caret = (selectionStart + delta)
            .coerceIn(blockStart, blockStart + updatedBlock.length)
            .coerceIn(0, updated.length)

        _noteDescription.value = TextFieldValue(text = updated, selection = TextRange(caret))
    }

    /**
     * Drops a two-column pipe table on its own line. The header row is localised because it is the
     * part a writer replaces by typing; the alignment row is syntax and stays as-is.
     */
    fun insertTable() {
        val header = application.getString(R.string.fmt_table_header)
        insertBlock("$header\n$TABLE_ALIGNMENT_ROW\n|  |  |")
    }

    fun insertText(insertText: String, offset: Int = 1, newLine: Boolean = true) {
        val value = _noteDescription.value
        val text = value.text
        val start = value.selection.min.coerceIn(0, text.length)
        val end = value.selection.max.coerceIn(0, text.length)

        // Two insertion models, because the toolbar produces two different kinds of text. A line
        // marker ("# ", "- ", "1.") qualifies the whole line it sits on, so it is placed in front of
        // that line; an inline token ("[](url)", a timestamp) belongs exactly where the caret is.
        // The previous implementation rebuilt the current line and appended the marker at its end,
        // which is why every button dropped its markers at the end of the note instead of at the
        // caret.
        val updatedText: String
        val caret: Int
        when {
            newLine && !insertText.contains('\n') -> {
                val lineStart = lineStartIndex(text, start)
                val lineEnd = lineEndIndex(text, end)
                val updatedLine = insertText + text.substring(lineStart, lineEnd)
                updatedText = text.replaceRange(lineStart, lineEnd, updatedLine)
                caret = start + insertText.length
            }
            insertText.contains('\n') -> {
                // A block (fence, divider, checkbox pair) keeps to itself instead of being spliced
                // into the middle of a sentence, so it gets a line break in front when needed.
                val needsLeadingBreak = start > 0 && text[start - 1] != '\n'
                val lead = if (needsLeadingBreak) "\n" else ""
                updatedText = text.replaceRange(start, end, lead + insertText)
                caret = start + lead.length + insertText.length + (offset - 1)
            }
            else -> {
                updatedText = text.replaceRange(start, end, insertText)
                caret = start + insertText.length + (offset - 1)
            }
        }
        _noteDescription.value = TextFieldValue(
            text = updatedText,
            selection = TextRange(caret.coerceIn(0, updatedText.length))
        )
    }

    /**
     * Inserts [block] on a line of its own.
     *
     * [insertText] is caret-oriented, which is right for `**bold**` but wrong for an attachment: the
     * line processors require the whole line to be the image/recording token, so appending one to a
     * paragraph leaves it unrendered and the raw id visible in the editor. An empty line is reused;
     * otherwise a new one is opened below it.
     */
    private fun insertBlock(block: String) {
        val text = _noteDescription.value.text
        val range = getIntRangeForCurrentLine()
        val lineStart = range.first
        val lineEndExclusive = (range.last + 1).coerceAtLeast(lineStart)
        val currentLine = text.substring(lineStart, lineEndExclusive)

        val updatedText: String
        val caret: Int
        if (currentLine.isBlank()) {
            updatedText = text.replaceRange(lineStart, lineEndExclusive, block)
            caret = lineStart + block.length
        } else {
            updatedText = text.substring(0, lineEndExclusive) + "\n" + block +
                text.substring(lineEndExclusive)
            caret = lineEndExclusive + 1 + block.length
        }

        _noteDescription.value = TextFieldValue(text = updatedText, selection = TextRange(caret))
    }

    /**
     * Decides the attachment-row changes for one save, without writing anything.
     *
     * Four groups exist after an edit:
     *  - pending rows (picked or recorded in this session) that survived in the text -> upsert;
     *  - rows the note already owned but the text no longer references -> delete row + file;
     *  - pending rows the user deleted again before the note was ever saved -> delete file only;
     *  - trashed rows (deleted to the recycle bin since 2.0.2) -> untouched, and any of them the
     *    text references again (an undo before this save) are revived.
     *
     * The row changes ride into the save transaction (see [NoteUseCase.saveNoteWithAttachments]);
     * the file deletions happen after it commits, in [finishAttachmentPlan]. With [prune] false -
     * a body the user just cleared - nothing is removed or revived: the note is about to be
     * trashed, and undo or a restore must still find its pictures and recordings.
     *
     * A brand-new note has no id yet, so the rows are written with note-id 0 and claimed by
     * [claimAttachments] once Room has assigned the real id.
     */
    private suspend fun planAttachments(
        body: String,
        noteId: Int,
        prune: Boolean
    ): AttachmentPlan {
        val idsInBody = AttachmentSyntax.idsIn(body)

        val upsert = pendingAttachments.values.filter { it.id in idsInBody }

        var reviveIds: List<String> = emptyList()
        var removeRows: List<Attachment> = emptyList()
        if (prune && noteId != 0) {
            val ownedRows = noteUseCase.attachmentsForNote(noteId)
            // Undoing a delete before the next save puts the line back, and the row that moved to
            // the recycle bin has to come back with it.
            reviveIds = ownedRows.filter { it.id in idsInBody && it.trashedAt != null }.map { it.id }
            // Trashed rows are deliberately not part of the prune: the file and the row have to
            // survive for the recycle bin, which is what this whole column exists for.
            removeRows = ownedRows.filter { it.id !in idsInBody && it.trashedAt == null }
        }

        val abandoned = if (prune) {
            pendingAttachments.values.filter { it.id !in idsInBody }
        } else {
            emptyList()
        }

        return AttachmentPlan(upsert, reviveIds, removeRows, abandoned)
    }

    /** What one save will do to the attachment rows, and (after commit) to their files. */
    private data class AttachmentPlan(
        val upsert: List<Attachment>,
        val reviveIds: List<String>,
        val removeRows: List<Attachment>,
        val abandoned: List<Attachment>,
    )

    /**
     * Post-commit half of [planAttachments]: the file and registry cleanup that must not run
     * unless the rows actually landed. Deleting a file before the transaction could commit left a
     * row pointing at bytes that were already gone - the one ordering that cannot be undone.
     */
    private suspend fun finishAttachmentPlan(plan: AttachmentPlan) {
        val appContext = application.applicationContext

        plan.upsert.forEach { pendingAttachments.remove(it.id) }

        // D10: deleting files touches the filesystem and used to run on the main dispatcher at the
        // end of every save. The rows are already committed at this point, so a slow unlink can
        // only delay the UI - do it off the main thread. Registry writes stay on the caller's
        // thread because AttachmentRegistry is Compose state.
        val filesToDelete = plan.removeRows + plan.abandoned
        if (filesToDelete.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                AttachmentFiles.deleteAll(appContext, filesToDelete)
            }
        }
        if (plan.removeRows.isNotEmpty()) {
            AttachmentRegistry.remove(plan.removeRows.map { it.id })
        }
        if (plan.abandoned.isNotEmpty()) {
            AttachmentRegistry.remove(plan.abandoned.map { it.id })
            plan.abandoned.forEach { pendingAttachments.remove(it.id) }
        }

        // Anything the legacy rewrite produced is already in the database now; re-reading keeps the
        // registry (and therefore every open screen) in step with what was actually stored.
        noteUseCase.refreshAttachments()
    }

    /**
     * Gives the rows written under the placeholder id 0 to the note Room just created. Guarded so
     * the collector that calls it on every database emission does not relaunch a write each time.
     */
    private fun claimAttachments(savedNoteId: Int) {
        if (savedNoteId == 0 || !claimedNoteIds.add(savedNoteId)) return
        val ids = AttachmentSyntax.idsIn(noteDescription.value.text)
        if (ids.isEmpty()) return
        viewModelScope.launch { noteUseCase.assignAttachments(savedNoteId, ids.toList()) }
    }

    /**
     * A note opened from a share starts out holding what was shared. Pictures become attachment
     * rows under the placeholder id 0 - claimed by the note on its first save, exactly like a
     * picture picked from the toolbar - so the body only ever stores `attachment://` links.
     *
     * Runs in `init` because the payload has to be in place before the first frame: consuming it
     * anywhere later would paint an empty editor and then replace the text under the caret.
     */
    init {
        ShareIntake.consume()?.let { payload ->
            val blocks = buildList {
                payload.text.takeIf { it.isNotBlank() }?.let(::add)
                payload.imagePaths.forEach { path ->
                    val attachment = Attachment(
                        id = AttachmentSyntax.newId(),
                        noteId = 0,
                        type = Attachment.TYPE_IMAGE,
                        path = path,
                        createdAt = System.currentTimeMillis()
                    )
                    pendingAttachments[attachment.id] = attachment
                    AttachmentRegistry.put(attachment)
                    add(AttachmentSyntax.image(attachment.id))
                }
            }
            if (blocks.isNotEmpty()) {
                _noteDescription.value = TextFieldValue(
                    text = blocks.joinToString("\n\n"),
                    selection = TextRange(0)
                )
            }
        }
        startDraftPersistence()
    }

    private companion object {
        /** The characters the markdown renderer treats as syntax, and therefore swallows. */
        val ESCAPABLE_CHARACTERS = setOf('\\', '`', '*', '_', '=', '~', '|', '#', '>', '[', ']', '(', ')')

        /** One indent step; four spaces is also what the renderer needs to keep a line preformatted. */
        const val INDENT = "    "

        /** The row that marks the header/body split in a pipe table. */
        const val TABLE_ALIGNMENT_ROW = "| --- | --- |"

        /** SavedStateHandle keys for the unsaved-text draft. */
        const val KEY_DRAFT_NOTE_ID = "edit_draft_note_id"
        const val KEY_DRAFT_NAME = "edit_draft_name"
        const val KEY_DRAFT_DESCRIPTION = "edit_draft_description"

        /** How long the user must stop typing before the draft is written. */
        const val DRAFT_DEBOUNCE_MS = 500L
    }
}

/**
 * Lets one save run at a time and lets a caller catch up with text typed during that save (V-6).
 *
 * ON_STOP, the retry button and the awaited back-navigation save all write the same row, and two
 * of them could overlap: a new note's two transactions both read id 0 and each inserted a row, the
 * second write replaced the first one's undo point, and `updatedAt` ended up out of order. [run]
 * hands every caller arriving while a write is in flight the same [Deferred] instead of starting a
 * second one, and [runUntilCaughtUp] re-runs the write while the editor fields moved on under it -
 * so "save, keep typing, back" cannot leave those last keystrokes behind.
 *
 * The deferred is created on the editor's save scope, not the caller's: a rotation or an exiting
 * composition only stops *waiting* for the write, it never cancels it (DAT-9).
 */
internal class SaveSingleFlight(private val scope: CoroutineScope) {
    private val gate = Any()
    private var inFlight: Deferred<Boolean>? = null

    /**
     * Runs [save], or returns the run that is already executing.
     *
     * `false` is the save's "refused or failed" result; success is `true`. The caller decides what
     * to do with it - the loop below stops on `false` because retrying a refusal that needs user
     * input would spin.
     */
    fun run(save: suspend () -> Boolean): Deferred<Boolean> {
        var created: Deferred<Boolean>? = null
        val deferred = synchronized(gate) {
            // `inFlight` is cleared on completion, but that handler can run a beat after the
            // awaiting caller resumes; checking isCompleted as well keeps a finished result from
            // being handed to a caller that does have new text to write.
            inFlight?.takeIf { !it.isCompleted }?.let { return it }
            scope.async(start = CoroutineStart.LAZY) { save() }.also { started ->
                inFlight = started
                created = started
                started.invokeOnCompletion {
                    // Identity check: a newer run may already own the slot when this handler fires.
                    synchronized(gate) { if (inFlight === started) inFlight = null }
                }
            }
        }
        // Start outside the lock. On an immediate dispatcher the block would otherwise begin - and
        // could suspend - while this thread still holds the monitor.
        created?.start()
        return deferred
    }

    /**
     * Writes, then writes again while the fields differ from what the last write stored.
     *
     * [committedContent] is the text the database is known to hold; if a run reports success
     * without advancing it, the save was refused or had nothing to write. Looping there would spin
     * forever on text that can never match a row that was never written, so that case stops at once.
     */
    suspend fun runUntilCaughtUp(
        currentContent: () -> Pair<String, String>,
        committedContent: () -> Pair<String, String>?,
        save: suspend () -> Boolean,
    ): Boolean {
        while (true) {
            val committedBefore = committedContent()
            if (!run(save).await()) return false
            if (committedContent() == committedBefore) return true
            if (committedContent() == currentContent()) return true
        }
    }
}

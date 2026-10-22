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
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.attachment.AttachmentFiles
import com.ezpnix.writeon.core.attachment.AttachmentSyntax
import com.ezpnix.writeon.data.local.database.LegacyBodyMigration
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.presentation.components.DecryptionResult
import com.ezpnix.writeon.presentation.components.EncryptionHelper
import com.ezpnix.writeon.presentation.components.markdown.AttachmentRegistry
import com.ezpnix.writeon.presentation.screens.edit.components.UndoRedoState
import com.ezpnix.writeon.presentation.screens.edit.segments.AttachmentSegment
import com.ezpnix.writeon.presentation.screens.edit.segments.TextSegment
import com.ezpnix.writeon.presentation.screens.edit.segments.segmentize
import com.ezpnix.writeon.presentation.share.ShareIntake
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
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

    /**
     * Attachments this editor produced but has not written yet.
     *
     * Kept here rather than in Compose state: they only matter to [saveNote], while the preview
     * needs the id -> path pair immediately, which is what [AttachmentRegistry] is for. A picture
     * picked in a note that is never saved leaves a real file behind, so [persistAttachments] also
     * prunes the ones the final body stopped referencing.
     */
    private val pendingAttachments = LinkedHashMap<String, Attachment>()

    /** Notes whose unattributed attachment rows have already been claimed. */
    private val claimedNoteIds = mutableSetOf<Int>()

    /** What the most recent save replaced. */
    private data class SavePoint(val noteId: Int, val previous: Note?, val saved: Note)

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
                    description = description,
                    pinned = isPinned.value,
                    encrypted = encrypted,
                    createdAt = if (noteCreatedTime.value != 0L) noteCreatedTime.value else now,
                    updatedAt = now
                ) ?: Note(
                    id = id,
                    name = noteName.value.text,
                    description = description,
                    pinned = isPinned.value,
                    encrypted = encrypted,
                    createdAt = if (noteCreatedTime.value != 0L) noteCreatedTime.value else now,
                    updatedAt = now,
                    tags = emptyList()
                )
                // Captured before the write: [note] is about to replace this row.
                savePoint = SavePoint(noteId = id, previous = base, saved = note)
                noteUseCase.addNote(note)
                persistAttachments(description, note.id)
                // Clearing every character and saving now means the note is gone rather than an
                // empty row left on the home screen: the version just written is the one that lands
                // in the trash, so it can still be restored.
                if (!hasContent) {
                    val trashedId = if (note.id != 0) note.id else id
                    noteUseCase.moveToTrash(
                        if (note.id != 0) note else note.copy(id = trashedId)
                    )
                }
                _undoToken.intValue++
            }

            fetchLastNoteAndUpdate()
        }
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
        viewModelScope.launch {
            val previous = point.previous
            if (previous != null) {
                noteUseCase.restoreNote(previous)
                syncNote(previous)
            } else {
                val savedId = point.saved.id.takeIf { it != 0 } ?: noteId.value
                if (savedId != 0) noteUseCase.deleteNoteById(savedId)
                _originalNote.value = null
                _noteId.value = 0
                _noteCreatedTime.value = System.currentTimeMillis()
                _noteUpdatedTime.longValue = 0L
                _noteName.value = TextFieldValue("")
                _noteDescription.value = TextFieldValue("")
                _isPinned.value = false
            }
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
        undoRedoState.onInput(TextFieldValue(text, selection))
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
        undoRedoState.undo()
        _noteDescription.value = undoRedoState.input
        if (segmentedEditing) {
            requestFocusInText(_noteDescription.value.selection.end)
        }
    }

    fun redo() {
        undoRedoState.redo()
        _noteDescription.value = undoRedoState.input
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
     * Writes the attachment rows the saved body references and removes the ones it stopped using.
     *
     * Three groups exist after an edit:
     *  - pending rows (picked or recorded in this session) that survived in the text -> upsert;
     *  - rows the note already owned but the text no longer references -> delete row + file;
     *  - pending rows the user deleted again before the note was ever saved -> delete file.
     *
     * A brand-new note has no id yet, so its rows are written with note-id 0 and claimed by
     * [claimAttachments] once Room has assigned the real id.
     */
    private suspend fun persistAttachments(body: String, savedNoteId: Int) {
        val idsInBody = AttachmentSyntax.idsIn(body)
        val appContext = application.applicationContext

        val owned = pendingAttachments.values.filter { it.id in idsInBody }
        if (owned.isNotEmpty()) {
            noteUseCase.saveAttachments(owned.map { it.copy(noteId = savedNoteId) })
            owned.forEach { pendingAttachments.remove(it.id) }
        }

        if (savedNoteId != 0) {
            val removed = noteUseCase.attachmentsForNote(savedNoteId).filter { it.id !in idsInBody }
            if (removed.isNotEmpty()) {
                AttachmentFiles.deleteAll(appContext, removed)
                noteUseCase.deleteAttachments(removed.map { it.id })
                AttachmentRegistry.remove(removed.map { it.id })
            }
        }

        val abandoned = pendingAttachments.values.filter { it.id !in idsInBody }
        if (abandoned.isNotEmpty()) {
            AttachmentFiles.deleteAll(appContext, abandoned)
            AttachmentRegistry.remove(abandoned.map { it.id })
            abandoned.forEach { pendingAttachments.remove(it.id) }
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
    }

    private companion object {
        /** The characters the markdown renderer treats as syntax, and therefore swallows. */
        val ESCAPABLE_CHARACTERS = setOf('\\', '`', '*', '_', '=', '~', '|', '#', '>', '[', ']', '(', ')')

        /** One indent step; four spaces is also what the renderer needs to keep a line preformatted. */
        const val INDENT = "    "

        /** The row that marks the header/body split in a pipe table. */
        const val TABLE_ALIGNMENT_ROW = "| --- | --- |"
    }
}
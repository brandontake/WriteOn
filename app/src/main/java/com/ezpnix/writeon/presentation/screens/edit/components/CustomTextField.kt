package com.ezpnix.writeon.presentation.screens.edit.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import com.ezpnix.writeon.presentation.theme.appCornerShape

@Composable
fun CustomTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    shape: RoundedCornerShape = appCornerShape(),
    interactionSource: MutableInteractionSource = MutableInteractionSource(),
    singleLine: Boolean = false,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hideContent: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current
) {
    val visualTransformation = if (hideContent) {
        PasswordVisualTransformation()
    } else {
        VisualTransformation.None
    }

    TextField(
        value = value,
        visualTransformation = visualTransformation,
        onValueChange = onValueChange,
        interactionSource = interactionSource,
        textStyle = textStyle,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape),
        singleLine = singleLine,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        placeholder = {
            Text(placeholder)
        }
    )
}

class UndoRedoState(initialValue: TextFieldValue = TextFieldValue("")) {
    var input by mutableStateOf(initialValue)
        private set
    private val undoHistory = ArrayDeque<TextFieldValue>()
    private val redoHistory = ArrayDeque<TextFieldValue>()

    /**
     * Whether an undo/redo step is available right now.
     *
     * Snapshot state, not a computed getter over the ArrayDeques: the toolbar buttons read these
     * from a different composable scope than the one that writes the text, so a plain getter would
     * never invalidate them. Kept in step by every mutation below.
     */
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /**
     * PERF-09: UTF-16 bytes retained by the history (undo **and** redo).
     *
     * A snapshot is the whole document, so a cap on entries alone was meaningless for a long note:
     * 100 x 500k characters is ~100 MB. Entries are evicted oldest-first until this fits
     * [MAX_HISTORY_BYTES], but never below [MIN_HISTORY_SIZE] - undo needs at least the previous
     * text and the current one. R2-06: redo snapshots were not counted at all, so 100 of them
     * could sit outside the advertised 4 MB budget.
     */
    private var historyBytes = 0L
    private var redoBytes = 0L

    /** PERF-09 test seam: retained snapshot bytes, not a user-facing metric. */
    internal val retainedBytes: Long get() = historyBytes + redoBytes

    /** PERF-09 test seam: how many undo points are retained. */
    internal val undoDepth: Int get() = undoHistory.size

    /** R2-06 test seam: how many redo points are retained. */
    internal val redoDepth: Int get() = redoHistory.size

    init {
        push(initialValue)
        canRedo = false
    }

    fun onInput(value: TextFieldValue) {
        val previous = undoHistory.lastOrNull()
        if (previous == value) {
            input = value
            return
        }
        // R2-06: moving the caret or selecting text is not an edit. The old comparison included
        // the selection, so a tap in a long note cleared redo and pushed a full-document snapshot
        // that undo could not visibly revert - and those no-op snapshots then evicted real history.
        if (previous != null && previous.text == value.text) {
            input = value
            return
        }
        redoHistory.clear()
        redoBytes = 0L
        canRedo = false
        // Coalesce a typing run: consecutive single-caret insertions/deletions at the previous
        // caret replace the tail entry instead of pushing another full copy of the document.
        // Without this a long note still grew the history by one whole string per keystroke until
        // the cap kicked in; with it a run costs one entry no matter how long it is.
        if (undoHistory.size >= 2 && previous != null && isTypingContinuation(previous, value)) {
            historyBytes -= sizeOf(undoHistory.removeLast())
        }
        // PERF-09: deleting a character and retyping it returns the text to the snapshot the
        // coalescing just uncovered. Pushing it would leave two entries with the same text and make
        // canUndo true for an undo that changes nothing. Only the caret moves instead.
        if (undoHistory.lastOrNull() == value) {
            input = value
            canUndo = undoHistory.size > 1
            return
        }
        push(value)
        input = value
    }

    /**
     * Drops the pre-save history: the database now holds [current], so the only undo a user needs
     * after saving is the save-point bar, and every retained snapshot is pure memory overhead.
     */
    fun clearTo(current: TextFieldValue) {
        undoHistory.clear()
        redoHistory.clear()
        canRedo = false
        historyBytes = 0L
        redoBytes = 0L
        push(current)
        input = current
    }

    private fun push(value: TextFieldValue) {
        undoHistory.add(value)
        historyBytes += sizeOf(value)
        while (undoHistory.size > MAX_HISTORY_SIZE) {
            historyBytes -= sizeOf(undoHistory.removeFirst())
        }
        while (historyBytes > MAX_HISTORY_BYTES && undoHistory.size > MIN_HISTORY_SIZE) {
            historyBytes -= sizeOf(undoHistory.removeFirst())
        }
        canUndo = undoHistory.size > 1
    }

    /** R2-06: keeps redo inside the same entry/size budget as undo. */
    private fun pushRedo(value: TextFieldValue) {
        redoHistory.add(value)
        redoBytes += sizeOf(value)
        while (redoHistory.size > MAX_HISTORY_SIZE) {
            redoBytes -= sizeOf(redoHistory.removeFirst())
        }
        while (redoBytes > MAX_HISTORY_BYTES && redoHistory.size > 1) {
            redoBytes -= sizeOf(redoHistory.removeFirst())
        }
    }

    /**
     * True when [next] is [previous] with exactly one character inserted or deleted at its caret.
     *
     * This used to recognise only appends at the end of the document, so every keystroke in the
     * middle of a note pushed another full snapshot. The common prefix/suffix identify the changed
     * run; the caret positions prove the change is a continuation of the same edit (typing forward
     * at one spot, or backspacing at one spot) rather than a jump, a selection or a paste.
     *
     * PERF-09: the run is exactly one character in both directions. The old 1-8 character window
     * merged a short paste - and every IME commit or autocorrect replacement of that size - into the
     * preceding typing run, so undo stepped over the paste as if it had been typed. Anything larger
     * than one code unit is now its own undo point.
     */
    private fun isTypingContinuation(previous: TextFieldValue, next: TextFieldValue): Boolean {
        val prevText = previous.text
        val nextText = next.text
        if (prevText == nextText) return false
        if (!previous.selection.collapsed || !next.selection.collapsed) return false

        val maxCommon = minOf(prevText.length, nextText.length)
        var prefix = 0
        while (prefix < maxCommon && prevText[prefix] == nextText[prefix]) prefix++
        var suffix = 0
        while (
            suffix < maxCommon - prefix &&
            prevText[prevText.length - 1 - suffix] == nextText[nextText.length - 1 - suffix]
        ) {
            suffix++
        }

        val prevChanged = prevText.length - prefix - suffix
        val nextChanged = nextText.length - prefix - suffix
        return when {
            nextChanged == 1 && prevChanged == 0 ->
                // Insertion at the caret; the caret ends up after what was typed.
                previous.selection.start == prefix &&
                    next.selection.start == prefix + nextChanged
            prevChanged == 1 && nextChanged == 0 ->
                // Deletion at the caret: backspace leaves the caret before the removed run,
                // forward delete leaves it where it was.
                next.selection.start == prefix &&
                    (previous.selection.start == prefix + prevChanged ||
                        previous.selection.start == prefix)
            else -> false
        }
    }

    private fun sizeOf(value: TextFieldValue): Long = value.text.length.toLong() * 2L

    fun undo() {
        if (undoHistory.size > 1) {
            pushRedo(input)
            historyBytes -= sizeOf(undoHistory.removeLast())
            input = undoHistory.last()
        }
        canUndo = undoHistory.size > 1
        canRedo = redoHistory.isNotEmpty()
    }

    fun redo() {
        redoHistory.removeLastOrNull()?.let { value ->
            redoBytes -= sizeOf(value)
            push(value)
            input = value
        }
        canUndo = undoHistory.size > 1
        canRedo = redoHistory.isNotEmpty()
    }

    private companion object {
        /** Keeps the resident history bounded even for a six-figure-character note. */
        const val MAX_HISTORY_SIZE = 100

        /**
         * PERF-09: total UTF-16 bytes across undo snapshots (~4 MB).
         *
         * Large enough for the save-point bar and a couple of real undo steps on a long note,
         * small enough that 100 full-document copies can never accumulate.
         */
        const val MAX_HISTORY_BYTES = 4L * 1024 * 1024

        /** Undo must always have one earlier snapshot to restore. */
        const val MIN_HISTORY_SIZE = 2
    }
}

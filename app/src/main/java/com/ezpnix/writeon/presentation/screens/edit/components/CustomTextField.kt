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

    init {
        undoHistory.add(input)
    }

    fun onInput(value: TextFieldValue) {
        if (undoHistory.lastOrNull() == value) return
        redoHistory.clear()
        // Coalesce a typing run: consecutive single-caret appends at the end of the text replace
        // the tail entry instead of pushing another full copy of the document. Without this a
        // long note still grew the history by one whole string per keystroke until the cap kicked
        // in; with it a run costs one entry no matter how long it is.
        val last = undoHistory.lastOrNull()
        if (undoHistory.size >= 2 && last != null && isTypingContinuation(last, value)) {
            undoHistory.removeLast()
        }
        undoHistory.add(value)
        while (undoHistory.size > MAX_HISTORY_SIZE) undoHistory.removeFirst()
        input = value
    }

    /**
     * Drops the pre-save history: the database now holds [current], so the only undo a user needs
     * after saving is the save-point bar, and every retained snapshot is pure memory overhead.
     */
    fun clearTo(current: TextFieldValue) {
        undoHistory.clear()
        redoHistory.clear()
        undoHistory.add(current)
        input = current
    }

    /** True when [next] is just [previous] with a few characters typed at its end. */
    private fun isTypingContinuation(previous: TextFieldValue, next: TextFieldValue): Boolean {
        val prevText = previous.text
        val nextText = next.text
        if (nextText.length <= prevText.length) return false
        if (nextText.length - prevText.length > MAX_COALESCE_CHARS) return false
        if (!nextText.startsWith(prevText)) return false
        // Only merge while the caret was parked at the end for both snapshots; a jump to the
        // middle (selection, paste, cursor move) is a distinct undo point.
        return previous.selection.end == prevText.length && next.selection.end == nextText.length
    }

    fun undo() {
        if (undoHistory.size > 1) {
            redoHistory.add(input)
            undoHistory.removeLast()
            input = undoHistory.last()
        }
    }

    fun redo() {
        redoHistory.removeLastOrNull()?.let { value ->
            undoHistory.add(value)
            while (undoHistory.size > MAX_HISTORY_SIZE) undoHistory.removeFirst()
            input = value
        }
    }

    private companion object {
        /** Keeps the resident history bounded even for a six-figure-character note. */
        const val MAX_HISTORY_SIZE = 100
        /** One coalesced typing entry may absorb at most this many characters at once. */
        const val MAX_COALESCE_CHARS = 8
    }
}

package com.ezpnix.writeon.presentation.screens.edit.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PERF-09: what is one undo step.
 *
 * Only a one-character insertion or deletion that continues the same caret run coalesces. A short
 * paste (the old eight-character window), an IME commit or autocorrect starts a new snapshot, and a
 * delete/retype cycle that lands back on the same text must not leave a no-op history entry.
 */
class UndoRedoCoalescingRulesTest {

    @Test
    fun `a one character insertion continues the run`() {
        val state = UndoRedoState(TextFieldValue("ab", TextRange(1)))
        state.onInput(TextFieldValue("aXb", TextRange(2)))
        state.onInput(TextFieldValue("aXYb", TextRange(3)))

        assertEquals(2, state.undoDepth)
        state.undo()
        assertEquals("ab", state.input.text)
    }

    @Test
    fun `a one character edit at a different caret does not continue the run`() {
        val state = UndoRedoState(TextFieldValue("ab", TextRange(1)))
        state.onInput(TextFieldValue("aXb", TextRange(2)))
        // A second single-character edit, but at the end of the text instead of the run's caret.
        state.onInput(TextFieldValue("aXbY", TextRange(4)))

        assertEquals("a caret jump starts a new undo point", 3, state.undoDepth)
        state.undo()
        assertEquals("aXb", state.input.text)
    }

    @Test
    fun `an eight character paste starts a new undo point`() {
        val state = UndoRedoState(TextFieldValue("ab", TextRange(1)))
        state.onInput(TextFieldValue("aXb", TextRange(2)))
        state.onInput(TextFieldValue("aX12345678b", TextRange(10)))

        assertEquals("the paste must not be merged into the typing run", 3, state.undoDepth)
        state.undo()
        assertEquals("aXb", state.input.text)
        state.undo()
        assertEquals("ab", state.input.text)
    }

    @Test
    fun `delete and retype of the same character leaves no empty undo step`() {
        val state = UndoRedoState(TextFieldValue("x", TextRange(1)))
        state.onInput(TextFieldValue("xa", TextRange(2)))
        assertTrue(state.canUndo)

        // Backspace the character just typed: the coalescing uncovers the initial snapshot, and the
        // text is already "x" again. Adding it would make canUndo true for a no-op.
        state.onInput(TextFieldValue("x", TextRange(1)))

        assertEquals(1, state.undoDepth)
        assertFalse(state.canUndo)
        assertEquals("x", state.input.text)
        state.undo()
        assertEquals("x", state.input.text)
    }

    @Test
    fun `an input identical to the snapshot is not a new history entry`() {
        val state = UndoRedoState(TextFieldValue("same", TextRange(2)))
        state.onInput(TextFieldValue("same", TextRange(2)))
        state.onInput(TextFieldValue("same", TextRange(2)))

        assertEquals(1, state.undoDepth)
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
    }

    @Test
    fun `a delete followed by a different character is still one typing run`() {
        val state = UndoRedoState(TextFieldValue("ab", TextRange(1)))
        state.onInput(TextFieldValue("b", TextRange(0)))
        state.onInput(TextFieldValue("Yb", TextRange(1)))

        // Both edits continue at the same caret, so they coalesce into the run that started from
        // "ab"; one undo is enough to get the original text back.
        assertEquals(2, state.undoDepth)
        state.undo()
        assertEquals("ab", state.input.text)
    }
}

package com.ezpnix.writeon.presentation.screens.edit.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PERF-09: the undo stack is bounded by bytes and merges any contiguous edit at the caret.
 *
 * The full-document snapshots are what make this necessary: 100 entries on a 500k character body
 * could retain ~100 MB. These cases pin the budget and the generalized coalescing (mid-text
 * insertions and deletions merge; a paste breaks the run).
 */
class UndoRedoStateBudgetTest {

    @Test
    fun `two mid-text single character insertions merge into one undo point`() {
        val state = UndoRedoState(TextFieldValue("abcdef", TextRange(2)))

        state.onInput(TextFieldValue("abXcdef", TextRange(3)))
        state.onInput(TextFieldValue("abXYcdef", TextRange(4)))

        assertEquals("init snapshot plus one coalesced run", 2, state.undoDepth)
        state.undo()
        assertEquals("abcdef", state.input.text)
        state.redo()
        assertEquals("abXYcdef", state.input.text)
    }

    @Test
    fun `consecutive mid-text backspaces merge`() {
        val state = UndoRedoState(TextFieldValue("abcdef", TextRange(4)))

        state.onInput(TextFieldValue("abcef", TextRange(3)))
        state.onInput(TextFieldValue("abef", TextRange(2)))

        assertEquals(2, state.undoDepth)
        state.undo()
        assertEquals("abcdef", state.input.text)
    }

    @Test
    fun `a paste longer than one coalescing step breaks the run`() {
        val state = UndoRedoState(TextFieldValue("ab", TextRange(1)))

        state.onInput(TextFieldValue("aXYZWabcdefghijb", TextRange(13)))

        assertEquals("the paste is its own undo point", 2, state.undoDepth)
        state.undo()
        assertEquals("ab", state.input.text)
    }

    @Test
    fun `moving the caret does not create an undo point or clear redo`() {
        val state = UndoRedoState(TextFieldValue("abcdef", TextRange(2)))
        state.onInput(TextFieldValue("abcXdef", TextRange(4)))
        state.undo()
        assertEquals("abcdef", state.input.text)
        assertTrue(state.canRedo)

        // R2-06: selection-only changes are not edits. This used to clear redo and push a
        // full-document snapshot that undo could not visibly revert.
        state.onInput(TextFieldValue("abcdef", TextRange(4)))

        assertTrue("redo survives a caret move", state.canRedo)
        assertEquals("only the initial snapshot", 1, state.undoDepth)
        state.redo()
        assertEquals("abcXdef", state.input.text)
    }

    @Test
    fun `a long body cannot retain more than the byte budget`() {
        val base = "x".repeat(500_000)
        val state = UndoRedoState(TextFieldValue(base, TextRange(0)))
        var text = base

        // Every edit lands at a different offset, so none of them coalesce and the stack is forced
        // to evict: without the byte budget this would hold 200 full 1 MB snapshots.
        repeat(200) { index ->
            val position = (index * 7919) % text.length
            text = text.substring(0, position) + "y" + text.substring(position)
            state.onInput(TextFieldValue(text, TextRange(position + 1)))
        }

        assertTrue(
            "retained ${state.retainedBytes} bytes",
            state.retainedBytes <= 4L * 1024 * 1024
        )
        assertTrue("undo must keep at least two snapshots", state.undoDepth >= 2)
    }

    @Test
    fun `redo snapshots stay inside the same byte budget`() {
        val base = "x".repeat(500_000)
        val state = UndoRedoState(TextFieldValue(base, TextRange(0)))
        var text = base
        repeat(40) { index ->
            val position = (index * 7919) % text.length
            text = text.substring(0, position) + "y" + text.substring(position)
            state.onInput(TextFieldValue(text, TextRange(position + 1)))
        }
        // R2-06: every undo moves a whole-document snapshot into redo, which used to be bounded
        // only by the entry count (up to 100 x 1 MB here).
        repeat(40) {
            if (state.canUndo) state.undo()
        }

        assertTrue(
            "retained ${state.retainedBytes} bytes across undo and redo",
            state.retainedBytes <= 4L * 1024 * 1024
        )
        assertTrue("redo cannot exceed the entry cap", state.redoDepth <= 100)
    }
}

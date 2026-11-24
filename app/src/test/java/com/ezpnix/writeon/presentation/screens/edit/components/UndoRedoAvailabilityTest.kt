package com.ezpnix.writeon.presentation.screens.edit.components

import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-07: the toolbar buttons read [UndoRedoState.canUndo]/[UndoRedoState.canRedo] to disable
 * themselves on an empty stack. They have to flip with every mutation, not only when the text
 * happens to change scope.
 */
class UndoRedoAvailabilityTest {

    @Test
    fun `availability follows the two stacks and resets at the save point`() {
        val state = UndoRedoState(TextFieldValue("a"))
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)

        state.onInput(TextFieldValue("ab"))
        assertTrue(state.canUndo)
        assertFalse(state.canRedo)

        state.undo()
        assertEquals("a", state.input.text)
        assertFalse(state.canUndo)
        assertTrue(state.canRedo)

        state.redo()
        assertEquals("ab", state.input.text)
        assertTrue(state.canUndo)
        assertFalse(state.canRedo)

        state.clearTo(TextFieldValue("ab"))
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
    }
}

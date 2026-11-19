package com.ezpnix.writeon.presentation.screens.edit.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class UndoRedoStateTest {
    @Test
    fun `undo and redo preserve selection`() {
        val state = UndoRedoState()
        state.onInput(TextFieldValue("first", TextRange(2)))
        state.onInput(TextFieldValue("second", TextRange(3)))

        state.undo()
        assertEquals(TextFieldValue("first", TextRange(2)), state.input)
        state.redo()
        assertEquals(TextFieldValue("second", TextRange(3)), state.input)
    }

    @Test
    fun `history is bounded`() {
        val state = UndoRedoState()
        repeat(150) { state.onInput(TextFieldValue("value-$it")) }

        // init + 150 inputs capped at 100 keeps value-50..value-149; undoing past the floor
        // must stop there instead of looping or crashing.
        repeat(100) { state.undo() }
        assertEquals("value-50", state.input.text)
        state.undo()
        assertEquals("value-50", state.input.text)
    }
}

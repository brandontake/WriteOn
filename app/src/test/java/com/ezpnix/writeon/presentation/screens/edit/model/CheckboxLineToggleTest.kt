package com.ezpnix.writeon.presentation.screens.edit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SPL-1: the split preview toggles a checkbox in the live body, never by replaying a debounced
 * snapshot. These cases pin the two things that can go wrong without a Compose test: replacing the
 * wrong line when the user typed above it, and rewriting the body when the checkbox itself changed.
 */
class CheckboxLineToggleTest {

    @Test
    fun `toggles the checkbox on the hinted line`() {
        val body = "title\n[ ] task\nfooter"

        assertEquals(
            "title\n[x] task\nfooter",
            CheckboxLineToggle.toggle(body, lineNumber = 1, text = "task", checked = true)
        )
    }

    @Test
    fun `keeps indentation and CRLF`() {
        val body = "title\r\n  [ ] task\r\nfooter"

        assertEquals(
            "title\r\n  [x] task\r\nfooter",
            CheckboxLineToggle.toggle(body, lineNumber = 1, text = "task", checked = true)
        )
    }

    @Test
    fun `finds a unique checkbox after typing above shifted its line`() {
        val body = "new first line\ntitle\n[ ] task"

        assertEquals(
            "new first line\ntitle\n[x] task",
            CheckboxLineToggle.toggle(body, lineNumber = 2, text = "task", checked = true)
        )
    }

    @Test
    fun `does nothing when the checkbox text itself changed in the debounce window`() {
        val body = "title\n[ ] changed"

        assertNull(CheckboxLineToggle.toggle(body, lineNumber = 1, text = "task", checked = true))
    }

    @Test
    fun `duplicate checkbox text resolves to the closest line`() {
        val body = "[ ] same\n[ ] same"

        assertEquals(
            "[ ] same\n[x] same",
            CheckboxLineToggle.toggle(body, lineNumber = 1, text = "same", checked = true)
        )
    }

    @Test
    fun `untoggles a checked box`() {
        val body = "title\n[x] task"

        assertEquals(
            "title\n[ ] task",
            CheckboxLineToggle.toggle(body, lineNumber = 1, text = "task", checked = false)
        )
    }

    @Test
    fun `a non-checkbox line is not rewritten`() {
        assertNull(CheckboxLineToggle.toggle("title\ntext", 1, "text", true))
    }
}

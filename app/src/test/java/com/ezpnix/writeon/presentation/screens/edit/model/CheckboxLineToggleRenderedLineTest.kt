package com.ezpnix.writeon.presentation.screens.edit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UX-08: the preview page's checkbox path rewrites only the marker.
 *
 * The old `"$prefix ${element.text}"` rebuild lost indentation, CRLF and the `[ ]task` shape.
 * These cases pin all three, plus the stale-line guard the preview shares with the split pane.
 */
class CheckboxLineToggleRenderedLineTest {

    @Test
    fun `keeps leading indentation`() {
        val body = "title\n    [ ] child"

        assertEquals(
            "title\n    [x] child",
            CheckboxLineToggle.toggleRenderedLine(body, 1, "child", checked = true)
        )
    }

    @Test
    fun `keeps CRLF line endings`() {
        val body = "title\r\n  [ ] task\r\nfooter"

        assertEquals(
            "title\r\n  [x] task\r\nfooter",
            CheckboxLineToggle.toggleRenderedLine(body, 1, "task", checked = true)
        )
    }

    @Test
    fun `a marker glued to its text gains exactly one space`() {
        val body = "[ ]task"

        assertEquals(
            "[x] task",
            CheckboxLineToggle.toggleRenderedLine(body, 0, "task", checked = true)
        )
    }

    @Test
    fun `the rest of the line is preserved verbatim`() {
        val body = "  [ ]  spaced  "

        assertEquals(
            "  [x]  spaced  ",
            CheckboxLineToggle.toggleRenderedLine(body, 0, "spaced", checked = true)
        )
    }

    @Test
    fun `the requested line is edited even when another line has the same text`() {
        val body = "[ ] same\n[ ] same"

        assertEquals(
            "[x] same\n[ ] same",
            CheckboxLineToggle.toggleRenderedLine(body, 0, "same", checked = true)
        )
        assertEquals(
            "[ ] same\n[x] same",
            CheckboxLineToggle.toggleRenderedLine(body, 1, "same", checked = true)
        )
    }

    @Test
    fun `a stale text or an out-of-range line does nothing`() {
        assertNull(CheckboxLineToggle.toggleRenderedLine("text\n[ ] changed", 1, "task", true))
        assertNull(CheckboxLineToggle.toggleRenderedLine("text", -1, "text", true))
        assertNull(CheckboxLineToggle.toggleRenderedLine("text", 5, "text", true))
    }

    @Test
    fun `a non-checkbox line is not rewritten`() {
        assertNull(CheckboxLineToggle.toggleRenderedLine("title\ntext", 1, "text", true))
    }

    @Test
    fun `untoggling keeps the same rules`() {
        assertEquals(
            "  [ ] task\r\n",
            CheckboxLineToggle.toggleRenderedLine("  [x] task\r\n", 0, "task", checked = false)
        )
    }
}

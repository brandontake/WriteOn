package com.ezpnix.writeon.presentation.screens.edit.model

import com.ezpnix.writeon.presentation.components.markdown.CheckboxItem
import com.ezpnix.writeon.presentation.components.markdown.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REG-10: `[ ]task` is parsed as a checkbox with the label alone, and the marker primitive can
 * toggle it.
 *
 * The parser used to keep the marker in `CheckboxItem.text` whenever no space followed it. The
 * widget then printed `[ ]task` next to its checkbox, and `CheckboxLineToggle.matches` compared
 * `task` against `[ ]task`, so every preview/split/widget tap was a silent no-op.
 */
class CheckboxMarkerParsingTest {

    private fun checkbox(markdown: String): CheckboxItem =
        parseMarkdown(markdown, latexEnabled = false).filterIsInstance<CheckboxItem>().single()

    @Test
    fun `a marker followed by a space stores only the label`() {
        assertEquals("task", checkbox("[ ] task").text)
        assertTrue(!checkbox("[ ] task").checked)
        assertEquals("task", checkbox("[x] task").text)
        assertTrue(checkbox("[x] task").checked)
    }

    @Test
    fun `a marker glued to its text stores only the label`() {
        assertEquals("task", checkbox("[ ]task").text)
        assertEquals("task", checkbox("[x]task").text)
        assertTrue(checkbox("[x]task").checked)
    }

    @Test
    fun `an indented checkbox stores only the label`() {
        assertEquals("child", checkbox("    [ ] child").text)
        assertEquals("child", checkbox("  [x]child").text)
    }

    @Test
    fun `a CRLF checkbox stores only the label`() {
        assertEquals("task", checkbox("first\r\n[ ] task\r\nlast").text)
    }

    @Test
    fun `the parsed label toggles the glued and indented forms in place`() {
        assertEquals(
            "[x] task",
            CheckboxLineToggle.toggleRenderedLine("[ ]task", 0, checkbox("[ ]task").text, true)
        )
        assertEquals(
            "  [x] task\r\n",
            CheckboxLineToggle.toggleRenderedLine("  [ ] task\r\n", 0, "task", true)
        )
        assertEquals(
            "first\n    [x] child",
            CheckboxLineToggle.toggleRenderedLine(
                "first\n    [ ] child",
                1,
                "child",
                true
            )
        )
    }

    @Test
    fun `the split-preview toggle also matches the glued form`() {
        assertEquals(
            "[x] task",
            CheckboxLineToggle.toggle("[ ]task", lineNumber = 0, text = "task", checked = true)
        )
        assertEquals(
            "  [x] task\r\n",
            CheckboxLineToggle.toggle("  [ ] task\r\n", lineNumber = 0, text = "task", checked = true)
        )
        assertEquals(
            "first\n    [x] child",
            CheckboxLineToggle.toggle("first\n    [ ] child", 1, "child", checked = true)
        )
    }

    @Test
    fun `a stale duplicate label is a no-op instead of toggling the wrong line`() {
        // R3-04: the user tapped the second "dup", but that line's text changed in the live body.
        // A text-only fallback would find the first "dup" - a different checkbox - and toggle it.
        assertEquals(
            null,
            CheckboxLineToggle.toggle(
                markdown = "[ ] dup\n[ ] changed",
                lineNumber = 1,
                text = "dup",
                checked = true
            )
        )
        // A unique label whose line merely shifted (the hinted line is plain text, not a checkbox)
        // still resolves by text.
        assertEquals(
            "first\n[x] task",
            CheckboxLineToggle.toggle(
                markdown = "first\n[ ] task",
                lineNumber = 0,
                text = "task",
                checked = true
            )
        )
    }
}

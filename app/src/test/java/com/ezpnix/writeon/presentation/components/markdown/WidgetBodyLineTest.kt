package com.ezpnix.writeon.presentation.components.markdown

import com.ezpnix.writeon.presentation.screens.edit.model.CheckboxLineToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R4-05: the widget renders `# <title>`, a blank line and the body, so a parsed checkbox line has
 * to be offset by every title line. A fixed offset of two made the first body checkbox of a
 * multi-line title unmappable (and, after R3-04, a silent no-op).
 */
class WidgetBodyLineTest {

    @Test
    fun `a single-line title offsets by two lines`() {
        assertEquals(0, widgetBodyLine(2, "Title"))
        assertEquals(1, widgetBodyLine(3, "Title"))
    }

    @Test
    fun `a multi-line title offsets by every title line`() {
        // "# a", "b", "" then the body.
        assertEquals(0, widgetBodyLine(3, "a\nb"))
        assertEquals(2, widgetBodyLine(5, "a\nb"))
        // "# a", "b", "c", "" then the body.
        assertEquals(0, widgetBodyLine(4, "a\nb\nc"))
        assertEquals(1, widgetBodyLine(5, "a\nb\nc"))
    }

    @Test
    fun `a blank title has no offset`() {
        assertEquals(0, widgetBodyLine(0, ""))
        assertEquals(4, widgetBodyLine(4, "   "))
    }

    @Test
    fun `a title with CR or CRLF line breaks counts every rendered line`() {
        // R5-06: the renderer splits with String.lines(), which treats a lone CR as a break. A
        // `count('\n')` implementation under-counted it and shifted the body by one.
        assertEquals(0, widgetBodyLine(3, "a\rb"))
        assertEquals(1, widgetBodyLine(4, "a\rb"))
        assertNull(widgetBodyLine(2, "a\rb"))

        assertEquals(0, widgetBodyLine(3, "a\r\nb"))
        assertEquals(1, widgetBodyLine(4, "a\r\nb"))
    }

    @Test
    fun `a checkbox inside the title is not a body line`() {
        assertNull(widgetBodyLine(1, "a\n[ ] in the title"))
        assertNull(widgetBodyLine(2, "a\n[ ] in the title"))
    }

    @Test
    fun `the first body checkbox maps to the first body line for a multi-line title`() {
        val title = "a\nb"
        val body = "[ ] task"
        val combined = buildString {
            appendLine("# $title")
            appendLine()
            append(body)
        }
        val lines = combined.lines()
        val checkboxLine = lines.indexOfFirst { it.trim().startsWith("[ ]") }
        assertEquals("the checkbox is the fourth rendered line", 3, checkboxLine)

        val mapped = widgetBodyLine(checkboxLine, title)
        assertEquals("it is the first line of the body", 0, mapped)
        assertEquals("[x] task", CheckboxLineToggle.toggle(body, mapped!!, "task", true))
    }
}

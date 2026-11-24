package com.ezpnix.writeon.presentation.components.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PERF-13: the widget parses a bounded prefix of the note, not the whole body.
 *
 * The output must stay a prefix of the input: the parsed line numbers are used to rewrite the full
 * body when a widget checkbox is tapped, and the editor keeps the full text.
 */
class WidgetPreviewTruncationTest {

    @Test
    fun `a short note is returned unchanged`() {
        val body = "# Title\n\nline one\nline two"
        assertEquals(body, widgetPreviewMarkdown(body))
    }

    @Test
    fun `at most fifteen lines are kept`() {
        val body = (0 until 40).joinToString("\n") { "line-$it" }

        val preview = widgetPreviewMarkdown(body)

        assertEquals(15, preview.count { it == '\n' })
        assertTrue(body.startsWith(preview))
        assertEquals(
            (0 until 15).joinToString("\n") { "line-$it" } + "\n",
            preview
        )
    }

    @Test
    fun `a single long line is cut at the character cap`() {
        val body = "x".repeat(5_000)

        val preview = widgetPreviewMarkdown(body)

        assertEquals(WIDGET_PREVIEW_MAX_CHARS, preview.length)
        assertTrue(body.startsWith(preview))
    }

    @Test
    fun `the character cut never splits an emoji`() {
        val prefix = "a".repeat(WIDGET_PREVIEW_MAX_CHARS - 1)
        val body = prefix + "\uD83D\uDE00" + "tail"

        val preview = widgetPreviewMarkdown(body)

        assertEquals(prefix, preview)
        assertFalse(
            "the preview must not end in an isolated high surrogate",
            preview.last().isHighSurrogate()
        )
    }

    @Test
    fun `an emoji that fits before the cap is kept whole`() {
        val prefix = "a".repeat(WIDGET_PREVIEW_MAX_CHARS - 2)
        val body = prefix + "\uD83D\uDE00" + "tail"

        val preview = widgetPreviewMarkdown(body)

        assertEquals(prefix + "\uD83D\uDE00", preview)
    }

    @Test
    fun `an empty body stays empty`() {
        assertEquals("", widgetPreviewMarkdown(""))
    }
}

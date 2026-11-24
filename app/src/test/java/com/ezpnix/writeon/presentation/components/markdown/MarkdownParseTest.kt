package com.ezpnix.writeon.presentation.components.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PERF-02: the pure parse the async preview path publishes.
 *
 * `parseMarkdown` is what [MarkdownText] runs inside `withContext(Dispatchers.Default)`; pinning
 * its output here keeps the element list (and the checkbox line numbers the preview relies on)
 * stable without a Compose or Looper test.
 */
class MarkdownParseTest {

    @Test
    fun `parses headings and checkboxes with their source line numbers`() {
        val elements = parseMarkdown("# Title\n[ ] task\n[x] done", latexEnabled = false)

        assertEquals(Heading(level = 1, text = "Title"), elements[0])
        assertEquals(CheckboxItem(text = "task", checked = false, lineNumber = 1), elements[1])
        assertEquals(CheckboxItem(text = "done", checked = true, lineNumber = 2), elements[2])
    }

    @Test
    fun `parses an attachment line as an insertion element`() {
        val elements = parseMarkdown("![](attachment://a1b2c3d4)", latexEnabled = false)

        assertEquals(1, elements.size)
        assertTrue(elements.single() is ImageInsertion)
    }

    @Test
    fun `latex stays disabled unless asked for`() {
        val withoutLatex = parseMarkdown("$$\nx^2\n$$", latexEnabled = false)
        val withLatex = parseMarkdown("$$\nx^2\n$$", latexEnabled = true)

        assertTrue(withoutLatex.none { it is MathBlock })
        assertTrue(withLatex.any { it is MathBlock })
    }
}

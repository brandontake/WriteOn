package com.ezpnix.writeon.presentation.screens.edit.segments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PERF-01: an ordinary note must never enter the segmentation path.
 *
 * The counter lives in the test, not in production code: `segmentsFor` takes the segmentize
 * function as a parameter, so these tests pass a counting wrapper and assert it is not called at
 * all for a body without attachment syntax.
 */
class NoteSegmentsFastPathTest {

    @Test
    fun `a body without attachment syntax never calls segmentize`() {
        val body = buildString {
            repeat(10_000) { append("plain line ").append(it).append('\n') }
        }
        var calls = 0

        val segments = segmentsFor(body) { text ->
            calls++
            segmentize(text)
        }

        assertEquals("an ordinary note must skip segmentation entirely", 0, calls)
        val only = segments.single() as TextSegment
        assertEquals(body, only.text)
        assertEquals(0, only.startOffset)
        assertEquals(body.length, only.endOffset)
        assertEquals(body.count { it == '\n' }, only.endLine)
        assertEquals("assemble must still reproduce the body", body, assemble(segments))
    }

    @Test
    fun `an empty body is one empty field`() {
        var calls = 0
        val segments = segmentsFor("") { text ->
            calls++
            segmentize(text)
        }
        assertEquals(0, calls)
        val only = segments.single() as TextSegment
        assertEquals("", only.text)
        assertEquals(0, only.startOffset)
        assertEquals(0, only.endOffset)
        assertEquals("", assemble(segments))
    }

    @Test
    fun `legacy and current attachment markers still segmentize`() {
        val bodies = listOf(
            "voice\n?audio(/sdcard/one.m4a)",
            "picture\n!(/sdcard/one.jpg)",
            "![alt](https://example.com/a.png)",
            "![](attachment://a1b2c3d4)",
            "[caption](attachment://e5f6a7b8)",
        )
        bodies.forEach { body ->
            var calls = 0
            segmentsFor(body) { text ->
                calls++
                segmentize(text)
            }
            assertEquals("expected the marker path for: $body", 1, calls)
        }
    }

    @Test
    fun `currentTextSegment resolves the whole body without attachments`() {
        val body = "alpha\nbeta\ngamma"
        val segment = currentTextSegment(body, "text:0")!!
        assertEquals(0, segment.startOffset)
        assertEquals(body.length, segment.endOffset)
        assertEquals(body, segment.text)
    }

    @Test
    fun `currentTextSegment finds the key when attachments exist`() {
        val body = "alpha\n![](attachment://a1)\nbeta"
        // REG-09 residual: text runs are keyed by the attachment before them, not by ordinal.
        val segment = currentTextSegment(body, "text:after:attachment:a1")!!
        assertEquals("beta", segment.text)
        assertNull(currentTextSegment(body, "text:1"))
        assertNull(currentTextSegment(body, "text:missing"))
    }

    @Test
    fun `segmentize still keeps indented fences and trailing spaces exact`() {
        // These are the lines the one-pass loop had to keep behaving like the old splitLines
        // version: whitespace before a fence still toggles it, and spaces/CR stay in text.
        val body = "intro\n  ```\n![](attachment://not-a-block)\n  ```\n  ![](attachment://a1)  \nend"
        val segments = segmentize(body)
        assertEquals("  ![](attachment://a1)  ", (segments[1] as AttachmentSegment).raw)
        assertEquals(body, assemble(segments))
    }
}

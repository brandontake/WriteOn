package com.ezpnix.writeon.presentation.screens.edit.segments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * REG-09: a key from a disposed segment must not resolve to the whole body.
 *
 * The PERF-01 fast path used to answer every key with the single whole-body segment. After the last
 * attachment line was deleted, a still-running field with the old key (`text:1`) delivered one more
 * `onValueChange`, and the editor replaced the entire note with that field's text.
 */
class NoteSegmentsStaleKeyTest {

    @Test
    fun `an old key on a body without markers resolves to nothing`() {
        assertNull(currentTextSegment("plain body", "text:1"))
        assertNull(currentTextSegment("plain body", "text:7"))
    }

    @Test
    fun `the current whole-body key still resolves`() {
        val segment = currentTextSegment("plain body", "text:0")!!
        assertEquals("text:0", segment.key)
        assertEquals(0, segment.startOffset)
        assertEquals("plain body".length, segment.endOffset)
        assertEquals("plain body", segment.text)
    }

    @Test
    fun `a body that still has attachment syntax keeps the exact key lookup`() {
        val body = "before\n![](attachment://a1b2c3d4)\nafter"
        // REG-09 residual: the trailing run is named after the attachment that precedes it. An
        // ordinal key would be reused by a different range after the attachment line changes.
        assertEquals(
            "after",
            currentTextSegment(body, "text:after:attachment:a1b2c3d4")?.text
        )
        assertNull(currentTextSegment(body, "text:1"))
        assertNull(currentTextSegment(body, "text:2"))
    }
}

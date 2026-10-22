package com.ezpnix.writeon.presentation.screens.edit.segments

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSegmentsTest {

    @Test
    fun `empty body is one empty text segment`() {
        val segments = segmentize("")
        assertEquals(1, segments.size)
        val text = segments.single() as TextSegment
        assertEquals("", text.text)
        assertEquals(0, text.startOffset)
        assertEquals(0, text.endOffset)
        assertRoundTrip("")
    }

    @Test
    fun `plain text round trips and stays one field`() {
        val body = "First line\n\nSecond paragraph"
        val segments = segmentize(body)
        assertEquals(1, segments.size)
        assertEquals(body, (segments.single() as TextSegment).text)
        assertRoundTrip(body)
    }

    @Test
    fun `trailing newline stays in the text segment`() {
        val body = "First line\n"
        val segments = segmentize(body)
        assertEquals(1, segments.size)
        assertEquals("First line\n", (segments.single() as TextSegment).text)
        assertRoundTrip(body)
    }

    @Test
    fun `image and audio lines become blocks`() {
        val body = "Intro\n![](attachment://img12345)\n[Meeting](attachment://aud67890)\nOutro"
        val segments = segmentize(body)
        assertEquals(4, segments.size)

        assertEquals("Intro", (segments[0] as TextSegment).text)
        val image = segments[1] as AttachmentSegment
        assertEquals("img12345", image.attachmentId)
        assertEquals("image", image.type)
        assertEquals("Intro", body.substring(0, image.startOffset).removeSuffix("\n"))

        val audio = segments[2] as AttachmentSegment
        assertEquals("aud67890", audio.attachmentId)
        assertEquals("audio", audio.type)
        assertEquals("Meeting", audio.caption)
        assertEquals("Outro", (segments[3] as TextSegment).text)
        assertRoundTrip(body)
    }

    @Test
    fun `consecutive attachments stay separate and are not merged`() {
        val body = "![](attachment://one)\n![](attachment://two)\n[voice](attachment://three)"
        val segments = segmentize(body)
        assertEquals(4, segments.size)
        assertTrue(segments[0] is AttachmentSegment)
        assertTrue(segments[1] is AttachmentSegment)
        assertTrue(segments[2] is AttachmentSegment)
        assertTrue(segments[3] is TextSegment)
        assertTrue((segments[3] as TextSegment).synthetic)
        assertEquals("", (segments[3] as TextSegment).text)
        assertRoundTrip(body)
    }

    @Test
    fun `a body ending on an attachment gets a synthetic caret field without a newline`() {
        val body = "text\n![](attachment://img12345)"
        val segments = segmentize(body)
        assertEquals(3, segments.size)
        val tail = segments[2] as TextSegment
        assertTrue(tail.synthetic)
        assertEquals(body.length, tail.startOffset)
        assertEquals(body.length, tail.endOffset)
        assertRoundTrip(body)
    }

    @Test
    fun `a body ending on an attachment with a newline keeps a real empty line`() {
        val body = "text\n![](attachment://img12345)\n"
        val segments = segmentize(body)
        assertEquals(3, segments.size)
        val tail = segments[2] as TextSegment
        assertEquals("", tail.text)
        assertEquals(false, tail.synthetic)
        assertRoundTrip(body)
    }

    @Test
    fun `attachment syntax inside a fenced code block remains text`() {
        val body = "```\n![](attachment://notAFile)\n[also](attachment://notAudio)\n```\nafter"
        val segments = segmentize(body)
        assertEquals(1, segments.size)
        assertTrue(segments.single() is TextSegment)
        assertRoundTrip(body)
    }

    @Test
    fun `double backtick fences do not count`() {
        val body = "``\n![](attachment://img12345)\n``"
        val segments = segmentize(body)
        assertEquals(3, segments.size)
        assertTrue(segments[1] is AttachmentSegment)
        assertRoundTrip(body)
    }

    @Test
    fun `crlf is preserved exactly and still recognises attachments`() {
        val body = "line one\r\n![](attachment://img12345)\r\n[Voice](attachment://aud67890)\r\n"
        val segments = segmentize(body)
        assertEquals(4, segments.size)
        assertEquals("line one\r", (segments[0] as TextSegment).text)
        assertTrue((segments[1] as AttachmentSegment).raw.endsWith("\r"))
        assertEquals("Voice", (segments[2] as AttachmentSegment).caption)
        assertRoundTrip(body)
    }

    @Test
    fun `offsets address the same source lines`() {
        val body = "alpha\n![](attachment://img12345)\nbeta"
        val segments = segmentize(body)
        segments.forEach { segment ->
            assertTrue(segment.startOffset in 0..body.length)
            assertTrue(segment.endOffset in segment.startOffset..body.length)
        }
        assertEquals("alpha", body.substring(segments[0].startOffset, segments[0].endOffset))
        assertEquals(
            "![](attachment://img12345)",
            body.substring(segments[1].startOffset, segments[1].endOffset)
        )
        assertEquals("beta", body.substring(segments[2].startOffset, segments[2].endOffset))
    }

    @Test
    fun `duplicate attachment ids still have unique LazyColumn keys`() {
        val body = """
            ![](attachment://same)
            [First](attachment://same)
            ![](attachment://same)
        """.trimIndent()
        val segments = segmentize(body)
        assertEquals(4, segments.size)
        assertEquals(segments.size, segments.map { it.key }.toSet().size)
        assertRoundTrip(body)
    }

    @Test
    fun `round trip over generated bodies`() {
        val random = Random(7523)
        val lf = String(byteArrayOf(10))
        val crlf = String(byteArrayOf(13, 10))
        val bareCr = String(byteArrayOf(13))
        val lines = listOf(
            "",
            "plain text",
            "## heading",
            "```",
            "```kotlin",
            "inside code",
            "![](attachment://a1b2c3d4)",
            "  ![](attachment://a1b2c3d4)",
            "[Voice](attachment://e5f6a7b8)",
            "[Same](attachment://a1b2c3d4)",
            "| a | b |",
            "line with ${bareCr}inside",
            "  trailing spaces  "
        )
        repeat(500) {
            val count = random.nextInt(0, 18)
            val newline = if (random.nextBoolean()) lf else crlf
            val body = buildString {
                repeat(count) { index ->
                    if (index > 0) append(newline)
                    append(lines[random.nextInt(lines.size)])
                }
            }
            assertEquals(body, assemble(segmentize(body)))
        }
    }

    private fun assertRoundTrip(body: String) {
        assertEquals(body, assemble(segmentize(body)))
    }
}

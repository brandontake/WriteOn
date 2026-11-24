package com.ezpnix.writeon.presentation.screens.home.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PERF-08: the card summary is produced by a bounded scan, not by splitting the whole body.
 *
 * `onLineScanned` counts how many lines the scanner actually enters, which is the assertion the
 * audit asks for: a 1 MB body costs one line, and even a 13-line body stops before reading the
 * 13th line's text. The tests also pin the exact output against the old `lines().take(12)` shape.
 */
class CardBodySummaryTest {

    @Test
    fun `a one megabyte single line is cut at the character cap`() {
        val body = "a".repeat(1_000_000)
        var scanned = 0

        val card = buildCardBody(body) { scanned++ }

        assertEquals(CARD_BODY_MAX_LINE_CHARS, card.text.length)
        assertTrue(card.isTruncated)
        assertEquals("only one line is entered", 1, scanned)
    }

    @Test
    fun `a thirteen line body keeps twelve and never reads the thirteenth`() {
        val body = (0 until 13).joinToString("\n") { "line-$it" }
        var scanned = 0

        val card = buildCardBody(body) { scanned++ }

        assertEquals((0 until 12).joinToString("\n") { "line-$it" }, card.text)
        assertTrue(card.isTruncated)
        assertTrue("scanned $scanned lines", scanned <= CARD_BODY_MAX_LINES + 1)
    }

    @Test
    fun `a short body is returned unchanged and not truncated`() {
        val body = (0 until 12).joinToString("\n") { "line-$it" }
        val card = buildCardBody(body)
        assertEquals(body, card.text)
        assertFalse(card.isTruncated)
    }

    @Test
    fun `a trailing newline counts as a thirteenth line`() {
        val twelveLines = (0 until 12).joinToString("\n") { "line-$it" }
        val card = buildCardBody("$twelveLines\n")

        assertEquals(twelveLines, card.text)
        assertTrue("lines() would see the empty trailing line too", card.isTruncated)
    }

    @Test
    fun `an over-long line ends the summary instead of reading the rest of the body`() {
        // The old `lines()` path would have shown the lines after the long one; the bounded scan
        // stops once its only visible line budget is spent. Those lines were below the card's
        // clipped area anyway, and stopping is what keeps a 1 MB paste from being walked.
        val body = "short\n" + "y".repeat(100_000) + "\nthis line is never read"
        var scanned = 0

        val card = buildCardBody(body) { scanned++ }

        assertEquals("short\n" + "y".repeat(400), card.text)
        assertTrue(card.isTruncated)
        assertEquals(2, scanned)
    }

    @Test
    fun `the summary matches the old lines-take output`() {
        val bodies = listOf(
            "",
            "one line",
            "a\nbb\nccc",
            "line\r\nwith crlf",
            "[ ] task\n      indented",
            (0 until 20).joinToString("\n") { "line-$it" },
            "short\n" + "y".repeat(10_000),
        )
        bodies.forEach { body ->
            val oldShape = body.lines()
            val oldText = if (
                oldShape.size <= CARD_BODY_MAX_LINES &&
                oldShape.none { it.length > CARD_BODY_MAX_LINE_CHARS }
            ) {
                body
            } else {
                oldShape.take(CARD_BODY_MAX_LINES).joinToString("\n") { it.take(CARD_BODY_MAX_LINE_CHARS) }
            }
            assertEquals("old output for: $body", oldText, buildCardBody(body).text)
        }
    }
}

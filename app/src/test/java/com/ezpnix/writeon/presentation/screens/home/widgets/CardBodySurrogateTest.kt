package com.ezpnix.writeon.presentation.screens.home.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PERF-08: the card's character cap is a UTF-16 index.
 *
 * A 400-character cut that lands inside an emoji leaves an isolated high surrogate, which renders as
 * a replacement glyph in the card title/body. The cut moves one back so the summary always ends on
 * a complete code point.
 */
class CardBodySurrogateTest {

    @Test
    fun `a cut between the halves of an emoji is moved back one`() {
        val prefix = "a".repeat(CARD_BODY_MAX_LINE_CHARS - 1)
        val body = prefix + "\uD83D\uDE00" + "more text"

        val card = buildCardBody(body)

        assertEquals("the split emoji is dropped, not left half-written", prefix, card.text)
        assertTrue(card.isTruncated)
        assertFalse(
            "the summary must not end in an isolated high surrogate",
            card.text.lastOrNull()?.isHighSurrogate() == true
        )
    }

    @Test
    fun `an emoji that fits before the cap is kept whole`() {
        val prefix = "a".repeat(CARD_BODY_MAX_LINE_CHARS - 2)
        val body = prefix + "\uD83D\uDE00" + "more text"

        val card = buildCardBody(body)

        assertEquals(prefix + "\uD83D\uDE00", card.text)
        assertEquals(CARD_BODY_MAX_LINE_CHARS, card.text.length)
        assertTrue(card.isTruncated)
    }

    @Test
    fun `a short body with emoji is untouched`() {
        val body = "hello \uD83D\uDE00 world"
        assertEquals(body, buildCardBody(body).text)
    }
}

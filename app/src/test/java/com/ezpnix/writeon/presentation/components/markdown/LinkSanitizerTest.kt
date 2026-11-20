package com.ezpnix.writeon.presentation.components.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scheme whitelist for note links (SEC-3).
 *
 * A note's text can come from a shared file, an imported backup or another app, so `[x](url)` is
 * untrusted input. Compose turns any accepted URL into `ACTION_VIEW`; these tests pin down which
 * values may reach that call and which have to render as plain text.
 */
class LinkSanitizerTest {

    @Test
    fun `http https and mailto links are accepted`() {
        assertEquals("https://example.com/a?b=1", sanitizeLinkUrl("https://example.com/a?b=1"))
        assertEquals("http://192.168.1.10:8080", sanitizeLinkUrl("http://192.168.1.10:8080"))
        assertEquals("mailto:someone@example.com", sanitizeLinkUrl("mailto:someone@example.com"))
        assertNotNull(sanitizeLinkUrl("https://a"))
    }

    @Test
    fun `scheme matching is case insensitive`() {
        assertNotNull("HTTPS must not be treated as an unknown scheme", sanitizeLinkUrl("HTTPS://example.com"))
        assertNotNull("MailTo must not be treated as an unknown scheme", sanitizeLinkUrl("MailTo:someone@example.com"))
    }

    @Test
    fun `script local and app launching schemes are rejected`() {
        val rejected = listOf(
            "javascript:alert(1)",
            "file:///data/data/com.ezpnix.writeon/files/secret",
            "content://com.android.providers.media.documents/document/1",
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "data:text/html,<script>alert(1)</script>",
            "tel:+123456",
            "chrome://settings",
            "about:blank",
            "ws://example.com/socket"
        )
        for (url in rejected) {
            assertNull("$url must be rejected", sanitizeLinkUrl(url))
        }
    }

    @Test
    fun `mixed case javascript scheme cannot bypass the whitelist`() {
        assertNull(sanitizeLinkUrl("JaVaScRiPt:alert(1)"))
        assertNull(sanitizeLinkUrl("jAvAsCrIpT:alert(1)"))
        assertNull(sanitizeLinkUrl("FILE:///sdcard/photo.jpg"))
        assertNull(sanitizeLinkUrl("Intent://x#Intent;end"))
    }

    @Test
    fun `whitespace and control characters inside a url are rejected`() {
        val rejected = listOf(
            "https://exa mple.com",
            "java\nscript:alert(1)",
            "java\tscript:alert(1)",
            "https://example.com/\u0000",
            "https://example.com/a b",
            "https://example.com/\u007F"
        )
        for (url in rejected) {
            assertNull("$url must be rejected", sanitizeLinkUrl(url))
        }
    }

    @Test
    fun `surrounding whitespace is trimmed rather than rejected`() {
        assertEquals("https://example.com", sanitizeLinkUrl("  https://example.com\n"))
    }

    @Test
    fun `schemeless and malformed values are rejected`() {
        val rejected = listOf(
            "",
            "   ",
            "example.com",
            "/etc/passwd",
            "//example.com",
            "https:",
            "mailto:",
            "1http://example.com",
            "ht tp://example.com"
        )
        for (url in rejected) {
            assertNull("'$url' must be rejected", sanitizeLinkUrl(url))
        }
    }

    @Test
    fun `overlong urls are rejected`() {
        val prefix = "https://example.com/"
        val justFits = prefix + "a".repeat(MAX_LINK_URL_LENGTH - prefix.length)
        assertEquals(MAX_LINK_URL_LENGTH, justFits.length)
        assertNotNull("a url at the cap must still work", sanitizeLinkUrl(justFits))

        val tooLong = prefix + "a".repeat(MAX_LINK_URL_LENGTH - prefix.length + 1)
        assertNull("a url over the cap must be rejected", sanitizeLinkUrl(tooLong))
    }

    @Test
    fun `an unsafe link renders without a link annotation`() {
        // No parentheses in the target: the markdown regex stops at the first `)`.
        val text = buildString("[tap](javascript:alert)")

        assertEquals("the link text must still be readable", "tap", text.text)
        assertTrue(
            "no annotation may be attached to a rejected scheme",
            text.getLinkAnnotations(0, text.length).isEmpty()
        )
    }

    @Test
    fun `a safe link keeps its annotation`() {
        val text = buildString("[tap](https://example.com)")

        assertEquals("tap", text.text)
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
    }
}

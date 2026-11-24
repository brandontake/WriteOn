package com.ezpnix.writeon.data.backup

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SYN-5: WebDAV URLs with non-ASCII characters or spaces must reach the wire as ASCII headers.
 *
 * OkHttp's header builder rejects values outside 0x20..0x7e, and RFC 4918 requires the
 * `Destination` URI to be percent-encoded. Before this the raw setting string was interpolated
 * into `Destination` and the tagged `If`, so a CJK or spaced path threw while the MOVE was built.
 */
class WebDavUrlEncodingTest {

    private val unicodeUrl = "https://host/我的笔记/note s.zip"

    @Test
    fun `the tagged If header is ASCII for a non-ASCII path with a space`() {
        val plan = WebDavPublish.destinationPreconditionHeaders(
            destinationUrl = unicodeUrl,
            ifMatch = "\"abc123\"",
            ifNoneMatch = false,
            ifUnmodifiedSince = null,
        )

        val ifHeader = plan.headers.getValue("If")
        assertAscii(ifHeader)
        assertTrue("the path must be percent-encoded: $ifHeader", ifHeader.contains("%E6%88%91"))
        assertTrue("the space must be encoded: $ifHeader", ifHeader.contains("note%20s.zip"))
        // The exact failure the audit describes: building the request used to throw.
        Request.Builder().url(WebDavPublish.encodedUrl(unicodeUrl)).header("If", ifHeader).build()
    }

    @Test
    fun `the Destination URL is ASCII and percent-encoded`() {
        val encoded = WebDavPublish.encodedUrl(unicodeUrl)

        assertAscii(encoded)
        assertEquals("https://host/%E6%88%91%E7%9A%84%E7%AC%94%E8%AE%B0/note%20s.zip", encoded)
        Request.Builder().url(encoded).header("Destination", encoded).build()
    }

    @Test
    fun `a Unicode URL is accepted by OkHttp as a header value only after encoding`() {
        // Guard against a test that would pass even if no encoding happened.
        try {
            Request.Builder().url("https://host/").header("Destination", unicodeUrl).build()
            fail("OkHttp was expected to reject a raw non-ASCII header value")
        } catch (expected: IllegalArgumentException) {
            // Expected: this is the crash the old MOVE path swallowed.
        }
    }

    @Test
    fun `staging names are encoded, keep the query string and stay ASCII`() {
        val staging = WebDavPublish.stagingUrl(
            canonicalUrl = "$unicodeUrl?token=abc%2Fdef",
            installId = "install-one",
        )

        assertAscii(staging)
        assertEquals(
            "https://host/%E6%88%91%E7%9A%84%E7%AC%94%E8%AE%B0/note%20s.zip.uploading-install-one?token=abc%2Fdef",
            staging,
        )
    }

    @Test
    fun `conflict-copy names are inserted before the extension without raw concatenation`() {
        val conflict = WebDavPublish.insertBeforeExtension(unicodeUrl, ".conflict-20261120-101500-123")

        assertAscii(conflict)
        assertEquals(
            "https://host/%E6%88%91%E7%9A%84%E7%AC%94%E8%AE%B0/note%20s.conflict-20261120-101500-123.zip",
            conflict,
        )
    }

    @Test
    fun `a folder URL is rejected with a typed folder error`() {
        try {
            WebDavPublish.requireFileUrl("https://host/我的笔记/")
            fail("a folder URL must not be accepted as a backup file")
        } catch (e: InvalidWebDavUrlException) {
            assertTrue(e.folder)
        }
    }

    @Test
    fun `an unparseable URL is rejected with a typed invalid error`() {
        try {
            WebDavPublish.requireFileUrl("not a url at all")
            fail("an invalid URL must not be accepted")
        } catch (e: InvalidWebDavUrlException) {
            assertFalse(e.folder)
        }
    }

    private fun assertAscii(value: String) {
        value.forEach { character ->
            assertTrue(
                "non-ASCII character U+${character.code.toString(16).uppercase()} in \"$value\"",
                character.code in 0x20..0x7e,
            )
        }
    }
}

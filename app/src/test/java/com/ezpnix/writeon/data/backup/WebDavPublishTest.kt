package com.ezpnix.writeon.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SYN-2 + SYN-4: the header and naming rules of the WebDAV publication.
 *
 * These are pure functions over [WebDavPublish], so no server or Android runtime is involved.
 * The bug they pin down is a spec detail that only a strict server shows: preconditions on a
 * `MOVE` are evaluated against the staging file (the request URI), not against `Destination`.
 * Sending `If-Match` there used to either fail every publish with 412 or leave the canonical
 * file unprotected, depending on how loose the server was.
 */
class WebDavPublishTest {

    private val destination = "https://dav.example/remote.php/dav/files/user/notes.zip"

    @Test
    fun `a known etag becomes a tagged If on the destination, never If-Match`() {
        val plan = WebDavPublish.destinationPreconditionHeaders(
            destinationUrl = destination,
            ifMatch = "\"abc123\"",
            ifNoneMatch = false,
            ifUnmodifiedSince = null,
        )

        assertEquals("T", plan.headers["Overwrite"])
        assertEquals("<$destination> ([\"abc123\"])", plan.headers["If"])
        assertFalse("If-Match on MOVE would guard the staging file", plan.headers.containsKey("If-Match"))
        assertFalse("If-None-Match belongs to the request URI, not Destination", plan.headers.containsKey("If-None-Match"))
        assertFalse(plan.directPut)
        assertTrue("a server that rejects If on MOVE may be retried as a direct PUT", plan.retryDirectPutOnReject)
    }

    @Test
    fun `a create sends Overwrite F and no If-None-Match`() {
        val plan = WebDavPublish.destinationPreconditionHeaders(
            destinationUrl = destination,
            ifMatch = null,
            ifNoneMatch = true,
            ifUnmodifiedSince = null,
        )

        assertEquals(mapOf("Overwrite" to "F"), plan.headers)
        assertFalse(plan.headers.containsKey("If-None-Match"))
        assertFalse(plan.directPut)
        assertTrue(plan.retryDirectPutOnReject)
    }

    @Test
    fun `last-modified-only has no MOVE form and chooses the direct conditional PUT`() {
        val plan = WebDavPublish.destinationPreconditionHeaders(
            destinationUrl = destination,
            ifMatch = null,
            ifNoneMatch = false,
            ifUnmodifiedSince = "Wed, 21 Oct 2015 07:28:00 GMT",
        )

        assertTrue(
            "If-Unmodified-Since on MOVE guards the staging file, so the canonical PUT must publish",
            plan.directPut,
        )
        assertTrue(plan.headers.isEmpty())
        assertFalse(plan.retryDirectPutOnReject)
    }

    @Test
    fun `no validator publishes unconditionally with Overwrite T`() {
        val plan = WebDavPublish.destinationPreconditionHeaders(
            destinationUrl = destination,
            ifMatch = null,
            ifNoneMatch = false,
            ifUnmodifiedSince = null,
        )

        assertEquals(mapOf("Overwrite" to "T"), plan.headers)
        assertFalse(plan.directPut)
        assertFalse(plan.retryDirectPutOnReject)
    }

    @Test
    fun `an unquoted etag is quoted and an already quoted one is left alone`() {
        assertEquals("\"abc123\"", WebDavPublish.quoteEtag("abc123"))
        assertEquals("\"abc123\"", WebDavPublish.quoteEtag("  \"abc123\"  "))

        val plan = WebDavPublish.destinationPreconditionHeaders(
            destinationUrl = destination,
            ifMatch = "abc123",
            ifNoneMatch = false,
            ifUnmodifiedSince = null,
        )
        assertEquals("<$destination> ([\"abc123\"])", plan.headers["If"])
    }

    @Test
    fun `staging names differ per install and keep the query string`() {
        val signed = "$destination?token=abc%2Fdef&expires=1700000000"

        val first = WebDavPublish.stagingUrl(signed, "install-one")
        val second = WebDavPublish.stagingUrl(signed, "install-two")

        assertEquals("$destination.uploading-install-one?token=abc%2Fdef&expires=1700000000", first)
        assertNotEquals(first, second)
        assertTrue("the query lives after the staging suffix", second.endsWith("?token=abc%2Fdef&expires=1700000000"))
        assertEquals("$destination.uploading-install-two?token=abc%2Fdef&expires=1700000000", second)
    }

    @Test
    fun `staging names without a query still get the install suffix`() {
        assertEquals(
            "$destination.uploading-install-one",
            WebDavPublish.stagingUrl(destination, "install-one"),
        )
    }
}

package com.ezpnix.writeon.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REG-11: the post-publication verdict.
 *
 * A `MOVE`/`PUT` that answers 2xx is only the server's acknowledgement. If the follow-up HEAD
 * shows a different ETag with a length that does not match the uploaded archive (or no file at
 * all), the run must be treated as a failed publication. These are the verdicts the old worker
 * could compute and then still report as a completed backup.
 */
class WebDavPublicationVerificationTest {

    private val lastModified = "Wed, 21 Oct 2015 07:28:00 GMT"

    @Test
    fun `a different etag with a shorter published file is unverified`() {
        val verdict = verify(
            head = RemoteArchiveState.Present(etag = "\"published\"", contentLength = 41),
            expectedEtag = "\"uploaded\"",
            localLength = 42,
        )

        assertFalse(verdict.verified)
        assertNull("an unverified publication must not remember a validator", verdict.storedEtag)
        assertNull(verdict.storedLastModified)
        assertEquals(WebDavPublicationVerification.Reason.ETAG_MISMATCH, verdict.reason)
    }

    @Test
    fun `a different etag with an unreadable length is unverified`() {
        val verdict = verify(
            head = RemoteArchiveState.Present(etag = "\"published\"", contentLength = null),
            expectedEtag = "\"uploaded\"",
            moveEtag = "\"move\"",
            localLength = 42,
        )

        assertFalse(verdict.verified)
        assertEquals(WebDavPublicationVerification.Reason.ETAG_MISMATCH, verdict.reason)
    }

    @Test
    fun `length alone catches a truncated file when no etag is available`() {
        val verdict = verify(
            head = RemoteArchiveState.Present(contentLength = 41),
            expectedEtag = null,
            localLength = 42,
        )

        assertFalse(verdict.verified)
        assertEquals(WebDavPublicationVerification.Reason.LENGTH_MISMATCH, verdict.reason)
    }

    @Test
    fun `an equal etag verifies and keeps the canonical validators`() {
        val verdict = verify(
            head = RemoteArchiveState.Present(
                etag = "\"uploaded\"",
                lastModified = lastModified,
                contentLength = 42,
            ),
            expectedEtag = "\"uploaded\"",
            localLength = 42,
        )

        assertTrue(verdict.verified)
        assertEquals("\"uploaded\"", verdict.storedEtag)
        assertEquals(lastModified, verdict.storedLastModified)
        assertEquals(WebDavPublicationVerification.Reason.ETAG_MATCH, verdict.reason)
    }

    @Test
    fun `a server minted etag over the same length still verifies when the move reported it`() {
        val verdict = verify(
            head = RemoteArchiveState.Present(etag = "\"minted\"", contentLength = 42),
            expectedEtag = "\"uploaded\"",
            moveEtag = "\"minted\"",
            localLength = 42,
        )

        assertTrue(verdict.verified)
        assertEquals(
            "the next run must condition on the tag the server actually reports",
            "\"minted\"",
            verdict.storedEtag,
        )
        assertEquals(WebDavPublicationVerification.Reason.SERVER_MINTED_ETAG, verdict.reason)
    }

    @Test
    fun `a different etag with the same length is unverified without the move's own tag`() {
        // R5-05: another device can write a same-size archive in the MOVE->HEAD window. Without a
        // MOVE response naming this tag, the file is indistinguishable from that write.
        val verdict = verify(
            head = RemoteArchiveState.Present(etag = "\"foreign\"", contentLength = 42),
            expectedEtag = "\"uploaded\"",
            localLength = 42,
        )

        assertFalse(verdict.verified)
        assertNull("an unproven file must not become this device's ownership", verdict.storedEtag)
        assertEquals(WebDavPublicationVerification.Reason.ETAG_MISMATCH, verdict.reason)
    }

    @Test
    fun `a present file with neither an etag nor a length is unverified`() {
        // R5-05: a bare 200 proves nothing about the bytes that are now under the canonical name.
        val verdict = verify(
            head = RemoteArchiveState.Present(),
            expectedEtag = "\"uploaded\"",
            localLength = 42,
        )

        assertFalse(verdict.verified)
        assertNull(verdict.storedEtag)
        assertNull(verdict.storedLastModified)
        assertEquals(WebDavPublicationVerification.Reason.NO_VALIDATOR_OR_LENGTH, verdict.reason)
    }

    @Test
    fun `a matching length without any etag verifies length only`() {
        val verdict = verify(
            head = RemoteArchiveState.Present(contentLength = 42),
            expectedEtag = null,
            localLength = 42,
        )

        assertTrue(verdict.verified)
        assertNull("length alone must not invent an ETag", verdict.storedEtag)
        assertEquals(WebDavPublicationVerification.Reason.LENGTH_ONLY_MATCH, verdict.reason)
    }

    @Test
    fun `a head etag without an upload etag is unverified`() {
        // R6-02: a PUT/MOVE that exposes no tag cannot prove the HEAD's tag is its own. Length
        // equality is not ownership; storing that tag would make the next run overwrite a foreign
        // same-size file.
        val verdict = verify(
            head = RemoteArchiveState.Present(etag = "\"foreign\"", contentLength = 42),
            expectedEtag = null,
            localLength = 42,
        )

        assertFalse(verdict.verified)
        assertNull(verdict.storedEtag)
        assertNull(verdict.storedLastModified)
        assertEquals(WebDavPublicationVerification.Reason.ETAG_MISMATCH, verdict.reason)
    }

    @Test
    fun `a missing canonical after the move is unverified`() {
        val verdict = verify(
            head = RemoteArchiveState.Missing,
            expectedEtag = "\"uploaded\"",
            localLength = 42,
        )

        assertFalse(verdict.verified)
        assertNull(verdict.storedEtag)
        assertEquals(WebDavPublicationVerification.Reason.MISSING_AFTER_MOVE, verdict.reason)
    }

    @Test
    fun `an unreadable post-write head keeps the upload validators`() {
        // SYN-1 must stay intact: a minimal server that cannot answer the post-MOVE HEAD still
        // described our bytes through the staging PUT / MOVE responses.
        val verdict = verify(
            head = RemoteArchiveState.Unknown("HEAD rejected with HTTP 405"),
            expectedEtag = "\"uploaded\"",
            moveEtag = "\"move\"",
            moveLastModified = lastModified,
            localLength = 42,
        )

        assertTrue(verdict.verified)
        assertEquals("\"move\"", verdict.storedEtag)
        assertEquals(lastModified, verdict.storedLastModified)
        assertEquals(
            WebDavPublicationVerification.Reason.HEAD_UNREADABLE_WITH_VALIDATORS,
            verdict.reason,
        )
    }

    @Test
    fun `an unreadable head without any validator is unverified`() {
        val verdict = verify(
            head = RemoteArchiveState.Unknown("HEAD failed: timeout"),
            expectedEtag = null,
            moveEtag = null,
            localLength = 42,
        )

        assertFalse(verdict.verified)
        assertNull(verdict.storedEtag)
        assertEquals(
            WebDavPublicationVerification.Reason.HEAD_UNREADABLE_WITHOUT_VALIDATORS,
            verdict.reason,
        )
    }

    private fun verify(
        head: RemoteArchiveState,
        expectedEtag: String?,
        localLength: Long,
        moveEtag: String? = null,
        moveLastModified: String? = null,
        uploadedLastModified: String? = null,
    ) = WebDavPublicationVerification.verify(
        head = head,
        expectedEtag = expectedEtag,
        moveEtag = moveEtag,
        moveLastModified = moveLastModified,
        uploadedLastModified = uploadedLastModified,
        localLength = localLength,
    )
}

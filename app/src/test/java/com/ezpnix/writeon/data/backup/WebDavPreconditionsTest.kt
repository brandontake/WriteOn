package com.ezpnix.writeon.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SYN-1: the publication decision. These are the cases the old `exists == true` guards let
 * through to an unconditional, overwriting `MOVE`.
 */
class WebDavPreconditionsTest {

    @Test
    fun `a missing remote creates with a no-replace precondition`() {
        val publication = decide(remote = RemoteArchiveState.Missing)

        assertEquals(CanonicalPublication.Create, publication)
    }

    @Test
    fun `a missing remote ignores a stale remembered validator`() {
        val publication = decide(
            remote = RemoteArchiveState.Missing,
            knownEtag = "\"old\"",
            knownLastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
        )

        assertEquals(CanonicalPublication.Create, publication)
    }

    @Test
    fun `a present file with an etag is overwritten conditionally`() {
        val publication = decide(
            remote = RemoteArchiveState.Present(etag = "\"remote\""),
        )

        assertEquals(
            CanonicalPublication.Conditional(ifMatch = "\"remote\"", ifUnmodifiedSince = null),
            publication,
        )
    }

    @Test
    fun `a remembered etag wins over the one just read`() {
        val publication = decide(
            remote = RemoteArchiveState.Present(etag = "\"remote\""),
            knownEtag = "\"ours\"",
        )

        assertEquals(
            CanonicalPublication.Conditional(ifMatch = "\"ours\"", ifUnmodifiedSince = null),
            publication,
        )
    }

    @Test
    fun `a last-modified-only server uses if-unmodified-since`() {
        val publication = decide(
            remote = RemoteArchiveState.Present(lastModified = "Wed, 21 Oct 2015 07:28:00 GMT"),
        )

        assertEquals(
            CanonicalPublication.Conditional(
                ifMatch = null,
                ifUnmodifiedSince = "Wed, 21 Oct 2015 07:28:00 GMT",
            ),
            publication,
        )
    }

    @Test
    fun `a present file without any validator is refused`() {
        val publication = decide(remote = RemoteArchiveState.Present())

        assertEquals(
            CanonicalPublication.Refused(CanonicalPublication.RefusalReason.NO_VALIDATOR),
            publication,
        )
    }

    @Test
    fun `an unreadable remote without a remembered validator is refused even after a previous upload`() {
        // The P1 shape: this device wrote the remote once (REMOTE_WRITTEN=true), the server
        // refuses HEAD and PROPFIND now, and nothing was remembered. This must not publish.
        val publication = decide(
            remote = RemoteArchiveState.Unknown("HEAD and PROPFIND failed"),
        )

        assertEquals(
            CanonicalPublication.Refused(CanonicalPublication.RefusalReason.REMOTE_UNVERIFIED),
            publication,
        )
    }

    @Test
    fun `an unreadable remote is refused even with a remembered etag`() {
        // The remembered value cannot be checked against the current file, so it is not proof of
        // anything: only the explicit takeover may write an unreadable remote.
        val publication = decide(
            remote = RemoteArchiveState.Unknown("HEAD failed: timeout"),
            knownEtag = "\"ours\"",
        )

        assertEquals(
            CanonicalPublication.Refused(CanonicalPublication.RefusalReason.REMOTE_UNVERIFIED),
            publication,
        )
    }

    @Test
    fun `only the explicit takeover may write an unverifiable remote`() {
        assertEquals(
            CanonicalPublication.ForcedOverwrite,
            decide(
                remote = RemoteArchiveState.Unknown("HEAD failed"),
                forceOverwrite = true,
            ),
        )
        // A remembered validator cannot be checked against the current file, and the user asked
        // for the takeover: the explicit flag, not a possibly stale ETag, is the authority here.
        assertEquals(
            CanonicalPublication.ForcedOverwrite,
            decide(
                remote = RemoteArchiveState.Unknown("HEAD failed"),
                knownEtag = "\"ours\"",
                forceOverwrite = true,
            ),
        )
    }

    @Test
    fun `a forced takeover of a missing file still creates instead of overwriting`() {
        assertEquals(
            CanonicalPublication.Create,
            decide(
                remote = RemoteArchiveState.Missing,
                knownEtag = "\"stale\"",
                forceOverwrite = true,
            ),
        )
    }

    @Test
    fun `a forced takeover still conditions on the current remote validator`() {
        assertEquals(
            CanonicalPublication.Conditional(ifMatch = "\"remote\"", ifUnmodifiedSince = null),
            decide(
                remote = RemoteArchiveState.Present(etag = "\"remote\""),
                knownEtag = "\"ours\"",
                forceOverwrite = true,
            ),
        )
    }

    @Test
    fun `a collection is a configuration error, never a file`() {
        val publication = decide(
            remote = RemoteArchiveState.Present(isCollection = true),
            forceOverwrite = true,
        )

        assertEquals(
            CanonicalPublication.Refused(CanonicalPublication.RefusalReason.COLLECTION_URL),
            publication,
        )
    }

    @Test
    fun `no automatic decision asks for an unconditional overwrite`() {
        val remotes = listOf(
            RemoteArchiveState.Missing,
            RemoteArchiveState.Present(),
            RemoteArchiveState.Present(etag = "\"a\""),
            RemoteArchiveState.Present(lastModified = "Wed, 21 Oct 2015 07:28:00 GMT"),
            RemoteArchiveState.Unknown("no answer"),
        )

        remotes.forEach { remote ->
            val publication = decide(remote = remote)
            assertTrue(
                "unexpected publication for $remote: $publication",
                publication != CanonicalPublication.ForcedOverwrite,
            )
        }
    }

    private fun decide(
        remote: RemoteArchiveState,
        knownEtag: String? = null,
        knownLastModified: String? = null,
        forceOverwrite: Boolean = false,
    ) = WebDavPreconditions.decide(
        remote = remote,
        knownEtag = knownEtag,
        knownLastModified = knownLastModified,
        forceOverwrite = forceOverwrite,
    )
}

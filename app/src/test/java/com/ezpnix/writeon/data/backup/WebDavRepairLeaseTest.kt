package com.ezpnix.writeon.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-01: the repair lease only ever names the exact remote state a failed publication left.
 *
 * Treating that file as a stranger parked a conflict copy on every retry and never rebuilt the
 * canonical archive; treating every file as ours would overwrite another device's backup.
 */
class WebDavRepairLeaseTest {

    @Test
    fun `a remote with the failed publication's etag is repairable`() {
        assertTrue(
            WebDavRepairLease.matches(
                remote = RemoteArchiveState.Present(etag = "\"truncated\""),
                repairEtag = "\"truncated\"",
                repairLastModified = "Wed, 21 Oct 2026 07:28:00 GMT",
            )
        )
    }

    @Test
    fun `a remote that changed since the failed publication is not repairable`() {
        assertFalse(
            WebDavRepairLease.matches(
                remote = RemoteArchiveState.Present(etag = "\"another-device\""),
                repairEtag = "\"truncated\"",
                repairLastModified = null,
            )
        )
        // A same-second write keeps Last-Modified but changes the entity tag; the strong validator
        // wins, so the other device's file is never treated as this install's broken upload.
        assertFalse(
            WebDavRepairLease.matches(
                remote = RemoteArchiveState.Present(
                    etag = "\"another-device\"",
                    lastModified = "Wed, 21 Oct 2026 07:28:00 GMT",
                ),
                repairEtag = "\"truncated\"",
                repairLastModified = "Wed, 21 Oct 2026 07:28:00 GMT",
            )
        )
    }

    @Test
    fun `a last-modified-only server is matched on the timestamp`() {
        assertTrue(
            WebDavRepairLease.matches(
                remote = RemoteArchiveState.Present(lastModified = "Wed, 21 Oct 2026 07:28:00 GMT"),
                repairEtag = null,
                repairLastModified = "Wed, 21 Oct 2026 07:28:00 GMT",
            )
        )
        assertFalse(
            WebDavRepairLease.matches(
                remote = RemoteArchiveState.Present(lastModified = "Thu, 22 Oct 2026 07:28:00 GMT"),
                repairEtag = null,
                repairLastModified = "Wed, 21 Oct 2026 07:28:00 GMT",
            )
        )
    }

    @Test
    fun `missing, unknown and folder remotes never match a lease`() {
        assertFalse(WebDavRepairLease.matches(RemoteArchiveState.Missing, "\"x\"", null))
        assertFalse(
            WebDavRepairLease.matches(
                RemoteArchiveState.Unknown("HEAD failed"),
                "\"x\"",
                "Wed, 21 Oct 2026 07:28:00 GMT"
            )
        )
        assertFalse(
            WebDavRepairLease.matches(
                RemoteArchiveState.Present(etag = "\"x\"", isCollection = true),
                "\"x\"",
                null
            )
        )
    }

    @Test
    fun `no lease means no repair`() {
        assertFalse(
            WebDavRepairLease.matches(
                remote = RemoteArchiveState.Present(etag = "\"x\""),
                repairEtag = null,
                repairLastModified = null,
            )
        )
    }

    @Test
    fun `a lease is only created for a shorter remote whose etag this publication produced`() {
        assertTrue(
            WebDavRepairLease.shouldEstablish(
                remote = RemoteArchiveState.Present(etag = "\"uploaded\"", contentLength = 41),
                uploadEtag = "\"uploaded\"",
                moveEtag = null,
                localLength = 42,
            )
        )
        // SYN-2: many servers mint a new ETag on MOVE. That tag is still ours when the MOVE
        // response returned it, so a truncated destination written by this MOVE is repairable.
        assertTrue(
            WebDavRepairLease.shouldEstablish(
                remote = RemoteArchiveState.Present(etag = "\"moved\"", contentLength = 41),
                uploadEtag = "\"uploaded\"",
                moveEtag = "\"moved\"",
                localLength = 42,
            )
        )
        // R4-01: another device can write a shorter archive between our MOVE and our HEAD. Its
        // ETag is not one of ours, so no lease may name it - the conflict-copy rule has to win.
        assertFalse(
            WebDavRepairLease.shouldEstablish(
                remote = RemoteArchiveState.Present(etag = "\"other-device\"", contentLength = 41),
                uploadEtag = "\"uploaded\"",
                moveEtag = null,
                localLength = 42,
            )
        )
        // R3-02: a longer or same-size remote is not explained by a dropped tail.
        assertFalse(
            WebDavRepairLease.shouldEstablish(
                remote = RemoteArchiveState.Present(etag = "\"uploaded\"", contentLength = 200),
                uploadEtag = "\"uploaded\"",
                moveEtag = null,
                localLength = 42,
            )
        )
        // No strong validator on our own upload: a Last-Modified-only server never gets an
        // automatic overwrite based on a lease.
        assertFalse(
            WebDavRepairLease.shouldEstablish(
                remote = RemoteArchiveState.Present(etag = "\"uploaded\"", contentLength = 41),
                uploadEtag = null,
                moveEtag = null,
                localLength = 42,
            )
        )
        assertFalse(
            WebDavRepairLease.shouldEstablish(
                remote = RemoteArchiveState.Present(etag = "\"uploaded\"", contentLength = 41),
                uploadEtag = "W/\"weak\"",
                moveEtag = null,
                localLength = 42,
            )
        )
        // No readable remote length means no evidence of truncation.
        assertFalse(
            WebDavRepairLease.shouldEstablish(
                remote = RemoteArchiveState.Present(etag = "\"uploaded\""),
                uploadEtag = "\"uploaded\"",
                moveEtag = null,
                localLength = 42,
            )
        )
    }

    @Test
    fun `attempts stay monotonic per url even when every write mints a new etag`() {
        val url = "https://dav.example.com/backup.zip"
        // Three failed publications, each with a different ETag: the budget must still climb.
        var attempts = WebDavRepairLease.nextAttempts(null, url, 0)
        assertTrue(attempts == 1)
        assertTrue(
            WebDavRepairLease.shouldEstablish(
                RemoteArchiveState.Present(etag = "\"e1\"", contentLength = 41),
                "\"e1\"",
                null,
                42,
            )
        )
        attempts = WebDavRepairLease.nextAttempts(url, url, attempts)
        assertTrue(attempts == 2)
        attempts = WebDavRepairLease.nextAttempts(url, url, attempts)
        assertTrue(attempts == 3)
        // The fourth run sees the exhausted budget and keeps a conflict copy instead of repairing.
        assertFalse(WebDavRepairLease.canRepair(leaseMatches = true, attempts = attempts))
        // A lease written for another URL starts a fresh budget; saving a new server must not
        // inherit the old one's used attempts.
        assertTrue(WebDavRepairLease.nextAttempts("https://old.example.com/a.zip", url, 3) == 1)
        assertTrue(WebDavRepairLease.nextAttempts(null, url, 3) == 1)
    }

    @Test
    fun `an exhausted attempt budget disables a matching lease`() {
        // R5-06: the worker's cap branch is this predicate, so it is pinned without WorkManager.
        assertTrue(WebDavRepairLease.canRepair(leaseMatches = true, attempts = 0))
        assertTrue(
            WebDavRepairLease.canRepair(
                leaseMatches = true,
                attempts = WebDavRepairLease.MAX_REPAIR_ATTEMPTS - 1,
            )
        )
        assertFalse(
            WebDavRepairLease.canRepair(
                leaseMatches = true,
                attempts = WebDavRepairLease.MAX_REPAIR_ATTEMPTS,
            )
        )
        assertFalse(WebDavRepairLease.canRepair(leaseMatches = false, attempts = 0))
    }
}

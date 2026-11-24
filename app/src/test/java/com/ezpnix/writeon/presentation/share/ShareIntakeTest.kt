package com.ezpnix.writeon.presentation.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SEC-04: the share gate is consulted before anything is copied.
 *
 * The limiter used to run inside [ShareIntake.offer], i.e. after [readShareIntent] had already
 * imported every `EXTRA_STREAM`, so a rejected `ACTION_SEND_MULTIPLE` still wrote its files and
 * left them as orphans. These tests pin the two halves of the fix: the clock/window policy of
 * [ShareRateLimiter] and the import ordering in [readShareIntent] (the importer is a seam, so the
 * "nothing was copied" assertion does not depend on the file system).
 *
 * No device test here: the actual stream copy and its encryption need a real Keystore.
 */
@RunWith(RobolectricTestRunner::class)
class ShareIntakeTest {

    private lateinit var context: Context

    /** Start well past 0 so the limiter's "nothing accepted yet" sentinel behaves like on device. */
    private var now = 1_000_000L

    private fun limiter(
        minIntervalMs: Long = 1_000L,
        windowMs: Long = 60_000L,
        maxPerWindow: Int = 3,
    ): ShareRateLimiter = ShareRateLimiter(minIntervalMs, windowMs, maxPerWindow) { now }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // -----------------------------------------------------------------------------------------
    // ShareRateLimiter policy
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the first share is accepted`() {
        assertTrue(limiter().tryAcquire())
    }

    @Test
    fun `a share inside the minimum interval is rejected`() {
        val limiter = limiter()
        assertTrue(limiter.tryAcquire())
        now += 999L
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `a share after the minimum interval is accepted`() {
        val limiter = limiter()
        assertTrue(limiter.tryAcquire())
        now += 1_000L
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `the window resets after it elapses`() {
        val limiter = limiter()
        val windowStart = now
        assertTrue(limiter.tryAcquire())
        now += 1_000L
        assertTrue(limiter.tryAcquire())
        now += 1_000L
        assertTrue(limiter.tryAcquire())
        // Three per window is the cap; a fourth inside the same window is dropped.
        now += 1_000L
        assertFalse(limiter.tryAcquire())

        now = windowStart + 60_000L
        assertTrue(limiter.tryAcquire())
    }

    // -----------------------------------------------------------------------------------------
    // Ordering: a rejected share never reaches the importer
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a rate limited share does not reach the importer`() {
        val limiter = limiter()
        var imports = 0
        val importer: (Context, Uri, Long) -> ImportedImage? = { _, _, _ ->
            imports++
            ImportedImage("/tmp/shared-$imports.jpg", 4L)
        }
        val intent = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://test/share/1"))

        val accepted = readShareIntent(context, intent, limiter, importer)
        assertNotNull(accepted)

        now += 10L
        val rejected = readShareIntent(context, intent, limiter, importer)
        assertNull("a rate limited share must be dropped entirely", rejected)
        assertEquals("only the accepted share may import", 1, imports)
    }

    @Test
    fun `the stream count of one intent is capped`() {
        val limiter = limiter()
        var imports = 0
        val importer: (Context, Uri, Long) -> ImportedImage? = { _, _, _ ->
            imports++
            ImportedImage("/tmp/shared-$imports.jpg", 1L)
        }
        val streams = (1..SHARE_MAX_STREAMS + 5).map { Uri.parse("content://test/share/$it") }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(streams))

        val payload = readShareIntent(context, intent, limiter, importer)

        assertEquals(SHARE_MAX_STREAMS, payload?.imagePaths?.size)
        assertEquals(SHARE_MAX_STREAMS, imports)
    }

    @Test
    fun `the total bytes of one intent are capped`() {
        val limiter = limiter()
        var imports = 0
        // Each fake file is slightly more than half the budget, so the budget is exhausted by the
        // second copy and the third stream is never handed to the importer.
        val perFile = SHARE_MAX_TOTAL_BYTES / 2 + 1
        val importer: (Context, Uri, Long) -> ImportedImage? = { _, _, _ ->
            imports++
            ImportedImage("/tmp/shared-$imports.jpg", perFile)
        }
        val streams = (1..4).map { Uri.parse("content://test/share/$it") }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(streams))

        readShareIntent(context, intent, limiter, importer)

        assertEquals(2, imports)
    }

    @Test
    fun `a payload with neither text nor streams is ignored`() {
        val limiter = limiter()
        var imports = 0
        val importer: (Context, Uri, Long) -> ImportedImage? = { _, _, _ ->
            imports++
            null
        }
        val intent = Intent(Intent.ACTION_SEND)

        assertNull(readShareIntent(context, intent, limiter, importer))
        // A no-op intent must not even spend the rate-limit token.
        assertEquals(0, imports)
        assertTrue("the token is still available for a real share", limiter.tryAcquire())
    }
}

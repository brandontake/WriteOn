package com.ezpnix.writeon.presentation.screens.settings

import com.ezpnix.writeon.data.backup.WebDavRetry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W9 + W11: the classification both WebDAV workers now obey.
 *
 * Before this, the restore worker retried *every* non-2xx and the backup worker retried every 5xx
 * with no ceiling, so a wrong URL, a revoked app password or a deleted collection kept a unique
 * work chain alive forever and blocked every later backup behind it. These cases pin down which
 * outcomes may ask WorkManager for another run.
 */
class WebDavRetryTest {

    @Test
    fun `a transport failure before any response is retryable`() {
        assertTrue(WebDavRetry.isRetryable(WebDavRetry.NETWORK_FAILURE))
        assertTrue(WebDavRetry.shouldRetry(WebDavRetry.NETWORK_FAILURE, 0))
    }

    @Test
    fun `throttling and transient server errors are retryable`() {
        listOf(408, 425, 429, 500, 502, 503, 504).forEach { status ->
            assertTrue("HTTP $status must be retryable", WebDavRetry.isRetryable(status))
            assertTrue("HTTP $status must be retryable", WebDavRetry.shouldRetry(status, 0))
        }
    }

    @Test
    fun `credentials, missing resources and rejected payloads are terminal`() {
        // 401/403: the stored password is wrong or lacks permission. 404: the file or its parent
        // collection is gone. 409/423: the server refuses this update. 412 is the conflict path and
        // is handled by keepConflictCopy, never by a retry. 507: the server is out of space.
        listOf(400, 401, 403, 404, 405, 409, 412, 413, 423, 507).forEach { status ->
            assertFalse("HTTP $status must not be retried", WebDavRetry.isRetryable(status))
            assertFalse("HTTP $status must not be retried", WebDavRetry.shouldRetry(status, 0))
        }
    }

    @Test
    fun `redirects are recognised and never retried`() {
        listOf(301, 302, 303, 307, 308).forEach { status ->
            assertTrue("HTTP $status is a redirect", WebDavRetry.isRedirect(status))
            assertFalse("HTTP $status must not be retried", WebDavRetry.isRetryable(status))
        }
    }

    @Test
    fun `retrying stops at the attempt ceiling`() {
        assertTrue(WebDavRetry.shouldRetry(503, 0))
        assertTrue(WebDavRetry.shouldRetry(503, WebDavRetry.MAX_RUN_ATTEMPTS - 1))
        assertFalse(WebDavRetry.shouldRetry(503, WebDavRetry.MAX_RUN_ATTEMPTS))
        assertFalse(WebDavRetry.shouldRetry(503, WebDavRetry.MAX_RUN_ATTEMPTS + 3))
    }

    @Test
    fun `a successful status is neither a failure nor a redirect`() {
        (200..299).forEach { status ->
            assertFalse("HTTP $status is a success", WebDavRetry.isRetryable(status))
            assertFalse("HTTP $status is a success", WebDavRetry.isRedirect(status))
        }
    }
}

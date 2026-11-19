package com.ezpnix.writeon.data.backup

/**
 * W9: decides whether a WebDAV outcome is worth another WorkManager run.
 *
 * Both workers used to treat every failure the same: the restore worker retried *any* non-2xx
 * response (so a 401/403/404 or an archive with the wrong passphrase spun forever), and the
 * backup worker retried every 5xx without a ceiling. WorkManager does not cap attempts on its
 * own either, so a permanently broken configuration could keep the unique work chain alive
 * indefinitely - and because the chain is shared, later backup/restore requests were appended
 * behind a run that could never succeed.
 *
 * The classification follows RFC 9110: 408/425/429 and the gateway/server errors below describe
 * a condition that may clear on its own. Every other 4xx describes a request the same bytes will
 * never satisfy (bad URL, bad credentials, missing parent collection, payload rejected), so it is
 * terminal and must be shown to the user. Status 0 is this app's marker for "the call threw
 * IOException before any response arrived" - a network failure, which is retryable. Redirects are
 * never retryable (W11): repeating the same request against the same URL produces the same
 * redirect, and following it is exactly what turned a PUT into a GET.
 */
internal object WebDavRetry {

    /**
     * WorkManager's `runAttemptCount` is zero-based (0 on the first run). Once it reaches this
     * value the caller must fail terminally instead of asking for another run, so the user sees
     * that the backup/restore did not happen rather than an endless chain.
     */
    const val MAX_RUN_ATTEMPTS = 5

    /** Marker used by both workers when the call threw before an HTTP response existed. */
    const val NETWORK_FAILURE = 0

    private val RETRYABLE_STATUS = setOf(408, 425, 429, 500, 502, 503, 504)

    /** True when the same request may succeed later without any change on this device. */
    fun isRetryable(statusCode: Int): Boolean =
        statusCode == NETWORK_FAILURE || statusCode in RETRYABLE_STATUS

    /** True when the worker should ask WorkManager for another run. */
    fun shouldRetry(statusCode: Int, runAttemptCount: Int): Boolean =
        isRetryable(statusCode) && runAttemptCount < MAX_RUN_ATTEMPTS

    /** 3xx: the request reached a proxy or a moved resource instead of the configured file. */
    fun isRedirect(statusCode: Int): Boolean = statusCode in 300..399
}

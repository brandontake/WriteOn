package com.ezpnix.writeon.data.backup

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * W10 + W11: the one HTTP configuration every WebDAV call goes through.
 *
 * The workers used to build a bare `OkHttpClient()` per run - two of them in the restore worker
 * alone, plus one in the settings screen's manifest preview. A bare client means:
 *
 *  - **no `callTimeout`**: OkHttp's connect/read/write timeouts are per-socket-operation, so a
 *    server that drips one byte every 30 seconds keeps a `doWork()` call (and its WorkManager
 *    slot, and the unique work chain behind it) alive indefinitely. A call that hangs is
 *    indistinguishable from a slow network, and only a hard ceiling on the whole call ends it.
 *  - **redirects followed by default**: HTTP says a 302 in response to a `PUT` may be replayed as
 *    a `GET`. OkHttp does exactly that, so a proxy or a moved collection turned an upload into a
 *    download that came back `200 OK` with a body - and the worker, which only checked
 *    `code in 200..299`, recorded a successful backup that had uploaded nothing. Redirects are
 *    disabled here; [WebDavRetry.isRedirect] turns the 3xx into a terminal, visible failure.
 *  - **no bound on a download**: the restore path streamed `response.body` straight into a cache
 *    file with no ceiling, so a server (or a captive portal) answering with something enormous
 *    filled the device's storage before the integrity check ever ran. [copyWithLimit] caps it,
 *    and [ensureFreeSpace] refuses a download there is no room for *before* it starts.
 *
 * One shared instance is deliberate: each `OkHttpClient()` allocates its own connection pool and
 * dispatcher threads, so a backup/restore pair left a set of idle threads behind on every run.
 */
internal object WebDavHttp {

    /** How long a TCP connect may take. */
    const val CONNECT_TIMEOUT_SECONDS = 30L

    /** How long the socket may stall while sending a body (multi-megabyte archives on slow uplinks). */
    const val WRITE_TIMEOUT_SECONDS = 180L

    /** How long the socket may stall while waiting for the next byte of a response. */
    const val READ_TIMEOUT_SECONDS = 60L

    /**
     * Hard ceiling for a *metadata* call (HEAD/PROPFIND/MOVE/DELETE), including a small body.
     *
     * Transfers do not use this value: a note database plus every recording can be hundreds of
     * megabytes, and 200 MB on a 1.5 Mbps uplink already takes ~18 minutes - the old shared
     * 300-second cap cut every attempt of such a backup (SYN-7). [clientForTransfer] scales the
     * ceiling from the archive size instead.
     */
    const val CALL_TIMEOUT_SECONDS = 300L

    /**
     * SYN-7: how long a transfer is allowed to take when its size is known.
     *
     * The budget is [TRANSFER_BASE_SECONDS] plus the time the bytes need at
     * [MIN_TRANSFER_BYTES_PER_SECOND], a deliberately pessimistic floor (~256 kbps). A call that
     * is merely slow therefore finishes, while a server that stops making progress is still
     * caught by the read/write timeouts.
     */
    const val TRANSFER_BASE_SECONDS = 120L

    /** SYN-7: the pessimistic throughput a transfer budget is computed from (~256 kbps). */
    const val MIN_TRANSFER_BYTES_PER_SECOND = 32L * 1024L

    /**
     * Refuse to download an archive larger than this.
     *
     * A note database plus every recording is measured in tens of megabytes for a heavy user; a
     * gigabyte is far past anything this app can legitimately produce and is only reachable when
     * the URL does not point at a WriteOn archive at all.
     */
    const val MAX_ARCHIVE_BYTES = 1_073_741_824L

    /** Space that must stay free after a download lands, so the restore itself can still run. */
    const val DISK_HEADROOM_BYTES = 64L * 1024 * 1024

    @Volatile
    private var sharedClient: OkHttpClient? = null

    /** The process-wide client, built once. */
    fun client(): OkHttpClient = sharedClient ?: synchronized(this) {
        sharedClient ?: buildClient().also { sharedClient = it }
    }

    /**
     * SYN-7: a client whose whole-call ceiling is scaled to the transfer.
     *
     * [expectedBytes] null means "the size is not known before the response headers arrive" (the
     * restore download): the call then has no whole-call ceiling at all, because any fixed value
     * either truncates a legitimate large archive or is meaningless. Connect/read/write timeouts
     * stay in force, so a stalled socket is still detected. The clone shares the connection pool
     * and dispatcher of [client].
     */
    fun clientForTransfer(expectedBytes: Long?): OkHttpClient {
        val base = client()
        return if (expectedBytes == null) {
            base.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
        } else {
            base.newBuilder()
                .callTimeout(WebDavTransferTimeouts.secondsFor(expectedBytes), TimeUnit.SECONDS)
                .build()
        }
    }

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // W11: a redirect must be reported, never silently converted into a GET.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Throws unless [directory] can hold [expectedBytes] and still leave [DISK_HEADROOM_BYTES].
     *
     * `usableSpace` reports 0 on some volumes and can go negative; both mean "do not know", and
     * refusing a restore on an unknowable volume would be worse than attempting it, so only a
     * positive reading that is genuinely too small aborts.
     */
    fun ensureFreeSpace(directory: File, expectedBytes: Long) {
        val required = expectedBytes.coerceAtLeast(0L) + DISK_HEADROOM_BYTES
        val usable = directory.usableSpace
        if (usable in 1 until required) {
            throw IOException(
                "Not enough free space in ${directory.name}: need $required bytes, have $usable"
            )
        }
    }

    /**
     * Streams [input] into [output], refusing a body larger than [limit] (W10).
     *
     * Returns the number of bytes copied so the caller can compare it against a declared length.
     * The destination is left as it is when the limit is hit; callers write to a cache file they
     * delete on failure.
     */
    fun copyWithLimit(
        input: InputStream,
        output: OutputStream,
        limit: Long = MAX_ARCHIVE_BYTES
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw ArchiveTooLargeException(limit)
            output.write(buffer, 0, read)
        }
        return total
    }

    /** Thrown when a response body is bigger than [MAX_ARCHIVE_BYTES]. */
    class ArchiveTooLargeException(limit: Long) :
        IOException("Remote archive is larger than the $limit byte limit")
}

/**
 * SYN-7: the arithmetic behind the transfer budget, kept pure so it can be tested without a
 * server or a socket.
 *
 * The old fixed 300-second call timeout was shorter than the transfer time of any archive with
 * real media on a mobile uplink. Scaling from a pessimistic minimum rate keeps the ceiling useful
 * (a stuck transfer still ends) without rejecting legitimate slow ones.
 */
internal object WebDavTransferTimeouts {

    /** Whole-call ceiling in seconds for a body of [bytes]: base + bytes at the minimum rate. */
    fun secondsFor(bytes: Long): Long {
        val length = bytes.coerceAtLeast(0L)
        val rate = WebDavHttp.MIN_TRANSFER_BYTES_PER_SECOND
        val transferSeconds = (length + rate - 1) / rate
        return WebDavHttp.TRANSFER_BASE_SECONDS + transferSeconds
    }
}

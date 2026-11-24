package com.ezpnix.writeon.presentation.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.ezpnix.writeon.core.attachment.AttachmentFiles
import com.ezpnix.writeon.core.attachment.MediaVault
import com.ezpnix.writeon.domain.model.Attachment
import java.io.File
import java.io.IOException

/**
 * What another app handed us: some text, some pictures, or both.
 *
 * Images arrive as content:// URIs whose read grant dies with the sending app's process, so
 * [readShareIntent] copies them into our own files dir first and only the local paths are kept.
 */
data class SharePayload(
    val text: String = "",
    val imagePaths: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = text.isBlank() && imagePaths.isEmpty()
}

/**
 * Hand-off point between the receiving activity and the editor.
 *
 * A share can arrive before the editor exists (cold start) or while the app is elsewhere in its
 * back stack (warm start), and the editor itself can be recreated by the system, so the payload
 * cannot ride on an Intent extra: it is parked here and consumed exactly once, when a new note is
 * opened. [navigationRequest] is bumped only for the warm case - a cold start already lands on the
 * editor, and navigating again there would leave two editors on the stack.
 */
object ShareIntake {

    var pending: SharePayload? = null
        private set

    var navigationRequest by mutableIntStateOf(0)
        private set

    /**
     * SEC-04: checked by [readShareIntent] *before* the first byte is copied, not by [offer].
     *
     * A5: any installed app can fire ACTION_SEND at WriteOn without user interaction. A
     * confirmation screen for every share would tax the ordinary one-share flow, so the abuse case
     * is throttled instead: one accepted share per [SHARE_MIN_INTERVAL_MS], and at most
     * [SHARE_MAX_PER_WINDOW] per minute. A person sharing two articles in a row still fits; a
     * background app trying to inject hundreds of notes gets dropped with a log line and no
     * visible editor. The payload is never logged.
     *
     * It used to be consulted after every `EXTRA_STREAM` had already been imported, so a rejected
     * share still filled the private partition and left orphans nothing referenced. Reserving
     * first means a rejected share never writes at all.
     */
    internal val rateLimiter = ShareRateLimiter()

    fun offer(payload: SharePayload, navigate: Boolean) {
        if (payload.isEmpty) return
        // SEC-04: the token was already taken by readShareIntent before it wrote anything to disk;
        // charging here as well would rate-limit every legitimate share twice.
        pending = payload
        if (navigate) navigationRequest++
    }

    fun consume(): SharePayload? {
        val payload = pending
        pending = null
        return payload
    }
}

/**
 * SEC-04: the "one share per [minIntervalMs], at most [maxPerWindow] per [windowMs]" gate.
 *
 * Clock-parameterized so the window/interval policy is testable on the JVM without a device. It
 * only answers "may this intent proceed"; the caller owns the payload, the copy budget and the
 * cleanup of anything it wrote before a later step failed.
 */
internal class ShareRateLimiter(
    private val minIntervalMs: Long = SHARE_MIN_INTERVAL_MS,
    private val windowMs: Long = SHARE_WINDOW_MS,
    private val maxPerWindow: Int = SHARE_MAX_PER_WINDOW,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var lastAcceptedAt = 0L
    private var windowStartedAt = 0L
    private var acceptedInWindow = 0

    /**
     * Reserves one share slot, or refuses it.
     *
     * Synchronized because the read now happens on the IO dispatcher and a cold start plus a warm
     * intent can overlap; without it two calls could both pass the interval check.
     */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        if (now - windowStartedAt >= windowMs) {
            windowStartedAt = now
            acceptedInWindow = 0
        }
        if (acceptedInWindow >= maxPerWindow) return false
        // 0 is the "nothing accepted yet" sentinel; a device that boots at elapsed 0 therefore
        // never has its first share delayed by the interval.
        if (lastAcceptedAt != 0L && now - lastAcceptedAt < minIntervalMs) return false
        lastAcceptedAt = now
        acceptedInWindow++
        return true
    }
}

/**
 * Reads ACTION_SEND / ACTION_SEND_MULTIPLE, or returns null when this is an ordinary launch.
 * Only the first line of a shared text becomes the note name; the whole text stays in the body.
 *
 * SEC-04: the rate-limit reservation and the size/stream caps are applied **before** the first
 * [importImage] call, and anything already copied is deleted when the payload cannot be handed to
 * the editor. A rejected or oversized share therefore leaves the private storage exactly as it
 * was. [limiter] and [importer] are seams for the JVM tests; production always uses the defaults.
 */
internal fun readShareIntent(
    context: Context,
    intent: Intent?,
    limiter: ShareRateLimiter = ShareIntake.rateLimiter,
    importer: (Context, Uri, Long) -> ImportedImage? = { target, uri, maxBytes ->
        importImage(target, uri, maxBytes)
    },
): SharePayload? {
    if (intent == null) return null
    val multiple = intent.action == Intent.ACTION_SEND_MULTIPLE
    if (!multiple && intent.action != Intent.ACTION_SEND) return null

    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().trim()
    @Suppress("DEPRECATION")
    val streams: List<Uri> = if (multiple) {
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    } else {
        listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }

    if (text.isEmpty() && streams.isEmpty()) return null
    // SEC-04: reserve before the first copy. A rejected share must not import anything, not even
    // the first stream of an ACTION_SEND_MULTIPLE.
    if (!limiter.tryAcquire()) {
        Log.w(TAG, "Dropped an ACTION_SEND payload: share rate limit exceeded")
        return null
    }

    val imported = ArrayList<ImportedImage>()
    var remainingBytes = SHARE_MAX_TOTAL_BYTES
    try {
        for (uri in streams.take(SHARE_MAX_STREAMS)) {
            if (remainingBytes <= 0L) break
            val file = importer(
                context,
                uri,
                minOf(SHARE_MAX_FILE_BYTES, remainingBytes),
            ) ?: continue
            imported += file
            remainingBytes -= file.bytes
        }
    } catch (throwable: Throwable) {
        // A later stream failed after earlier ones had landed; leaving those copies would keep
        // files no note references. The text half (if any) is still worth keeping.
        Log.e(TAG, "Could not import a shared file", throwable)
        deleteImported(imported)
        imported.clear()
    }

    val payload = SharePayload(text = text, imagePaths = imported.map { it.path })
    if (payload.isEmpty) {
        deleteImported(imported)
        return null
    }
    return payload
}

/** SEC-04: the path and the bytes copied, so the caller can enforce the per-intent budget. */
internal data class ImportedImage(val path: String, val bytes: Long)

private fun deleteImported(files: Collection<ImportedImage>) {
    files.forEach { imported -> runCatching { File(imported.path).delete() } }
}

private fun importImage(context: Context, uri: Uri, maxBytes: Long): ImportedImage? {
    // Shared pictures are user content: keep them in internal storage, not the app-specific
    // external directory that any file manager (or MTP) can read.
    val directory = AttachmentFiles.directoryFor(context, Attachment.TYPE_IMAGE)
    if (!directory.exists() && !directory.mkdirs()) return null

    // The display name decides the extension, so a shared PNG stays a PNG instead of being
    // renamed to .jpg while still holding PNG bytes (which coil would then fail to decode).
    val extension = displayName(context, uri)
        ?.substringAfterLast('.', "")
        ?.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
        ?: "jpg"
    val file = File(directory, "shared-${System.currentTimeMillis()}-${uri.hashCode() and 0xFFFF}.$extension")

    return try {
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                // A share is one file out of the sender's app; the cap only trips on something no
                // note should carry (a video picked as an image, a corrupt length). Without it a
                // malicious or mistaken sender could fill the private partition.
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > maxBytes) {
                        throw IOException("shared image exceeds $maxBytes bytes")
                    }
                    output.write(buffer, 0, read)
                }
                copied
            }
        } ?: return null

        // SEC-4: the copy is complete, so it is encrypted before its path is ever handed to the
        // editor. A share does not go through the save-with-retry an editor save does: if the bytes
        // cannot be encrypted the plaintext copy is deleted and the import fails, because a file
        // that was never referenced by a row is not worth a permanent plaintext picture.
        if (!MediaVault.encryptInPlace(context, file)) {
            Log.e(TAG, "Could not encrypt a shared image; dropping the plaintext copy")
            runCatching { file.delete() }
            return null
        }
        ImportedImage(file.absolutePath, copied)
    } catch (throwable: Throwable) {
        Log.e(TAG, "Could not import a shared image", throwable)
        // A partial file would linger and render as a broken attachment.
        runCatching { file.delete() }
        null
    }
}

/** 100 MB per file: far above any photo, far below "one share fills the disk". */
internal const val SHARE_MAX_FILE_BYTES = 100L * 1024 * 1024

/** SEC-04: `ACTION_SEND_MULTIPLE` has no limit of its own; ten files is already a large share. */
internal const val SHARE_MAX_STREAMS = 10

/** SEC-04: total bytes one intent may copy, so ten files cannot sum to a full partition. */
internal const val SHARE_MAX_TOTAL_BYTES = 200L * 1024 * 1024

private const val SHARE_MIN_INTERVAL_MS = 1_500L
private const val SHARE_WINDOW_MS = 60_000L
private const val SHARE_MAX_PER_WINDOW = 5
private const val TAG = "ShareIntake"

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

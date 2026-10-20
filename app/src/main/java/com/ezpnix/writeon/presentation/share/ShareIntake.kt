package com.ezpnix.writeon.presentation.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File

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

    fun offer(payload: SharePayload, navigate: Boolean) {
        if (payload.isEmpty) return
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
 * Reads ACTION_SEND / ACTION_SEND_MULTIPLE, or returns null when this is an ordinary launch.
 * Only the first line of a shared text becomes the note name; the whole text stays in the body.
 */
fun readShareIntent(context: Context, intent: Intent?): SharePayload? {
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

    val payload = SharePayload(
        text = text,
        imagePaths = streams.mapNotNull { uri -> importImage(context, uri) }
    )
    return payload.takeIf { !it.isEmpty }
}

private fun importImage(context: Context, uri: Uri): String? {
    val directory = context.getExternalFilesDir(null) ?: return null
    if (!directory.exists() && !directory.mkdirs()) return null

    // The display name decides the extension, so a shared PNG stays a PNG instead of being
    // renamed to .jpg while still holding PNG bytes (which coil would then fail to decode).
    val extension = displayName(context, uri)
        ?.substringAfterLast('.', "")
        ?.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
        ?: "jpg"
    val file = File(directory, "shared-${System.currentTimeMillis()}-${uri.hashCode() and 0xFFFF}.$extension")

    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        file.absolutePath
    } catch (throwable: Throwable) {
        Log.e("ShareIntake", "Could not import a shared image", throwable)
        null
    }
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

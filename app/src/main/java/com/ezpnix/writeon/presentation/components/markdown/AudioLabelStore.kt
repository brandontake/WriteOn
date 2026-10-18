package com.ezpnix.writeon.presentation.components.markdown

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import java.io.File

/**
 * User facing names for recorded audio.
 *
 * The file on disk deliberately keeps the name the recorder generated: a note body references its
 * recording through an absolute path in `?audio(...)`, so renaming the file would break every note
 * that already points at it, and a note is not necessarily the only place the path appears. The
 * label stored here is what the player card shows, what a rename edits, and what "save to folder"
 * suggests as the export name.
 *
 * Backed by a [mutableStateMapOf] so a rename recomposes the card immediately.
 */
object AudioLabelStore {
    private const val PREFS = "audio_labels"

    private val labels = mutableStateMapOf<String, String>()
    private var loaded = false

    /** The name to show for [path]: the label the user set, otherwise the file name. */
    fun label(context: Context, path: String): String {
        ensureLoaded(context)
        return labels[path]?.takeIf { it.isNotBlank() } ?: File(path).name
    }

    /** Applies [label] to [path]. Blank input is ignored so a card cannot lose its name. */
    fun rename(context: Context, path: String, label: String) {
        ensureLoaded(context)
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        labels[path] = trimmed
        prefs(context).edit().putString(path, trimmed).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        prefs(context).all.forEach { (key, value) ->
            if (value is String && value.isNotBlank()) labels[key] = value
        }
    }
}

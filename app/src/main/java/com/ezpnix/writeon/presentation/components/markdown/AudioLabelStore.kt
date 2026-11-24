package com.ezpnix.writeon.presentation.components.markdown

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import com.ezpnix.writeon.data.local.database.NoteCrypto
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
 *
 * SEC-10: a label is note content, so it must not sit in SharedPreferences in the clear. Values are
 * sealed with the same Keystore-backed [NoteCrypto] as an attachment caption (the cache is
 * process-wide and the key alias is the same), and plaintext values written by earlier builds are
 * re-sealed the first time they are loaded. [clearInMemory] drops the decrypted map when the app
 * locks or the Vault session is evicted.
 */
object AudioLabelStore {
    private const val PREFS = "audio_labels"
    private const val TAG = "AudioLabelStore"

    private val labels = mutableStateMapOf<String, String>()
    private var loaded = false

    /**
     * SEC-10: the sealing seam, the same one attachment captions and flashcards use.
     *
     * A fresh instance is enough: [NoteCrypto]'s plaintext cache and Keystore entry are shared
     * process-wide, so there is no second source of truth to keep in step.
     */
    private val crypto by lazy { NoteCrypto() }

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
        // SEC-10: never fall back to writing plain text. A Keystore failure keeps the new name in
        // memory for this session and logs it instead of putting note content on disk unsealed.
        val sealed = runCatching { crypto.seal(trimmed) }.getOrNull()
        if (sealed == null) {
            Log.w(TAG, "Could not seal an audio label; it will not be persisted")
            return
        }
        prefs(context).edit().putString(path, sealed).apply()
    }

    /**
     * SEC-10: drops every decrypted label; called by LockManager on a lock or Vault eviction.
     *
     * [loaded] is reset so the next access after an unlock re-reads (and re-decrypts) the store
     * instead of leaving every card without its user-facing name for the rest of the process.
     */
    fun clearInMemory() {
        labels.clear()
        loaded = false
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        prefs(context).all.forEach { (key, value) ->
            if (value !is String || value.isBlank()) return@forEach
            // A sealed value this process cannot open is skipped rather than shown as ciphertext;
            // the card falls back to the file name and the original bytes stay untouched.
            val plain = runCatching { crypto.openOrNull(value) }.getOrNull() ?: return@forEach
            if (plain.isBlank()) return@forEach
            labels[key] = plain
            if (!crypto.isSealed(value)) {
                // SEC-10 migration for labels written before this change.
                runCatching { crypto.seal(plain) }.getOrNull()?.let { sealed ->
                    prefs(context).edit().putString(key, sealed).apply()
                }
            }
        }
    }
}

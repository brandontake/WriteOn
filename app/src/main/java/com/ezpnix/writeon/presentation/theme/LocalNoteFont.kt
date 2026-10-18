package com.ezpnix.writeon.presentation.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.io.FileOutputStream

val LocalNoteFontFamily = compositionLocalOf<FontFamily?> { null }

object NoteFontManager {
    private const val TAG = "NoteFontManager"
    private const val PREFS_NAME = "custom_font_prefs"
    private const val KEY_FONT_NAME = "custom_font_name"
    private const val KEY_FONT_FILE = "custom_font_file"
    private const val FONT_DIR = "fonts"
    private const val FONT_FILE_NAME = "custom_note_font.ttf"

    @Volatile
    private var cachedFontFamily: FontFamily? = null
    @Volatile
    private var cachedFontName: String? = null

    // Reactive Compose state for instant recomposition across the entire app
    private val _fontState = mutableStateOf<FontFamily?>(null)
    private var initialized = false

    fun getFontState(context: Context): State<FontFamily?> {
        if (!initialized) {
            _fontState.value = getFontFamily(context)
            initialized = true
        }
        return _fontState
    }

    fun getFontName(context: Context): String? {
        if (cachedFontName != null) return cachedFontName
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cachedFontName = prefs.getString(KEY_FONT_NAME, null)
        return cachedFontName
    }

    /**
     * Safely loads the cached or persisted custom font.
     * If the font file is corrupt or cannot be parsed by Android, catches Throwable
     * and safely returns null (falling back to system default) without crashing the app.
     */
    fun getFontFamily(context: Context): FontFamily? {
        if (cachedFontFamily != null) return cachedFontFamily
        val fontFile = currentFontFile(context) ?: return null
        return try {
            val family = FontFamily(Font(fontFile))
            cachedFontFamily = family
            family
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load custom font from ${fontFile.absolutePath}", t)
            cachedFontFamily = null
            null
        }
    }

    /**
     * Resolves the font file that is currently active.
     *
     * Each import writes a brand-new file name. Compose caches resolved typefaces by
     * the [Font] instance, which compares the backing file path, so re-using a single
     * fixed file name would keep rendering the previously imported font until the
     * process restarted. The legacy fixed name is still honoured for upgrades.
     */
    private fun currentFontFile(context: Context): File? {
        val dir = File(context.filesDir, FONT_DIR)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_FONT_FILE, null)
        if (!stored.isNullOrBlank()) {
            val file = File(dir, stored)
            if (file.exists() && file.length() > 0L) return file
            return null
        }
        val legacy = File(dir, FONT_FILE_NAME)
        return if (legacy.exists() && legacy.length() > 0L) legacy else null
    }

    /**
     * Imports a user-selected font from SAF Uri into internal storage.
     * Validates that the font can actually be parsed before persisting.
     * Returns the display name of the font, or throws an exception on error.
     */
    fun importFont(context: Context, uri: Uri): String {
        val dir = File(context.filesDir, FONT_DIR).apply { if (!exists()) mkdirs() }
        val tempFile = File(dir, "temp_font.ttf")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Cannot open input stream for font Uri")

            if (tempFile.length() == 0L) {
                throw IllegalStateException("Font file is empty")
            }

            // Really parse the selected file before replacing the active font.
            // Compose's FontFamily(Font(file)) is lazy and would happily accept a
            // corrupt/unsupported file, so it must NOT be used as validation.
            // Typeface.Builder.build() returns null (instead of throwing) when the
            // file is not a loadable font, which is exactly what we need here.
            // (Typeface.Builder is API 26+, matching this app's minSdk.)
            Typeface.Builder(tempFile).build()
                ?: throw IllegalStateException("The selected file is not a valid font")

            val targetName = "note_font_${System.currentTimeMillis()}.ttf"
            val targetFile = File(dir, targetName)
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            // Keep only the newly imported font so stale files never linger.
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name != targetName) {
                    runCatching { file.delete() }
                }
            }

            var displayName = queryFileName(context, uri) ?: "custom_font.ttf"
            if (displayName.contains(".")) {
                displayName = displayName.substringBeforeLast(".")
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_FONT_NAME, displayName)
                .putString(KEY_FONT_FILE, targetName)
                .apply()

            cachedFontName = displayName
            val family = FontFamily(Font(targetFile))
            cachedFontFamily = family
            _fontState.value = family

            return displayName
        } catch (t: Throwable) {
            if (tempFile.exists()) tempFile.delete()
            throw t
        }
    }

    fun resetDefaultFont(context: Context) {
        val dir = File(context.filesDir, FONT_DIR)
        dir.listFiles()?.forEach { file ->
            if (file.isFile) runCatching { file.delete() }
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_FONT_NAME).remove(KEY_FONT_FILE).apply()
        cachedFontName = null
        cachedFontFamily = null
        _fontState.value = null
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    cursor.getString(nameIndex)
                } else null
            }
        }.getOrNull()
    }
}

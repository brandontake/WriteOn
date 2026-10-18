package com.ezpnix.writeon.presentation.theme

import android.content.Context
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
        val fontFile = File(context.filesDir, "$FONT_DIR/$FONT_FILE_NAME")
        if (!fontFile.exists() || fontFile.length() == 0L) {
            return null
        }
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

            // Test loading the font to verify validity before replacing the active font
            FontFamily(Font(tempFile))

            val targetFile = File(dir, FONT_FILE_NAME)
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            var displayName = queryFileName(context, uri) ?: "custom_font.ttf"
            if (displayName.contains(".")) {
                displayName = displayName.substringBeforeLast(".")
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_FONT_NAME, displayName).apply()

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
        val targetFile = File(context.filesDir, "$FONT_DIR/$FONT_FILE_NAME")
        if (targetFile.exists()) {
            targetFile.delete()
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_FONT_NAME).apply()
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

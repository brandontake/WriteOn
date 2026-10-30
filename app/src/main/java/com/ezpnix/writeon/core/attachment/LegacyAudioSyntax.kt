package com.ezpnix.writeon.core.attachment

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The 1.x `?audio(path)` tokens still carried in bodies the 7 -> 8 migration never reached
 * (password-protected notes, notes restored from old backups).
 *
 * Extracting and deleting those paths used to live in the editor's recorder component, which made
 * the note-deletion path in the domain layer import a screen. The functions are pure string and
 * file work, so they belong next to the rest of the attachment plumbing.
 */
object LegacyAudioSyntax {

    private val AUDIO_TAG_REGEX = Regex("""\?audio\(([^)]+)\)""")

    /** Extracts every audio path embedded as `?audio(path)` inside Markdown text. */
    fun extractAudioPaths(text: String): Set<String> =
        AUDIO_TAG_REGEX.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** Deletes the files, but only ones that resolve inside the app's own audios directory. */
    fun deleteAudioFiles(paths: Collection<String>, context: Context) {
        val audioDir = File(context.filesDir, "audios").canonicalPath
        for (path in paths) {
            try {
                val file = File(path)
                // Security guard: the path comes from note text, so it may point anywhere. The
                // trailing separator matters - a plain startsWith would also match a sibling
                // `audios_evil/` directory that merely shares the prefix.
                if (file.exists() && file.canonicalPath.startsWith(audioDir + File.separator)) {
                    file.delete()
                }
            } catch (t: Throwable) {
                Log.e("LegacyAudioSyntax", "Failed to delete audio file: $path", t)
            }
        }
    }
}

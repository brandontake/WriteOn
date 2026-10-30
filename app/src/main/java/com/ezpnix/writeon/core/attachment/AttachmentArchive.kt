package com.ezpnix.writeon.core.attachment

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.ezpnix.writeon.domain.model.Attachment
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Moves the files behind `attachment://` links in and out of a backup archive.
 *
 * A note only stores attachment ids; the bytes live in the app's private directories. Backups used
 * to carry the `attachments` table alone, so restoring on another device (or after clearing data)
 * left every image and recording pointing at a path that no longer existed. Every export now adds
 * an `attachments/<id>.<ext>` member, and every restore copies those members back into the app and
 * rewrites the row's `path` before the database is put in place.
 */
object AttachmentArchive {

    const val DIRECTORY = "attachments"

    private const val TAG = "AttachmentArchive"

    /** Where restored files live. Internal storage keeps them out of MTP and file managers. */
    fun restoreDirectory(context: Context): File = File(context.filesDir, DIRECTORY)

    /** Adds one member per readable attachment. Missing files are skipped rather than failing. */
    fun writeTo(zip: ZipOutputStream, attachments: Collection<Attachment>) {
        val written = HashSet<String>()
        attachments.forEach { attachment ->
            if (attachment.path.isBlank()) return@forEach
            val source = File(attachment.path)
            if (!source.isFile || !source.canRead()) return@forEach
            val extension = source.extension
                .takeIf { it.isNotBlank() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
                ?: if (attachment.type == Attachment.TYPE_AUDIO) "m4a" else "jpg"
            val name = "$DIRECTORY/${attachment.id}.$extension"
            if (!written.add(name)) return@forEach
            zip.putNextEntry(ZipEntry(name))
            source.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /**
     * Copies the members extracted under [stagingDir]/attachments into internal storage and rewrites
     * the matching `path` rows in [databaseFile].
     *
     * The database passed here is still the staged copy, so a failure leaves the live database
     * untouched. Returns the number of files restored.
     */
    fun restore(context: Context, stagingDir: File, databaseFile: File): Int {
        val sourceDir = File(stagingDir, DIRECTORY)
        if (!sourceDir.isDirectory) return 0

        val targetDir = restoreDirectory(context).apply { mkdirs() }
        val restoredPaths = LinkedHashMap<String, String>()

        sourceDir.listFiles().orEmpty().forEach { source ->
            if (!source.isFile) return@forEach
            val id = source.name.substringBeforeLast('.', source.name)
            if (id.isBlank()) return@forEach
            val target = File(targetDir, source.name)
            val moved = runCatching {
                if (target.exists()) target.delete()
                if (!source.renameTo(target)) {
                    source.copyTo(target, overwrite = true)
                    source.delete()
                }
                target
            }.getOrNull()
            if (moved != null && moved.isFile) {
                restoredPaths[id] = moved.absolutePath
            } else {
                Log.w(TAG, "Could not restore attachment ${source.name}")
            }
        }

        if (restoredPaths.isEmpty() || !databaseFile.isFile) return 0

        return runCatching {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { db ->
                db.beginTransaction()
                try {
                    restoredPaths.forEach { (id, path) ->
                        db.execSQL(
                            "UPDATE attachments SET path = ? WHERE `attachment-id` = ?",
                            arrayOf(path, id)
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            restoredPaths.size
        }.getOrElse { error ->
            Log.e(TAG, "Could not rewrite attachment paths in the restored database", error)
            0
        }
    }
}

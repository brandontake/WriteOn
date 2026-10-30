package com.ezpnix.writeon.core.attachment

import android.content.Context
import com.ezpnix.writeon.domain.model.Attachment
import java.io.File

/**
 * Owns the folders attachment files live in, and deletes only inside them.
 *
 * Paths come from a database column, so a stale or restored row can point anywhere. Deleting
 * `File(path)` directly would let a corrupted backup wipe unrelated user files; every delete is
 * therefore re-checked against the canonical root for its type.
 *
 * Images used to live in `getExternalFilesDir()`, which is plaintext on shared storage and outside
 * the protection of the app lock and Keystore. New files go to `filesDir/attachments`; the old
 * external root stays valid for deletion so existing notes can still remove their files.
 */
object AttachmentFiles {

    /** Directory new files of [type] should be written to. Created on demand by the caller. */
    fun directoryFor(context: Context, type: String): File = when (type) {
        Attachment.TYPE_AUDIO -> File(context.filesDir, "audios")
        else -> File(context.filesDir, AttachmentArchive.DIRECTORY)
    }

    private fun rootsFor(context: Context, type: String): List<File> {
        val roots = if (type == Attachment.TYPE_AUDIO) {
            listOf(
                File(context.filesDir, "audios"),
                // A restored recording is unpacked next to the restored images: the backup archive
                // stores every attachment under `attachments/<id>.<ext>`, so without this root a
                // restored audio row could never be deleted again (its file would stay forever).
                File(context.filesDir, AttachmentArchive.DIRECTORY)
            )
        } else {
            listOf(
                File(context.filesDir, AttachmentArchive.DIRECTORY),
                // Legacy 2.0.x location of picked and shared pictures.
                context.getExternalFilesDir(null)
            )
        }
        return roots.filterNotNull().mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
    }

    /** Returns true when a file was actually removed. Never throws. */
    fun delete(context: Context, attachment: Attachment): Boolean =
        deletePath(context, attachment.path, attachment.type)

    fun deletePath(context: Context, path: String, type: String): Boolean = runCatching {
        val file = File(path).canonicalFile
        rootsFor(context, type).any { root ->
            file != root &&
                file.path.startsWith(root.path + File.separator) &&
                file.delete()
        }
    }.getOrDefault(false)

    fun deleteAll(context: Context, attachments: Collection<Attachment>) {
        attachments.forEach { delete(context, it) }
    }
}

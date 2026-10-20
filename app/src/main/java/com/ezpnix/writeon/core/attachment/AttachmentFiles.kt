package com.ezpnix.writeon.core.attachment

import android.content.Context
import com.ezpnix.writeon.domain.model.Attachment
import java.io.File

/**
 * Deletes the file behind an attachment row, and only inside the folders this app owns.
 *
 * Paths come from a database column, so a stale or restored row can point anywhere. Deleting
 * `File(path)` directly would let a corrupted backup wipe unrelated user files; every delete is
 * therefore re-checked against the canonical root for its type.
 */
object AttachmentFiles {

    private fun rootFor(context: Context, type: String): File? = when (type) {
        Attachment.TYPE_AUDIO -> File(context.filesDir, "audios")
        else -> context.getExternalFilesDir(null)
    }?.let { runCatching { it.canonicalFile }.getOrNull() }

    /** Returns true when a file was actually removed. Never throws. */
    fun delete(context: Context, attachment: Attachment): Boolean =
        deletePath(context, attachment.path, attachment.type)

    fun deletePath(context: Context, path: String, type: String): Boolean = runCatching {
        val root = rootFor(context, type) ?: return false
        val file = File(path).canonicalFile
        if (file == root) return false
        if (!file.path.startsWith(root.path + File.separator)) return false
        file.delete()
    }.getOrDefault(false)

    fun deleteAll(context: Context, attachments: Collection<Attachment>) {
        attachments.forEach { delete(context, it) }
    }
}

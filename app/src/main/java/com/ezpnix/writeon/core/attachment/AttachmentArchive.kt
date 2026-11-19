package com.ezpnix.writeon.core.attachment

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.ezpnix.writeon.core.util.DatabaseRestore
import com.ezpnix.writeon.domain.model.Attachment
import java.io.File
import java.io.IOException
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

    /** Caps how many ids/names a D7 reconciliation report puts into logcat. */
    private const val REPORT_LIMIT = 10

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
     *
     * W13/D8: the staged database is validated **first**, before a single file lands in
     * `filesDir`. Both workers also check before calling in; repeating the check here is what
     * fixes the local import path without editing it - its statement order (move attachments,
     * *then* run `quick_check`) lives in a file this one does not own, and used to let a
     * rejected archive overwrite files that were in use.
     *
     * @throws IOException when the staged database fails `quick_check` or the schema gate.
     */
    fun restore(context: Context, stagingDir: File, databaseFile: File): Int {
        if (!DatabaseRestore.quickCheckPasses(databaseFile)) {
            throw IOException("backup database failed integrity check before attachments were moved")
        }
        DatabaseRestore.assertArchiveCompatible(databaseFile)

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

    /**
     * D7: once the restored database is live, compare its `attachments` rows with the files on
     * disk, quarantine what no row references any more, and report rows whose file is gone.
     *
     * Runs *after* the swap on purpose: quarantining earlier would remove files the still-live
     * old database points at whenever the replacement then fails - the exact "restore reported a
     * failure, but the files are already gone" illusion this exists to prevent.
     *
     * Conservative policy: orphan files under the app-owned roots move to
     * [AttachmentFiles.quarantineDirectory] (recoverable; retention/rotation documented on
     * [AttachmentFiles.quarantineAll]); rows without a file are only reported in logcat, never
     * deleted. The legacy external directory is never touched.
     *
     * @return the decision that was applied, or null when the restored database could not be
     *   read (a pre-2.0 archive has no `attachments` table) - in which case nothing is
     *   quarantined either.
     */
    fun reconcileAfterRestore(
        context: Context,
        databaseFile: File
    ): AttachmentFiles.ReconcileDecision? {
        if (!databaseFile.isFile) return null

        val rows = try {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery("SELECT `attachment-id`, path FROM attachments", null).use { cursor ->
                    val byId = LinkedHashMap<String, String>()
                    while (cursor.moveToNext()) {
                        byId[cursor.getString(0)] = cursor.getString(1) ?: ""
                    }
                    byId
                }
            }
        } catch (e: Exception) {
            // No attachments table (pre-2.0 archive) or an unreadable database - both were
            // either accepted deliberately or already refused by quick_check: leave everything.
            Log.w(TAG, "Could not read attachment rows; skipping D7 reconciliation", e)
            return null
        }

        val files = AttachmentFiles.internalRoots(context)
            .flatMap { root -> root.listFiles()?.filter(File::isFile).orEmpty() }
        val decision = AttachmentFiles.decideReconcile(
            rowIds = rows.keys,
            fileNames = files.map { it.name }.toSet(),
            rowPaths = rows,
        )

        // The set-based pass only sees the app-owned roots: a row whose file lives in the legacy
        // external directory is not actually missing, so drop those before reporting.
        val rowsWithoutFile = decision.rowsWithoutFile.filterNot { id ->
            rows[id]?.let { path -> File(path).isFile } == true
        }.toSet()

        if (rowsWithoutFile.isNotEmpty()) {
            Log.w(
                TAG,
                "D7: ${rowsWithoutFile.size} attachment row(s) have no file; reported only, " +
                    "rows kept: ${rowsWithoutFile.take(REPORT_LIMIT)}"
            )
        }

        val orphans = files.filter { it.name in decision.orphanFiles }
        val quarantined = AttachmentFiles.quarantineAll(context, orphans)
        if (orphans.isNotEmpty()) {
            Log.w(
                TAG,
                "D7: quarantined $quarantined of ${orphans.size} orphan attachment file(s): " +
                    "${decision.orphanFiles.take(REPORT_LIMIT)}"
            )
        }

        return decision.copy(rowsWithoutFile = rowsWithoutFile)
    }
}

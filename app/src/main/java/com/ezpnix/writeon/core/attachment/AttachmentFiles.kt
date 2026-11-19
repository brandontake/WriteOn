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

    /** Recordings live in their own directory beside the images. */
    const val AUDIO_DIRECTORY = "audios"

    /** Files no `attachments` row references any more wait here - see [quarantineAll]. */
    const val QUARANTINE_DIRECTORY = "attachments-quarantine"

    /** Directory new files of [type] should be written to. Created on demand by the caller. */
    fun directoryFor(context: Context, type: String): File = when (type) {
        Attachment.TYPE_AUDIO -> File(context.filesDir, AUDIO_DIRECTORY)
        else -> File(context.filesDir, AttachmentArchive.DIRECTORY)
    }

    private fun rootsFor(context: Context, type: String): List<File> {
        val roots = if (type == Attachment.TYPE_AUDIO) {
            listOf(
                File(context.filesDir, AUDIO_DIRECTORY),
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

    // -----------------------------------------------------------------------------------------
    // D7: rows ↔ files reconciliation.
    //
    // Three histories leave a row without a file or a file without a row: a restore that fails
    // after the attachments have landed, a delete whose file removal was swallowed, an editor
    // save that never committed. Nothing in the app ever scanned for either side, so both kinds
    // of leftovers stayed invisible and uncleanable for ever.
    // -----------------------------------------------------------------------------------------

    /** Result of [decideReconcile]: the two halves of a rows ↔ files mismatch. */
    data class ReconcileDecision(
        /**
         * Attachment ids with no file behind them. This is the *candidate* list for row deletion;
         * the policy applied today is report-only (see [decideReconcile]) - deleting rows would
         * also drop captions and the note's reference, so the decision waits for a caller with a
         * confirmation step.
         */
        val rowsWithoutFile: Set<String>,

        /** File names under the managed roots that no row references - quarantine candidates. */
        val orphanFiles: Set<String>,
    )

    /**
     * Pure rows ↔ files decision (D7): from the ids of the rows in the (restored) database and
     * the names of the files on disk, says which rows lost their file and which files lost their
     * row. No I/O, no Android - plain JVM unit tests cover every branch.
     *
     * Matching has two routes on purpose:
     * - files restored from an archive are named `<attachment-id>.<ext>`, so the file's stem is
     *   matched against the row ids;
     * - files picked on this device keep their picker name (`IMG_20240101.jpg`), so the path a
     *   row points at ([rowPaths], id → `attachments.path`) is matched by file name *and* stem.
     *   Without the second route every picked picture would be reported "missing" and its file
     *   wrongly quarantined.
     *
     * Conservative by design: a name that cannot be attributed (blank stem, e.g. `.nomedia`) is
     * never an orphan, and rows are only *reported* - the "delete the row" half of the D7 policy
     * stays a report until a caller with a confirmation step asks for it.
     *
     * @param rowIds ids of every `attachments` row in the database.
     * @param fileNames file names present under the app-owned roots.
     * @param rowPaths id → full `attachments.path` of each row; optional, the two parameters
     *   above are enough for the archive-shaped case.
     */
    fun decideReconcile(
        rowIds: Set<String>,
        fileNames: Set<String>,
        rowPaths: Map<String, String> = emptyMap(),
    ): ReconcileDecision {
        val presentStems = fileNames
            .map { it.substringBeforeLast('.', it) }
            .filter { it.isNotBlank() }
            .toSet()
        val pathBasenames = rowPaths.values
            .map { it.substringAfterLast('/', it) }
            .filter { it.isNotBlank() }
            .toSet()
        val pathStems = pathBasenames
            .map { it.substringBeforeLast('.', it) }
            .filter { it.isNotBlank() }
            .toSet()

        val rowsWithoutFile = rowIds.filter { id ->
            val ownPath = rowPaths[id]?.substringAfterLast('/', "")?.takeIf { it.isNotBlank() }
            val fileForId = id in presentStems
            val fileAtPath = ownPath != null && ownPath in fileNames
            !fileForId && !fileAtPath
        }.toCollection(LinkedHashSet())

        val orphanFiles = fileNames.filter { name ->
            val stem = name.substringBeforeLast('.', name)
            if (stem.isBlank()) {
                // Not attributable to any id - leave it alone rather than guess.
                false
            } else {
                stem !in rowIds && stem !in pathStems && name !in pathBasenames
            }
        }.toCollection(LinkedHashSet())

        return ReconcileDecision(rowsWithoutFile, orphanFiles)
    }

    /**
     * The app-owned roots D7 may read and quarantine from: the archive landing zone and the
     * audio directory. The legacy external root is deliberately absent - files there are never
     * quarantined, because outside this directory pair they are a different class of storage and
     * the conservative D7 policy only *reports* what it does not manage.
     */
    fun internalRoots(context: Context): List<File> =
        listOf(
            File(context.filesDir, AttachmentArchive.DIRECTORY),
            File(context.filesDir, AUDIO_DIRECTORY)
        ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }

    /** Where quarantined orphans wait; see [quarantineAll] for the retention rule. */
    fun quarantineDirectory(context: Context): File = File(context.filesDir, QUARANTINE_DIRECTORY)

    /**
     * Moves one file into quarantine, refusing anything that is not under an app-owned root.
     *
     * Re-checks the canonical location exactly like [deletePath] does: a stale or hostile path
     * must not be able to pull arbitrary files into (or out of) the app's directories.
     *
     * @return true when the file now lives in quarantine.
     */
    fun quarantine(context: Context, file: File): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val allowed = internalRoots(context)
        if (allowed.none { canonical.path.startsWith(it.path + File.separator) }) return false
        if (!canonical.isFile) return false
        return runCatching {
            val directory = quarantineDirectory(context)
            directory.mkdirs()
            val destination = File(directory, canonical.name)
            if (destination.exists()) destination.delete()
            canonical.renameTo(destination) || run {
                canonical.copyTo(destination, overwrite = true)
                canonical.delete()
                destination.isFile
            }
        }.getOrDefault(false)
    }

    /**
     * Quarantines [files] as one round, first dropping whatever the previous round left.
     *
     * Retention rule (the D7 counterpart of the `.bak` rotation in `DatabaseRestore`): nothing
     * consumes this directory yet - no UI, no worker - so keeping rounds forever would turn the
     * recyclable area into a new unbounded store of files, the exact problem it exists to avoid.
     * One round therefore survives until the next reconciliation: a file wrongly quarantined once
     * is either still orphaned then (and cleaned up) or comes back with the archive that owns it.
     * When a TTL worker appears ("move to trash, delete after 7 days"), switch this to rolling
     * retention instead of clearing.
     *
     * @return how many files were moved this round.
     */
    fun quarantineAll(context: Context, files: Collection<File>): Int {
        runCatching { quarantineDirectory(context).deleteRecursively() }
        if (files.isEmpty()) return 0
        quarantineDirectory(context).mkdirs()
        return files.count { quarantine(context, it) }
    }
}

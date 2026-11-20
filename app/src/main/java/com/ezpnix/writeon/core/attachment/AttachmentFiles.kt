package com.ezpnix.writeon.core.attachment

import android.content.Context
import com.ezpnix.writeon.domain.model.Attachment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** One directory per reconciliation round, timestamped so name order is age order. */
    private const val QUARANTINE_GENERATION_PREFIX = "round-"

    /**
     * DAT-6: how many generations survive. One is the round just finished, so a file wrongly
     * quarantined stays recoverable through the next **two** reconciliations before it ages out.
     */
    private const val QUARANTINE_GENERATIONS_KEPT = 3

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

    fun deletePath(context: Context, path: String, type: String): Boolean {
        val deleted = runCatching {
            val file = File(path).canonicalFile
            rootsFor(context, type).any { root ->
                file != root &&
                    file.path.startsWith(root.path + File.separator) &&
                    file.delete()
            }
        }.getOrDefault(false)
        // SEC-4: the decrypted copy in `cacheDir/media` is plaintext and must not outlive the file
        // the user deleted. Best effort, and deliberately also on a refused delete: the row is
        // already gone either way, so a cached copy left behind is unreachable and unencrypted.
        MediaVault.deleteCached(context, path)
        return deleted
    }

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
     *
     * [QUARANTINE_DIRECTORY] is deliberately **not** a root either: it is a sibling
     * (`filesDir/attachments-quarantine`), and the reconcile scan is a non-recursive `listFiles`
     * over these two roots, so a surviving generation can never be re-quarantined or aged out by
     * the scan that is supposed to leave it alone (DAT-6).
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
     * SEC-4: the decrypted copy in `cacheDir/media` is dropped after a successful move, so
     * quarantine cannot leave plaintext behind for a file that is no longer in the library. That
     * eviction is best effort and never blocks the quarantine.
     *
     * @return true when the file now lives in quarantine.
     */
    fun quarantine(context: Context, file: File): Boolean = runCatching {
        val directory = newGenerationDirectory(quarantineDirectory(context)).apply { mkdirs() }
        moveIntoQuarantine(context, file, directory)
    }.getOrDefault(false)

    /**
     * Moves one file into [directory], refusing anything that is not under an app-owned root.
     *
     * Re-checks the canonical location exactly like [deletePath] does: a stale or hostile path
     * must not be able to pull arbitrary files into (or out of) the app's directories.
     */
    private fun moveIntoQuarantine(context: Context, file: File, directory: File): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val allowed = internalRoots(context)
        if (allowed.none { canonical.path.startsWith(it.path + File.separator) }) return false
        if (!canonical.isFile) return false
        return runCatching {
            val destination = File(directory, canonical.name)
            if (destination.exists()) destination.delete()
            val moved = canonical.renameTo(destination) || run {
                canonical.copyTo(destination, overwrite = true)
                canonical.delete()
                destination.isFile
            }
            // SEC-4: a quarantined file leaves the live library, so its decrypted copy in
            // `cacheDir/media` is plaintext debris that must not outlive it. Best effort and
            // deliberately non-blocking: quarantine must succeed even when the cache cannot be
            // touched. The cached name is keyed by the path alone, so this also works now that
            // the source has moved.
            if (moved) runCatching { MediaVault.deleteCached(context, canonical.path) }
            moved
        }.getOrDefault(false)
    }

    /**
     * Quarantines [files] as **one timestamped generation**, then deletes only generations older
     * than the newest [QUARANTINE_GENERATIONS_KEPT] (DAT-6).
     *
     * The previous rule wiped the whole directory at the start of every round, so a file that was
     * quarantined by mistake was gone on the very next reconciliation - before anyone could
     * notice it missing. Rolling retention keeps the round just finished plus two more, which is
     * the window a user (or a later UI) has to recover it. Nothing deletes inside the surviving
     * generations; older rounds are removed by age alone.
     *
     * No `attachments/` directory / no orphans is a no-op: no empty generation is created.
     *
     * SEC-4: every file that moves also loses its decrypted cache copy (see [quarantine]).
     *
     * @return how many files were moved this round.
     */
    fun quarantineAll(context: Context, files: Collection<File>): Int {
        if (files.isEmpty()) return 0
        val quarantineRoot = quarantineDirectory(context)
        val generation = newGenerationDirectory(quarantineRoot).apply { mkdirs() }
        val moved = files.count { moveIntoQuarantine(context, it, generation) }
        pruneGenerations(quarantineRoot)
        return moved
    }

    /**
     * A unique generation directory under [quarantineRoot], named so lexical order is age order.
     * The suffix loop covers two rounds starting inside the same millisecond.
     */
    private fun newGenerationDirectory(quarantineRoot: File): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        var candidate = File(quarantineRoot, QUARANTINE_GENERATION_PREFIX + stamp)
        var attempt = 1
        while (candidate.exists()) {
            candidate = File(quarantineRoot, QUARANTINE_GENERATION_PREFIX + stamp + "-" + attempt++)
        }
        return candidate
    }

    /** Deletes only `round-*` generations outside the retention window; loose files stay. */
    private fun pruneGenerations(quarantineRoot: File) {
        quarantineRoot.listFiles { file ->
            file.isDirectory && file.name.startsWith(QUARANTINE_GENERATION_PREFIX)
        }.orEmpty()
            .sortedBy { it.name }
            .dropLast(QUARANTINE_GENERATIONS_KEPT)
            .forEach { runCatching { it.deleteRecursively() } }
    }
}

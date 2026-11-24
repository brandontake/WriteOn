package com.ezpnix.writeon.core.util

import android.content.Context
import android.util.Log
import com.ezpnix.writeon.core.attachment.AttachmentSnapshots
import com.ezpnix.writeon.core.attachment.LegacyMediaMigration
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.data.backup.FlashcardArchive
import com.ezpnix.writeon.data.backup.RestoreJournal
import com.ezpnix.writeon.data.backup.SnapshotSidecar
import com.ezpnix.writeon.data.backup.VaultSaltArchive
import com.ezpnix.writeon.data.local.database.BackupDatabase
import com.ezpnix.writeon.data.local.database.LegacyCaptionBackfillFlag
import com.ezpnix.writeon.data.local.database.LegacyPlaintextBackfillFlag
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One restorable `.bak` snapshot: where it is, when it was taken and how big it is. */
data class RestoreSnapshotInfo(val file: File, val savedAtMillis: Long, val sizeBytes: Long)

/**
 * DAT-1: the application-side undo for "I restored the wrong backup".
 *
 * Every restore already keeps the replaced database as `.bak` (see
 * [DatabaseRestore.replaceDatabaseAtomically]) but nothing exposed it, so a wrong restore was
 * irreversible from inside the app. This is the backend half - the UI entry point is another
 * lane's work.
 *
 * [restorePrevious] does not restart the process: Room's shared connection is closed and the next
 * `NoteDatabaseProvider.instance()` rebuilds it against the file just installed. It also leaves the
 * *replaced* database as the new `.bak`, so using the undo is itself undoable.
 *
 * N-1/N-2: a `.bak` is a database plus the Vault salt and flashcard deck that database expects,
 * because both live outside it. [com.ezpnix.writeon.data.backup.SnapshotSidecar] is written beside
 * every new snapshot and rotated with it here; a mismatch is refused rather than applied. A
 * snapshot created before the sidecar existed is still restorable, but only its database is
 * swapped - there is no recorded salt/deck to put back.
 */
object RestoreSnapshots {

    private const val TAG = "RestoreSnapshots"

    /** The `.bak` next to the live database, or null when it is missing or empty. */
    fun info(context: Context): RestoreSnapshotInfo? {
        val snapshot = snapshotFile(context)
        if (!snapshot.isFile || snapshot.length() <= 0L) return null
        return RestoreSnapshotInfo(
            file = snapshot,
            savedAtMillis = snapshot.lastModified(),
            sizeBytes = snapshot.length(),
        )
    }

    fun hasSnapshot(context: Context): Boolean = info(context) != null

    /**
     * Swaps `.bak` back in as the live database.
     *
     * Runs under [BackupRestoreLock] so a local import or cloud restore cannot interleave with the
     * close-and-swap, and the close-and-swap itself runs under [NonCancellable] (V-7): a caller
     * being cancelled must never leave the process with a closed connection half-way through a
     * file swap. Cancellation is rethrown rather than reported as a failed restore, so the caller
     * keeps coroutine semantics instead of showing "restore failed" for a restore that completed.
     *
     * @return success, or a failure whose message explains why the snapshot was unusable.
     */
    suspend fun restorePrevious(context: Context): Result<Unit> =
        restorePrevious(context) { target, staged, replaced ->
            DatabaseRestore.replaceDatabaseAtomically(target, staged, replaced)
        }

    /**
     * [restorePrevious] with the install step replaceable.
     *
     * Production always passes [DatabaseRestore.replaceDatabaseAtomically]. The V-4 test passes the
     * same swap with only the final move failing, because that is the only way to reach the window
     * V-4 is about: the live database has already been copied aside when the move fails, and the
     * `.bak` snapshot must still be there afterwards.
     *
     * The third argument is where the *replaced* (current) database is parked. It is a parameter
     * rather than `.bak` on purpose - see [swapInPrevious].
     *
     * TST-06: [onBeforeLock] runs on the IO dispatcher immediately before [BackupRestoreLock] is
     * taken. It exists for the N-7 test, which has to know that this call has reached the lock
     * before it replaces `.bak`; a fixed sleep only guessed. Production passes `null`.
     */
    internal suspend fun restorePrevious(
        context: Context,
        onBeforeLock: (() -> Unit)? = null,
        install: (target: File, staged: File, replaced: File) -> Unit,
    ): Result<Unit> {
        val target = liveDatabaseFile(context)
        val snapshot = DatabaseRestore.backupFile(target)
        if (!snapshot.isFile || snapshot.length() <= 0L) {
            return Result.failure(
                IOException("There is no previous database snapshot to restore")
            )
        }

        return withContext(Dispatchers.IO) {
            // Reached the lock boundary: the callback fires before any suspension on the mutex, so
            // a test holding the lock can replace `.bak` while this call is guaranteed not to have
            // read it yet (the lock is still held by the test).
            onBeforeLock?.invoke()
            // Waiting for the lock is still cancellable: nothing has been closed or written yet,
            // so a cancelled caller simply sees CancellationException and the files are untouched.
            BackupRestoreLock.mutex.withLock {
                // N-7: read the snapshot and its metadata *inside* the same lock every restore
                // takes. The outer existence check is only a fast fail; another import/cloud
                // restore can replace both files between it and the lock, and applying the old
                // sidecar to the new database would hand every `v3:` row the wrong KEK.
                if (!snapshot.isFile || snapshot.length() <= 0L) {
                    return@withLock Result.failure(
                        IOException("There is no previous database snapshot to restore")
                    )
                }
                // N-1: the `.bak` is only half of the snapshot. The Vault salt, flashcard deck and
                // attachment tree it was captured with live in a sidecar next to it. A file that
                // exists but describes different bytes is worse than no file at all - applying it
                // would silently swap the KEK under every `v3:` row - so the undo refuses instead
                // of guessing.
                val sidecar = SnapshotSidecar.read(snapshot)
                if (sidecar is SnapshotSidecar.ReadResult.Mismatch) {
                    return@withLock Result.failure(
                        IOException(
                            "The previous database snapshot does not match its Vault/flashcard " +
                                "metadata; refusing to restore it"
                        )
                    )
                }
                // N-1: a snapshot from before sidecars has no recorded salt. That is safe only if
                // it also predates the `v3:` format; an envelope row would need a KEK we cannot
                // recover.
                if (sidecar is SnapshotSidecar.ReadResult.Missing &&
                    DatabaseRestore.containsEnvelopeRows(snapshot)
                ) {
                    return@withLock Result.failure(
                        IOException(
                            "The previous database snapshot predates Vault metadata and contains " +
                                "envelope-encrypted notes; restoring it safely is not possible"
                        )
                    )
                }

                withContext(NonCancellable) {
                    swapInPrevious(context, target, snapshot, sidecar, install)
                }
            }
        }
    }

    /**
     * The close-and-swap itself, already holding [BackupRestoreLock] and already non-cancellable.
     *
     * Sequence: validate the snapshot (header, `quick_check`, D8 schema gate), close the
     * process-wide Room connection, hand a staged copy to [install], then rotate the replaced
     * current database over `.bak`.
     *
     * V-4: the install must park the current database in a *separate* file
     * ([REPLACED_SUFFIX], same directory), never over `.bak`. Here the snapshot being installed
     * *is* `.bak`, so the default destination would copy the current database on top of the only
     * copy of what the user asked to restore - and a failure after that copy (full disk, permission
     * error, a failed `Files.move`) would leave nothing to retry. With the separate destination:
     * - failure deletes the parked copy (the current database is still live, so nothing is lost)
     *   and `.bak` has not been written at all, so the same snapshot can simply be restored again;
     * - success atomically moves the parked database over `.bak`, which is what keeps the undo
     *   itself undoable.
     *
     * Validation runs *before* the connection is closed, so an unusable snapshot costs neither the
     * open connection nor the `.bak` bytes.
     */
    private fun swapInPrevious(
        context: Context,
        target: File,
        snapshot: File,
        sidecar: SnapshotSidecar.ReadResult,
        install: (target: File, staged: File, replaced: File) -> Unit,
    ): Result<Unit> {
        val replaced = File(target.absolutePath + REPLACED_SUFFIX)
        // N-1/N-2: what the live install holds now. If applying the snapshot's salt or deck fails
        // after the database has been swapped, these put the prefs back in step with the parked
        // current database that is about to be reinstalled. Declared outside the try because the
        // successful path rotates them into the new `.bak` sidecar after it.
        val currentSalt = VaultSaltArchive.read(context)
        val currentDeck = FlashcardArchive.readRaw(context)
        // The current attachment tree becomes the new `.bak`'s pairing when the undo succeeds.
        // Capturing before Room closes also gives a rollback target if the old tree cannot be
        // installed (install itself already restores what it moved, but this is the full tree).
        var redoSnapshot: String? = null
        try {
            if (!DatabaseRestore.quickCheckPasses(snapshot)) {
                throw IOException("The previous database snapshot failed its integrity check")
            }
            DatabaseRestore.assertArchiveCompatible(snapshot)
            redoSnapshot = AttachmentSnapshots.capture(context)
                ?: throw IOException("Could not snapshot the current attachments before undoing")

            // N-1 undo half: the same byte-decision journal as a normal restore. If the undo's
            // database move lands but the process dies before prefs/attachments follow, startup
            // finishes the snapshot's state; if it does not land, the live values stay untouched.
            val found = sidecar as? SnapshotSidecar.ReadResult.Found
            val rawDeck = found?.contents?.flashcards
            val journalDeck = rawDeck?.let(FlashcardArchive::resealRawForRecovery)
            // DAT-04: a deck that exists must be in the crash-recovery journal. Re-sealing can fail
            // when the Keystore is read-only/unavailable; a legacy plaintext deck can still be
            // written verbatim (restoreRaw/openForRecovery pass plaintext through), and a sealed
            // deck this process can open can be journaled in its raw form. Only a deck this install
            // cannot read at all aborts the undo - skipping it would silently lose the deck after a
            // crash, and writing it would make recovery retry forever.
            val deckForJournal = journalDeck ?: rawDeck?.takeIf {
                FlashcardArchive.openForRecovery(it) != null
            }
            if (rawDeck != null && deckForJournal == null) {
                throw IOException("Could not read the flashcard deck before undoing")
            }
            if (!RestoreJournal.write(
                    context = context,
                    restoredDb = snapshot,
                    newSalt = found?.contents?.vaultSalt,
                    newDeckSealed = deckForJournal,
                    setSalt = found != null,
                    // DAT-04/R2-04: the sidecar is also authoritative when it has no deck. The
                    // non-crash path runs `restoreRaw(null)` and clears the deck, so the crash
                    // path must do the same; `setDeck = rawDeck != null` left the current deck in
                    // place, making a kill change what the undo produces.
                    setDeck = found != null,
                    deckRequired = rawDeck != null,
                    attachmentTarget = found?.contents?.attachmentSnapshot,
                    attachmentDiscard = redoSnapshot,
                )
            ) {
                throw IOException("Could not write the restore journal before undoing")
            }

            // DAT-7: the snapshot has passed its checks, so the undo is really going to close the
            // shared Room connection. Publish the restore first - the UI covers the app and the
            // note collectors stop before the connection dies. An unusable snapshot never gets
            // here, so it cannot blank the screen.
            RestoreCoordinator.begin()
            try {
                // The live database's file is held open by the shared Room connection; the rename
                // below would otherwise leave the process reading the replaced file. Closing only
                // after the snapshot has passed its checks keeps an unusable `.bak` from costing
                // the user their open connection.
                BackupDatabase.provider(context).close()

                val staged = File(target.absolutePath + RESTORE_STAGING_SUFFIX)
                try {
                    snapshot.copyTo(staged, overwrite = true)
                    install(target, staged, replaced)
                } finally {
                    staged.delete()
                }

                if (sidecar is SnapshotSidecar.ReadResult.Found) {
                    if (!applySnapshotPrefs(context, sidecar.contents)) {
                        val rolledBack = rollbackParked(target, replaced)
                        if (rolledBack) {
                            VaultSaltArchive.restoreEncoded(context, currentSalt)
                            FlashcardArchive.restoreRaw(context, currentDeck)
                        }
                        throw IOException(
                            if (rolledBack) {
                                "Could not restore the snapshot's Vault/flashcard state; the " +
                                    "previous database was put back"
                            } else {
                                "Could not restore the snapshot's Vault/flashcard state and " +
                                    "the previous database could not be put back"
                            }
                        )
                    }
                    val snapshotId = sidecar.contents.attachmentSnapshot
                    if (snapshotId != null && !AttachmentSnapshots.install(context, snapshotId)) {
                        // The install is all-or-nothing: a false result means the live trees were
                        // put back. Restore the database/prefs too, then put the captured redo
                        // tree in place so all four stores describe the same state again.
                        val rolledBack = rollbackParked(target, replaced)
                        if (rolledBack) {
                            VaultSaltArchive.restoreEncoded(context, currentSalt)
                            FlashcardArchive.restoreRaw(context, currentDeck)
                            redoSnapshot?.let { AttachmentSnapshots.install(context, it) }
                        }
                        throw IOException(
                            if (rolledBack) {
                                "Could not restore the snapshot's attachments; the previous " +
                                    "database was put back"
                            } else {
                                "Could not restore the snapshot's attachments and the previous " +
                                    "database could not be put back"
                            }
                        )
                    }
                }
            } catch (e: Throwable) {
                // The UI entry point restarts only on success; a failure must leave the app usable
                // again, so the collectors come back here. Success keeps the flag set because the
                // process is about to restart against the database that was just installed.
                RestoreCoordinator.end()
                throw e
            }
        } catch (e: CancellationException) {
            // V-7: rethrowing keeps the coroutine contract. `.bak` was never written (see the
            // class comment above), so the only cleanup is the parked current-database copy.
            // The journal and re-do snapshot are deliberately left for startup: the hash decides
            // whether the database move landed, and deleting them here could discard the only
            // record of a half-installed undo.
            replaced.delete()
            throw e
        } catch (e: Exception) {
            // `.bak` still holds the snapshot byte for byte: the install wrote to `replaced`, not
            // to `.bak`. Dropping that copy leaves the live database (still the current one) as
            // the only copy of itself, which is exactly the state before the call. The journal is
            // resolved at next launch by the same byte comparison.
            replaced.delete()
            return Result.failure(e)
        }

        val oldAttachmentSnapshot = (sidecar as? SnapshotSidecar.ReadResult.Found)
            ?.contents
            ?.attachmentSnapshot
        rotateReplacedIntoBackup(replaced, snapshot, currentSalt, currentDeck, redoSnapshot)
        // The undo is complete; the old snapshot belongs to a database that is no longer behind
        // `.bak`. Delete it only after the rotation, so a failed rotation leaves the old pair
        // intact.
        if (oldAttachmentSnapshot != null && oldAttachmentSnapshot != redoSnapshot) {
            AttachmentSnapshots.delete(context, oldAttachmentSnapshot)
        }
        AttachmentSnapshots.pruneExcept(context, redoSnapshot)
        RestoreJournal.clear(context)
        // SEC-5/SEC-4: the old database may contain plaintext rows and captions; the process is
        // normally restarted after this action, so the next launch must not trust a flag written
        // for the DB we just replaced. reset() commits, because a lost flag turns into a skipped
        // backfill of exactly the rows this restore brought back.
        LegacyPlaintextBackfillFlag.reset(context)
        LegacyCaptionBackfillFlag.reset(context)
        // SEC-08: the undo may have put back an attachment tree captured by a build before the
        // legacy-media migration ran. AttachmentSnapshots.install moves bytes unchanged (unlike
        // AttachmentArchive.install, which re-encrypts), so the next launch must rescan instead of
        // trusting a marker written for the tree this undo just replaced.
        LegacyMediaMigration.reset(context)
        return Result.success(Unit)
    }

    /**
     * Writes the snapshot's captured salt/deck into their stores.
     *
     * A null value is meaningful: that snapshot predates the vault or had no deck, so the undo has
     * to clear what the archive restore put there. [VaultSaltArchive.applyEncoded] and
     * [FlashcardArchive.restoreRaw] report commit failures instead of swallowing them; the caller
     * then puts the current database back rather than leave rows under the wrong KEK.
     */
    private fun applySnapshotPrefs(
        context: Context,
        contents: SnapshotSidecar.Contents,
    ): Boolean {
        if (!VaultSaltArchive.applyEncoded(context, contents.vaultSalt)) return false
        return FlashcardArchive.restoreRaw(context, contents.flashcards)
    }

    /**
     * Puts the parked current database back over [target] after a failed salt/deck apply.
     *
     * This is the one rollback that does not go through `.bak`: the snapshot is live by then and
     * `.bak` still holds the original snapshot, so moving `replaced` back is both atomic and
     * keeps the snapshot available for another attempt.
     */
    private fun rollbackParked(target: File, replaced: File): Boolean {
        if (!replaced.isFile || replaced.length() <= 0L) return false
        return try {
            Files.move(
                replaced.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Could not put the previous database back after a prefs failure", e) }
            false
        }
    }

    /**
     * Publishes the replaced current database over `.bak`, making the undo undoable.
     *
     * An atomic move means `.bak` is either the old snapshot or the replaced database, never a
     * torn copy. A failure here does not fail the restore: the swap itself already succeeded, and
     * `.bak` still holds the snapshot (which is now also live, so keeping it costs nothing). The
     * moved-aside bytes are deliberately left on disk instead of deleted - they are the notes the
     * user just restored away from, and may be the only copy - so the log names the file.
     */
    private fun rotateReplacedIntoBackup(
        replaced: File,
        snapshot: File,
        currentSalt: String?,
        currentDeck: String?,
        currentAttachments: String?,
    ) {
        if (!replaced.isFile || replaced.length() <= 0L) return
        try {
            Files.move(
                replaced.toPath(),
                snapshot.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            // N-1/N-2: `.bak` is now the database that was live before the undo, so its sidecar
            // must be the prefs state that database expects. Written after the move; if the write
            // fails, the old sidecar's hash no longer matches and the next undo refuses instead
            // of applying the wrong KEK.
            SnapshotSidecar.write(snapshot, currentSalt, currentDeck, currentAttachments)
        } catch (e: Exception) {
            // Log is a stub on the plain-JVM test runtime; a failed log must not turn a completed
            // restore into a reported failure.
            runCatching {
                Log.w(
                    TAG,
                    "Restored the snapshot but could not rotate the previous database into " +
                        "${snapshot.name}; its bytes are still in ${replaced.name}",
                    e
                )
            }
        }
    }

    /**
     * Startup hygiene: the attachment tree paired with the one `.bak` is the only one that can
     * still be used. A restore killed before its sidecar was written leaves an unreferenced
     * candidate; without this it would sit in noBackupFilesDir forever. A missing/corrupt sidecar
     * keeps nothing, because the undo would refuse that snapshot anyway.
     */
    fun pruneAttachmentSnapshots(context: Context) {
        val backup = DatabaseRestore.backupFile(liveDatabaseFile(context))
        val keep = (SnapshotSidecar.read(backup) as? SnapshotSidecar.ReadResult.Found)
            ?.contents
            ?.attachmentSnapshot
        AttachmentSnapshots.pruneExcept(context, keep)
    }

    private fun liveDatabaseFile(context: Context): File =
        context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)

    private fun snapshotFile(context: Context): File =
        DatabaseRestore.backupFile(liveDatabaseFile(context))

    /** Same-directory temp for the snapshot copy; the suffix never collides with SQLite's. */
    private const val RESTORE_STAGING_SUFFIX = ".snapshot-restore"

    /**
     * Where the install parks the *replaced* current database (V-4). Same directory as the live
     * database so rotating it over `.bak` is a rename, and a distinct suffix so it can never be
     * mistaken for the snapshot.
     */
    internal const val REPLACED_SUFFIX = ".snapshot-replaced"
}

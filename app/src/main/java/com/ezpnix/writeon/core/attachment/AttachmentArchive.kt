package com.ezpnix.writeon.core.attachment

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.ezpnix.writeon.core.util.DatabaseRestore
import com.ezpnix.writeon.domain.model.Attachment
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Moves the files behind `attachment://` links in and out of a backup archive.
 *
 * A note only stores attachment ids; the bytes live in the app's private directories. Backups used
 * to carry the `attachments` table alone, so restoring on another device (or after clearing data)
 * left every image and recording pointing at a path that no longer existed. Every export now adds
 * an `attachments/<id>.<ext>` member. Restore is deliberately split (DAT-4): [prepare] rewrites the
 * staged database's `path` rows without touching `filesDir`, and [install] moves the bytes only
 * after that database has replaced the live one.
 */
object AttachmentArchive {

    const val DIRECTORY = "attachments"

    private const val TAG = "AttachmentArchive"

    /** Prefix of the per-install rollback directory under [WORK_DIRECTORY]; see [install]. */
    private const val ROLLBACK_PREFIX = "attachments-rollback-"

    /**
     * V-8: the journal, rollback and recovery trees all live in
     * `noBackupFilesDir/attachment-restore/` rather than `cacheDir`.
     *
     * `cacheDir` is the directory the OS is invited to delete under storage pressure; a journal
     * evicted there would turn a process kill into an unrecoverable half-install, which is the
     * exact state [recoverPendingInstall] exists to repair. `noBackupFilesDir` is app-private,
     * durable, and excluded from Android backup and device transfer *by platform definition* - so
     * it holds state that must survive a launch without also uploading attachment paths. (It is
     * not a place for content either: the journal holds paths only, never attachment bytes.)
     */
    internal const val WORK_DIRECTORY = "attachment-restore"

    /**
     * V-8: the install plan, written before the first move and replayed by
     * [recoverPendingInstall]. It lives under [WORK_DIRECTORY] because a plan lost to cache
     * eviction could never be completed.
     */
    internal const val INSTALL_JOURNAL_NAME = "attachments-install.journal"

    /** Journal header: "ATJ1". A foreign or half-written file is refused, never replayed. */
    private const val JOURNAL_MAGIC = 0x41544A31
    private const val JOURNAL_VERSION = 1

    /** Sanity cap on a plan read back from disk; a corrupt count must not exhaust memory. */
    private const val JOURNAL_MAX_ENTRIES = 100_000

    /**
     * Prefix of [recoverPendingInstall]'s replacement directory. Only the newest is kept: its
     * parked bytes pair with the `.bak` of the install it repaired, and `.bak` rotates one at a
     * time ([pruneRecoveryDirectories]).
     */
    internal const val RECOVERY_PREFIX = "attachments-recovery-"

    /** Caps how many ids/names a D7 reconciliation report puts into logcat. */
    private const val REPORT_LIMIT = 10

    /** Where restored files live. Internal storage keeps them out of MTP and file managers. */
    fun restoreDirectory(context: Context): File = File(context.filesDir, DIRECTORY)

    /**
     * The durable work tree (journal, rollback and recovery directories), created on demand.
     *
     * File-based rather than Context-based so the recovery helpers stay plain file I/O and the
     * tests can point them at a temporary directory.
     */
    internal fun workDirectory(context: Context): File =
        workDirectoryPath(context).apply { mkdirs() }

    /**
     * [workDirectory] without the `mkdirs`: a "is there a pending install?" check must not create
     * the tree on every launch, and [context.noBackupFilesDir] already exists.
     */
    private fun workDirectoryPath(context: Context): File =
        File(context.noBackupFilesDir, WORK_DIRECTORY)

    /**
     * Adds one member per readable attachment. Missing files are skipped rather than failing.
     *
     * @deprecated SEC-4: this overload copies raw bytes, so a `WMV1` media file lands in the
     *   archive as ciphertext - a backup that only opens on this device, while the restore path
     *   would then hand the reader an undecryptable file. Production must use
     *   [writeTo] with a `Context`, which decrypts through [MediaVault.copyTo].
     *
     *   **No production caller remains**: the local SAF export, the automatic backup and the
     *   WebDAV worker all use the context overload now. This is kept only for tests that document
     *   the old behaviour, and is not to be reintroduced into a backup producer.
     */
    @Deprecated(
        message = "Use writeTo(zip, attachments, context): raw copies leak WMV1 ciphertext into " +
            "the archive and make the backup device-bound.",
        level = DeprecationLevel.WARNING,
    )
    fun writeTo(zip: ZipOutputStream, attachments: Collection<Attachment>) {
        val written = HashSet<String>()
        attachments.forEach { attachment ->
            if (attachment.path.isBlank()) return@forEach
            val source = File(attachment.path)
            if (!source.isFile || !source.canRead()) return@forEach
            val name = entryName(attachment, source)
            if (!written.add(name)) return@forEach
            zip.putNextEntry(ZipEntry(name))
            source.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /**
     * SEC-4: adds one member per readable attachment, decrypting `WMV1` files on the way in.
     *
     * An archive is the only copy of a note that leaves this device, so its media must be
     * portable: [MediaVault.copyTo] writes plaintext into the zip, and the archive's own password
     * encryption (or the transport's) is what protects it there. This is **the overload production
     * should use** - see the deprecated two-argument version for why.
     *
     * @throws IOException when a member cannot be copied. A false result from [MediaVault.copyTo]
     *   means bytes may already have been written to the entry, so the archive must be aborted
     *   rather than left with a truncated member that looks valid.
     */
    fun writeTo(zip: ZipOutputStream, attachments: Collection<Attachment>, context: Context) {
        val written = HashSet<String>()
        attachments.forEach { attachment ->
            if (attachment.path.isBlank()) return@forEach
            val source = File(attachment.path)
            if (!source.isFile || !source.canRead()) return@forEach
            val name = entryName(attachment, source)
            if (!written.add(name)) return@forEach
            zip.putNextEntry(ZipEntry(name))
            if (!MediaVault.copyTo(context, source, zip)) {
                // No closeEntry: the entry is unusable and the caller is about to abort the zip.
                throw IOException("Could not copy attachment ${attachment.id} into the archive")
            }
            zip.closeEntry()
        }
    }

    /**
     * `attachments/<id>.<ext>`, with the fallback extension the reader can still decode.
     *
     * Shared by both [writeTo] overloads on purpose: the archive layout is frozen, and the
     * encrypted and plaintext paths must not drift apart while both exist.
     */
    private fun entryName(attachment: Attachment, source: File): String {
        val extension = source.extension
            .takeIf { it.isNotBlank() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
            ?: if (attachment.type == Attachment.TYPE_AUDIO) "m4a" else "jpg"
        return "$DIRECTORY/${attachment.id}.$extension"
    }

    /**
     * A restore's attachment half, split into two phases so no live file is touched before the
     * database swap has succeeded (DAT-4).
     *
     * [stagedFiles] are still inside the extraction directory; [pathsById] is the final
     * `attachments.path` value written into the staged database. Both are inert until [install].
     */
    data class PreparedAttachments(
        val stagedFiles: List<Pair<File, File>>,
        val pathsById: Map<String, String>,
    ) {
        val isEmpty: Boolean get() = stagedFiles.isEmpty()
    }

    /**
     * Validates the staged database, computes where each restored member will live, and rewrites
     * the staged database's `attachments.path` rows - **without touching `filesDir`**.
     *
     * The rewrite has to happen before the swap because the restored database is what Room opens
     * immediately afterwards; doing it here means the file that becomes live already points at
     * the paths [install] will create. A failure at any point leaves every live attachment byte
     * exactly as it was.
     *
     * W13/D8: the staged database is validated first, including [DatabaseRestore.quickCheckPasses]
     * and the schema gate. Callers already check before calling in; repeating it is cheap compared
     * with the alternative, and it keeps this function safe for any future caller.
     *
     * Pre-2.0 archives have no `attachments/` directory: that is a supported no-op and returns an
     * empty [PreparedAttachments] (the database still restores normally).
     *
     * @throws IOException when the staged database fails `quick_check`, the schema gate, or its
     *   rows cannot be rewritten.
     */
    fun prepare(context: Context, stagingDir: File, databaseFile: File): PreparedAttachments {
        if (!DatabaseRestore.quickCheckPasses(databaseFile)) {
            throw IOException("backup database failed integrity check before attachments were prepared")
        }
        DatabaseRestore.assertArchiveCompatible(databaseFile)

        val sourceDir = File(stagingDir, DIRECTORY)
        if (!sourceDir.isDirectory) return PreparedAttachments(emptyList(), emptyMap())

        // The directory itself must not be created yet: an archive that turns out to be rejected
        // after this point would otherwise leave an empty `attachments/` behind.
        val targetDir = restoreDirectory(context)
        val stagedFiles = ArrayList<Pair<File, File>>()
        val restoredPaths = LinkedHashMap<String, String>()

        sourceDir.listFiles().orEmpty().forEach { source ->
            if (!source.isFile) return@forEach
            val id = source.name.substringBeforeLast('.', source.name)
            if (id.isBlank()) return@forEach
            val target = File(targetDir, source.name)
            stagedFiles += source to target
            restoredPaths[id] = target.absolutePath
        }

        if (restoredPaths.isNotEmpty()) {
            try {
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
            } catch (error: Exception) {
                // Do not degrade to "restore the database with old paths": that is exactly the
                // dangling-path state this rewrite exists to prevent. Nothing live was touched.
                throw IOException("Could not rewrite attachment paths in the staged database", error)
            }
        }

        return PreparedAttachments(stagedFiles, restoredPaths)
    }

    /**
     * Moves the prepared members into `filesDir` after the database swap.
     *
     * SEC-4: each installed file is encrypted in place before the next one is touched. Archive
     * members are plaintext by definition (see [writeTo]'s context overload), so a restore is the
     * one path that can legitimately put plaintext media into `filesDir`; re-encrypting here keeps
     * "encrypted at rest" true after a restore as well. Failure is **not** fatal: the reader
     * handles plaintext, and a restore that otherwise succeeded must not be rolled back because
     * the media key was temporarily unavailable.
     *
     * DAT-4: an existing same-name file is moved into a timestamped rollback directory under
     * [WORK_DIRECTORY] instead of being deleted. Two things can then go wrong safely:
     * - a failure mid-install restores every file moved aside and rethrows, leaving the caller to
     *   roll the database back from `.bak`;
     * - a successful install still leaves the superseded bytes recoverable while the new `.bak`
     *   (the database those files belonged to) exists.
     *
     * The rollback tree lives under `noBackupFilesDir`, not `cacheDir`: a cache eviction between
     * the database swap and the last move would otherwise delete the only copy of the files the
     * still-offered `.bak` database points at (V-8).
     *
     * Only the newest rollback directory is kept: `.bak` rotates one at a time, so older rounds
     * can never be paired with a database that still exists.
     *
     * @return how many files were installed.
     * @throws IOException when a member cannot be moved; all changes made by this call are undone
     *   before it is thrown.
     */
    fun install(context: Context, prepared: PreparedAttachments): Int {
        if (prepared.isEmpty) return 0

        val workDir = workDirectory(context)
        val rollbackDir = uniqueDirectory(workDir, ROLLBACK_PREFIX)
        // V-8: the plan is durable before the first byte moves. From this point a process kill is
        // repaired at startup by [recoverPendingInstall]; without it the new database would be
        // live while only some of its files exist (and some old ones sit in the rollback dir).
        val journal = writeInstallJournal(workDir, prepared.stagedFiles)
        val movedAside = ArrayList<Pair<File, File>>()
        val installed = ArrayList<File>()

        try {
            prepared.stagedFiles.forEach { (source, target) ->
                target.parentFile?.mkdirs()

                if (target.exists()) {
                    rollbackDir.mkdirs()
                    val saved = uniqueChild(rollbackDir, target.name)
                    if (!target.safeMove(saved)) {
                        // Nothing changed for this member: the live file is still in place, so
                        // only the members processed earlier have to be undone by the catch.
                        throw IOException("Could not move ${target.name} aside")
                    }
                    movedAside += target to saved
                }

                if (!source.safeMove(target)) {
                    throw IOException("Could not install attachment ${target.name}")
                }
                installed += target

                // SEC-4: archive members are plaintext, so each installed file is encrypted right
                // after it lands. Non-fatal by design: `MediaVault` leaves the plaintext intact
                // when it fails, the reader handles plaintext, and the database is already live -
                // failing the restore over this would undo a successful one.
                runCatching { MediaVault.encryptInPlace(context, target) }
                    .onFailure { Log.w(TAG, "Could not re-encrypt restored ${target.name}", it) }
                    .onSuccess { encrypted ->
                        if (!encrypted) {
                            Log.w(TAG, "Restored ${target.name} stays plaintext; media key unavailable")
                        }
                    }
            }
        } catch (error: Exception) {
            undoInstall(installed, movedAside)
            runCatching { rollbackDir.deleteRecursively() }
            // The undo has already restored the previous state, so the plan is finished either
            // way. Deleting last keeps a kill *during* the undo replayable on the next start.
            runCatching { journal.delete() }
            throw IOException("Could not install restored attachments; previous files were put back", error)
        }

        pruneRollbackDirectories(workDir, keep = rollbackDir.takeIf { it.exists() })
        // A kill between the last move and this delete is harmless: the next recovery sees every
        // source gone and only removes the stale journal.
        runCatching { journal.delete() }
        return installed.size
    }

    /**
     * V-8: finishes an [install] that a process kill interrupted.
     *
     * The database swap happens before [install], so replay always moves *forward*: a staged
     * source that still exists is installed, and a live target it would replace is moved into
     * [RECOVERY_PREFIX] first (never deleted, so the bytes paired with a `.bak` stay reachable).
     * A source that is already gone means that entry was installed before the kill, so the live
     * file is left alone - which also makes a second replay a no-op.
     *
     * Paths are read from the journal only; the journal is deleted at the very end, so this
     * function is safe to interrupt again. The common no-crash case costs one `isFile` check.
     *
     * SEC-4: the archive members replayed here are plaintext (see [writeTo]'s context overload),
     * so the Context variant re-encrypts every target that is in place after the replay. Recovery
     * therefore keeps "encrypted at rest" true even for a restore that never reached the end of
     * [install]. That encryption is best effort: a target it cannot encrypt is logged and left as
     * plaintext - the reader handles it, and a recovery that repaired the files must not fail
     * because the media key is temporarily unavailable.
     *
     * @return how many entries were moved by this call; 0 when there was nothing to do.
     */
    fun recoverPendingInstall(context: Context): Int =
        recoverPendingInstall(workDirectoryPath(context)) { target ->
            // Mirrors [install]: archive bytes are plaintext, so a replayed target is encrypted
            // right after it lands. Failure is logged, never fatal.
            runCatching { MediaVault.encryptInPlace(context, target) }
                .onFailure { Log.w(TAG, "Could not re-encrypt recovered ${target.name}", it) }
                .onSuccess { encrypted ->
                    if (!encrypted) {
                        Log.w(TAG, "Recovered ${target.name} stays plaintext; media key unavailable")
                    }
                }
        }

    /**
     * True when a previous process died between the database swap and the end of [install].
     *
     * Startup uses this to decide whether the full D7 reconciliation is needed: running it on every
     * launch would scan the whole attachments table and directory for nothing. A restore that
     * completes normally already reconciles itself in the path that called [install].
     *
     * Checks the durable [WORK_DIRECTORY]: a journal in `cacheDir` (a pre-V-8 build) is not
     * replayed, and no new one is written there.
     */
    fun hasPendingInstall(context: Context): Boolean =
        installJournal(workDirectoryPath(context)).isFile

    /**
     * [recoverPendingInstall] over an explicit work directory - the same location [install] uses
     * ([workDirectory]). Kept File-based so the tests never need a real `noBackupFilesDir`.
     */
    internal fun recoverPendingInstall(workDir: File): Int =
        recoverPendingInstall(workDir) { }

    /**
     * The File seam with an install hook, kept internal: [onInstalled] receives every attachment
     * target that is in place after a replay (newly moved *and* already installed before the
     * kill), which is how [recoverPendingInstall]'s Context variant re-encrypts without this file
     * I/O having to know about [MediaVault]. The hook is informational for the replay itself: it
     * must not throw, and a throw is not a replay failure.
     */
    internal fun recoverPendingInstall(workDir: File, onInstalled: (File) -> Unit): Int {
        val journal = installJournal(workDir)
        if (!journal.isFile) return 0

        val entries = readInstallJournal(journal)
        if (entries == null) {
            // Torn or foreign: it cannot describe a plan, so replaying it could move wrong paths.
            // There is no old state to keep, and leaving it would block every later start.
            Log.w(TAG, "Ignoring unreadable attachment install journal")
            runCatching { journal.delete() }
            return 0
        }
        if (entries.isEmpty()) {
            runCatching { journal.delete() }
            return 0
        }

        val recoveryDir = uniqueDirectory(workDir, RECOVERY_PREFIX)
        var installed = 0
        try {
            entries.forEach { (source, target) ->
                // Already installed (or a stale plan): the live file is authoritative now.
                if (!source.isFile) {
                    // The hook still runs: a kill between the move and install's encryption
                    // leaves a plaintext target here, and this is the only second chance it gets.
                    if (target.isFile) runCatching { onInstalled(target) }
                    return@forEach
                }
                target.parentFile?.mkdirs()

                if (target.exists()) {
                    recoveryDir.mkdirs()
                    val saved = uniqueChild(recoveryDir, target.name)
                    if (!target.safeMove(saved)) {
                        throw IOException("Could not move ${target.name} aside during recovery")
                    }
                }

                if (!source.safeMove(target)) {
                    throw IOException("Could not finish installing ${target.name}")
                }
                installed++
                // SEC-4: the bytes that just landed are plaintext archive members. A hook failure
                // must not turn a repaired install into a paused one.
                runCatching { onInstalled(target) }
            }
        } catch (error: Exception) {
            // Keep the journal: the next start retries from the failed entry. Replaying the
            // entries already moved is safe because their source no longer exists.
            Log.w(TAG, "Attachment install recovery paused after $installed file(s)", error)
            return installed
        }

        runCatching { journal.delete() }
        pruneRecoveryDirectories(workDir, keep = recoveryDir.takeIf { it.exists() })
        return installed
    }

    /**
     * The journal location inside the durable work tree. Used to be `cacheDir`; that tree is
     * evictable by the OS, which is precisely what V-8 must survive.
     */
    internal fun installJournal(workDir: File): File = File(workDir, INSTALL_JOURNAL_NAME)

    /**
     * Writes the complete install plan with a magic/version header and `writeUTF` paths (no
     * delimiter to escape), fsyncs the bytes, and only then renames the `.part` sibling over the
     * real name. A kill during the write therefore leaves a `.part` file that recovery ignores,
     * never a truncated plan that could point at the wrong target.
     */
    internal fun writeInstallJournal(workDir: File, entries: List<Pair<File, File>>): File {
        val journal = installJournal(workDir)
        val part = File(workDir, "$INSTALL_JOURNAL_NAME.part")
        workDir.mkdirs()
        try {
            FileOutputStream(part).use { stream ->
                DataOutputStream(BufferedOutputStream(stream)).use { output ->
                    output.writeInt(JOURNAL_MAGIC)
                    output.writeInt(JOURNAL_VERSION)
                    output.writeInt(entries.size)
                    entries.forEach { (source, target) ->
                        output.writeUTF(source.absolutePath)
                        output.writeUTF(target.absolutePath)
                    }
                    output.flush()
                    stream.fd.sync()
                }
            }
            if (journal.exists() && !journal.delete()) {
                throw IOException("Could not replace ${journal.name}")
            }
            if (!part.renameTo(journal)) {
                throw IOException("Could not publish ${journal.name}")
            }
        } catch (error: Exception) {
            runCatching { part.delete() }
            throw if (error is IOException) error
            else IOException("Could not write the attachment install journal", error)
        }
        return journal
    }

    /** Null when the journal is torn, foreign, or from another version; never partially parsed. */
    private fun readInstallJournal(journal: File): List<Pair<File, File>>? =
        runCatching<List<Pair<File, File>>?> {
            DataInputStream(BufferedInputStream(FileInputStream(journal))).use { input ->
                if (input.readInt() != JOURNAL_MAGIC) return@runCatching null
                if (input.readInt() != JOURNAL_VERSION) return@runCatching null
                val count = input.readInt()
                if (count < 0 || count > JOURNAL_MAX_ENTRIES) return@runCatching null
                val entries = ArrayList<Pair<File, File>>(count)
                repeat(count) {
                    entries += File(input.readUTF()) to File(input.readUTF())
                }
                entries
            }
        }.getOrNull()

    /** A unique timestamped directory under [workDir]; lexical name order stays age order. */
    private fun uniqueDirectory(workDir: File, prefix: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        var candidate = File(workDir, prefix + stamp)
        var attempt = 1
        while (candidate.exists()) {
            candidate = File(workDir, prefix + stamp + "-" + attempt++)
        }
        return candidate
    }

    /** Keeps two entries with the same file name from overwriting each other while parked. */
    private fun uniqueChild(directory: File, name: String): File {
        var candidate = File(directory, name)
        var attempt = 1
        while (candidate.exists()) {
            candidate = File(directory, "$name.$attempt")
            attempt++
        }
        return candidate
    }

    /**
     * Moves [source] to [target], replacing an existing target.
     *
     * `renameTo` is attempted first because the extraction directory and `filesDir` normally live
     * on the same volume; the copy fallback covers the case where they do not.
     */
    private fun File.safeMove(target: File): Boolean {
        if (target.exists() && !target.delete()) return false
        if (renameTo(target)) return true
        return runCatching {
            copyTo(target, overwrite = true)
            delete()
            target.isFile
        }.getOrDefault(false)
    }

    /** Reverses [install]'s work in reverse order: drop new files, then put saved ones back. */
    private fun undoInstall(installed: List<File>, movedAside: List<Pair<File, File>>) {
        installed.asReversed().forEach { file -> runCatching { file.delete() } }
        movedAside.asReversed().forEach { (target, saved) ->
            runCatching {
                target.parentFile?.mkdirs()
                if (target.exists()) target.delete()
                if (!saved.renameTo(target)) saved.copyTo(target, overwrite = true)
            }
        }
    }

    /**
     * Keeps only [keep] under the work tree's rollback directories; older rounds belong to a
     * `.bak` that the next database swap has already replaced.
     */
    private fun pruneRollbackDirectories(workDir: File, keep: File?) {
        workDir.listFiles { file ->
            file.isDirectory && file.name.startsWith(ROLLBACK_PREFIX)
        }.orEmpty()
            .filter { it.name != keep?.name }
            .forEach { runCatching { it.deleteRecursively() } }
    }

    /**
     * Keeps only [keep] among the work tree's recovery directories.
     *
     * Same reasoning as [pruneRollbackDirectories]: a recovery directory holds the bytes an
     * interrupted install's replacement displaced, and pairs with the `.bak` of that install.
     * `.bak` rotates one restore at a time, so an older round can never be paired again - and now
     * that the tree is durable instead of evictable cache, nothing else would ever remove it.
     * Only called after a replay ran to completion; a paused replay returns earlier and keeps
     * every directory it might still need.
     */
    private fun pruneRecoveryDirectories(workDir: File, keep: File?) {
        workDir.listFiles { file ->
            file.isDirectory && file.name.startsWith(RECOVERY_PREFIX)
        }.orEmpty()
            .filter { it.name != keep?.name }
            .forEach { runCatching { it.deleteRecursively() } }
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

        // `listFiles` is non-recursive on purpose: the quarantine tree is a *sibling* of these
        // roots (`filesDir/attachments-quarantine`), and a recursive walk would re-quarantine the
        // recoverable files DAT-6 keeps there generation after generation.
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

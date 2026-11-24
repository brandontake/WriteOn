package com.ezpnix.writeon.core.attachment

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.ezpnix.writeon.core.constant.DatabaseConst
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
import java.security.MessageDigest
import java.security.SecureRandom
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
     * R2-08: marker inside a rollback directory whose undo was incomplete.
     *
     * The directory is the only copy of the files the still-live `.bak` database points at, so it
     * must survive later installs' pruning. A file (not a naming convention) is used because the
     * journal that recorded the failure can be overwritten by the next restore plan.
     */
    internal const val ROLLBACK_KEEP_MARKER = ".keep-for-bak"

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

    /**
     * DAT-05: where a restore extracts attachment members before [prepare] registers them.
     *
     * This used to be `cacheDir/restore-staging`. The install journal records these source paths,
     * and it lives in durable storage precisely so a kill can be replayed - but a cache eviction
     * between the kill and the next start deleted the sources, and recovery then read "source
     * missing" as "already installed". Keeping the staging tree next to the journal in
     * [WORK_DIRECTORY] makes both halves of the journal durable.
     */
    internal const val STAGING_DIRECTORY = "staging"

    /** REG-04: only UUID-shaped per-operation names are accepted as staging directories. */
    private val STAGING_OPERATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,63}")

    /**
     * DAT-05/SEC-11: age before an unreferenced staging directory is considered abandoned.
     *
     * Startup cleanup runs before any screen, but WorkManager may start a restore moments after
     * `Application.onCreate` returns. A directory created by such a restore is fresh, so the age
     * gate keeps the cleanup from deleting an in-flight operation between listing and deleting.
     */
    internal const val STAGING_STALE_MILLIS = 10L * 60 * 1000

    /** Journal header: "ATJ1". A foreign or half-written file is refused, never replayed. */
    private const val JOURNAL_MAGIC = 0x41544A31
    /**
     * v2 (R2-08/P1): the journal also records which database the plan belongs to and which
     * rollback directory holds the files it displaced.
     *
     * v3 (R4-03): the identity is a random token stamped into the live database's SQLite
     * `application_id` header field, not only its SHA-256. A note save or the startup plaintext
     * sweep changes the bytes of a perfectly restored database; the header field survives those
     * writes, so recovery can still tell "the restored database is live" from "the caller put
     * `.bak` back".
     *
     * A v1/v2 journal (no fields / no token) is still read: those fall back to the hash decision.
     */
    private const val JOURNAL_VERSION = 3
    private const val JOURNAL_VERSION_MIN = 1

    /** Sentinel for "no generation token recorded" in the journal; 0 is SQLite's default id. */
    private const val NO_GENERATION_TOKEN = 0

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
     * DAT-05: the restore extraction root for attachment members, inside the durable work tree.
     *
     * REG-04: this is only the *parent*. Each restore gets its own child directory named by
     * [stagingDirectory] / [newStagingDirectory], so a local import and a cloud restore can never
     * delete each other's staged bytes (they used to share one fixed directory that the cloud
     * worker deleted before taking the restore mutex).
     */
    fun stagingRoot(context: Context): File = File(workDirectoryPath(context), STAGING_DIRECTORY)

    /** A per-operation staging directory; the caller owns creation and removal. */
    fun stagingDirectory(context: Context, operationId: String): File {
        require(STAGING_OPERATION_ID.matches(operationId)) {
            "Invalid attachment staging operation id"
        }
        return File(stagingRoot(context), operationId)
    }

    /**
     * A fresh, process-unique staging directory for one restore operation.
     *
     * The name is a UUID, so two operations can never collide and cleanup can remove one without
     * touching another. This only computes the path; the caller creates it while holding
     * [com.ezpnix.writeon.core.util.BackupRestoreLock.mutex].
     */
    fun newStagingDirectory(context: Context): File =
        stagingDirectory(context, java.util.UUID.randomUUID().toString())

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
     * DAT-09: a row whose file is missing or unreadable **fails** the archive by default. A backup
     * that silently drops a referenced image or recording is worse than one that refuses to run:
     * the user is told "backup completed" and only finds the hole when restoring. The old skip was
     * also how one pruned attachment row made every later backup quietly incomplete.
     *
     * @param skipMissing kept only for a legacy caller/test that truly needs the old skip; every
     *   production producer must use the default (strict).
     * @throws IOException when a member cannot be copied. A false result from [MediaVault.copyTo]
     *   means bytes may already have been written to the entry, so the archive must be aborted
     *   rather than left with a truncated member that looks valid.
     */
    fun writeTo(
        zip: ZipOutputStream,
        attachments: Collection<Attachment>,
        context: Context,
        skipMissing: Boolean = false,
    ) {
        val written = HashSet<String>()
        attachments.forEach { attachment ->
            // A blank path is a row with no file attached at all (not a missing member).
            if (attachment.path.isBlank()) return@forEach
            val source = runCatching { File(attachment.path).canonicalFile }.getOrNull()
            if (source == null) {
                if (skipMissing) {
                    Log.w(TAG, "Skipping attachment ${attachment.id}: its path cannot be resolved")
                    return@forEach
                }
                throw IOException("Could not resolve the path of attachment ${attachment.id}")
            }
            if (!source.isFile || !source.canRead()) {
                if (skipMissing) {
                    Log.w(TAG, "Skipping attachment ${attachment.id}: the file is missing or unreadable")
                    return@forEach
                }
                throw IOException(
                    "Attachment ${attachment.id} is missing or unreadable; refusing a partial archive"
                )
            }
            // SEC-07: `path` comes from a database row, so a crafted or restored row could point
            // at `shared_prefs/*.xml` and the next backup would carry the lock hash or the vault
            // salt off the device. A path inside the app's private data directory must be under a
            // media root; anything else keeps the historical behaviour (the legacy external media
            // directory has always been outside that tree).
            if (!mayArchiveSource(context, source)) {
                Log.w(
                    TAG,
                    "Skipping attachment ${attachment.id}: its path is outside the app media roots"
                )
                return@forEach
            }
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
     * SEC-07: the canonical roots a backup may read attachment bytes from.
     *
     * The two current media directories come from [AttachmentFiles.internalRoots]; the legacy
     * external directory is still accepted so pictures picked by an older build remain backed up.
     * Everything else - prefs, the database, the archive work trees - is out of bounds.
     */
    private fun mediaRoots(context: Context): List<File> =
        (
            AttachmentFiles.internalRoots(context) + listOfNotNull(
                context.getExternalFilesDir(null)?.let { dir ->
                    runCatching { dir.canonicalFile }.getOrNull()
                }
            )
        ).distinctBy { it.path }

    /** True only for a canonical file strictly inside one of [mediaRoots]. */
    private fun isUnderMediaRoot(context: Context, file: File): Boolean =
        mediaRoots(context).any { root -> file.path.startsWith(root.path + File.separator) }

    /**
     * SEC-07: whether a row's path may be read into an archive.
     *
     * The private data directory holds the prefs, the database and the work trees - a restored or
     * crafted row pointing there is exactly the leak this check exists for, so such a path must be
     * under a media root. A file outside that directory (the legacy external media folder, or a
     * caller-provided source in a test) keeps the historical behaviour: skipping it would silently
     * drop media that older installs legitimately hold.
     */
    private fun mayArchiveSource(context: Context, file: File): Boolean {
        val appData = runCatching { context.filesDir.parentFile?.canonicalFile }.getOrNull()
            ?: return true
        val insideAppData = file.path.startsWith(appData.path + File.separator)
        return !insideAppData || isUnderMediaRoot(context, file)
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
     * R2-09: finishes a restore that swapped the database and died before its attachments landed.
     *
     * The restore journal now records the staging directory. On the next start the staged database
     * is already live and its `attachments.path` rows were rewritten by [prepare] to the names in
     * `<staging>/attachments`, so the plan can be rebuilt from the directory alone - no database
     * file is needed (the staged copy was moved onto the live name).
     *
     * Files already moved by the killed run are simply absent from the source directory; the
     * install journal (if one was written) replays those. This only has to cover the window before
     * the first [install] call.
     *
     * @return how many files this call installed.
     */
    fun installStagedAttachments(
        context: Context,
        stagingDir: File,
    ): Int {
        val sourceDir = File(stagingDir, DIRECTORY)
        if (!sourceDir.isDirectory) return 0
        val targetDir = restoreDirectory(context)
        val plan = sourceDir.listFiles().orEmpty().mapNotNull { source ->
            if (!source.isFile) return@mapNotNull null
            val id = source.name.substringBeforeLast('.', source.name)
            if (id.isBlank()) return@mapNotNull null
            source to File(targetDir, source.name)
        }
        if (plan.isEmpty()) return 0
        // R3-05: a recovery install must not consume the only remaining copy of the archive
        // members. Copying leaves the staged source available when the install fails and its undo
        // removes the target; the normal path keeps its atomic move.
        return install(
            context = context,
            prepared = PreparedAttachments(plan, emptyMap()),
            move = { source, target -> source.safeMove(target) },
            sourceMove = { source, target ->
                runCatching {
                    source.copyTo(target, overwrite = true)
                    target.isFile
                }.getOrDefault(false)
            },
        )
    }

    /** R2-09: whether [stagingDir] still holds attachment members to install. */
    fun hasStagedAttachments(stagingDir: File): Boolean =
        File(stagingDir, DIRECTORY).isDirectory

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
     *   before it is thrown. DAT-10: if that undo itself cannot be completed, the rollback tree
     *   and the install journal are deliberately kept, and the message names the kept directory.
     */
    fun install(
        context: Context,
        prepared: PreparedAttachments,
    ): Int = install(
        context,
        prepared,
        move = { source, target -> source.safeMove(target) },
    )

    /**
     * DAT-10: [install] with the move and rollback primitives injectable, so the "undo itself
     * fails" branch - the one that must not delete the rollback tree - is testable.
     * Production always passes [safeMove] and the default restore below.
     */
    internal fun install(
        context: Context,
        prepared: PreparedAttachments,
        move: (File, File) -> Boolean,
        restore: ((saved: File, target: File) -> Boolean)? = null,
        /**
         * R3-05: how a staged source is put in place. Production uses the atomic move; the startup
         * recovery path copies, so a failed install's undo cannot delete the only staged copy.
         */
        sourceMove: ((File, File) -> Boolean)? = null,
    ): Int {
        if (prepared.isEmpty) return 0

        val workDir = workDirectory(context)
        val rollbackDir = uniqueDirectory(workDir, ROLLBACK_PREFIX).apply { mkdirs() }
        // R5-01/R5-02: the generation token is resolved *before* the hash.
        //
        // A restore stamps the staged database before it becomes live (RestoreJournal.write with
        // stampGeneration), so `read` returns that token and this call changes no byte - which is
        // what keeps the restore journal's whole-file hash valid. Only a database no restore
        // prepared (a test fixture, a legacy path) needs a stamp here; the hash below then covers
        // the stamped bytes, so a later token-unreadable fallback compares like with like.
        val liveDatabase = context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)
        val generationToken = InstallGeneration.read(liveDatabase)?.takeIf { it != 0 }
            ?: InstallGeneration.stamp(liveDatabase)
        // R3-01: the plan belongs to the database that is live *now* - after [prepare] rewrote the
        // staged copy's attachment paths and after the caller swapped it in. Hashing the live file
        // here removes the ordering trap that made a normal forward install look like a rollback.
        val databaseHash = sha256File(liveDatabase)
        // V-8: the plan is durable before the first byte moves. From this point a process kill is
        // repaired at startup by [recoverPendingInstall]; without it the new database would be
        // live while only some of its files exist (and some old ones sit in the rollback dir).
        val journal = writeInstallJournal(
            workDir,
            prepared.stagedFiles,
            databaseHash = databaseHash,
            rollbackDir = rollbackDir,
            generationToken = generationToken,
        )
        val movedAside = ArrayList<Pair<File, File>>()
        val installed = ArrayList<File>()

        try {
            prepared.stagedFiles.forEach { (source, target) ->
                target.parentFile?.mkdirs()

                if (target.exists()) {
                    rollbackDir.mkdirs()
                    val saved = uniqueChild(rollbackDir, target.name)
                    if (!move(target, saved)) {
                        // Nothing changed for this member: the live file is still in place, so
                        // only the members processed earlier have to be undone by the catch.
                        throw IOException("Could not move ${target.name} aside")
                    }
                    movedAside += target to saved
                }

                if (!(sourceMove ?: move)(source, target)) {
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
            // DAT-10: only a *verified* rollback may delete the rollback tree. The old code
            // ignored every rename/copy failure and deleted the directory anyway, so a failed
            // undo destroyed the only copy of the files the still-live `.bak` database points
            // at. When the undo is incomplete both the tree and the journal stay, and the kept
            // path is logged and reported in the exception for a future repair.
            val undone = undoInstall(installed, movedAside, restore)
            if (undone) {
                runCatching { rollbackDir.deleteRecursively() }
                // The undo restored the previous state, so the plan is finished. Deleting last
                // keeps a kill *during* the undo replayable on the next start.
                runCatching { journal.delete() }
            } else {
                // R2-08: this directory now pairs with the `.bak` that is still on disk. A marker
                // makes that durable beyond this process: the next successful install prunes every
                // other rollback round, and without the marker it would delete exactly the files
                // this branch kept for the user.
                runCatching {
                    File(rollbackDir, ROLLBACK_KEEP_MARKER).writeText(
                        "Kept because the rollback of an interrupted attachment install was incomplete.\n"
                    )
                }
                runCatching {
                    Log.e(
                        TAG,
                        "Could not fully undo the attachment install; kept the rollback tree at " +
                            rollbackDir.absolutePath
                    )
                }
            }
            throw IOException(
                if (undone) {
                    "Could not install restored attachments; previous files were put back"
                } else {
                    "Could not install restored attachments and the rollback was incomplete; " +
                        "the previous files were kept in ${rollbackDir.absolutePath}"
                },
                error
            )
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
     * file is left alone - which also makes a second replay a no-op. DAT-05: only if the target
     * **also** exists. With both gone the member was lost before it reached `filesDir`; that is a
     * failure, so the journal is kept for the next start instead of being deleted as if the work
     * had finished.
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
     * @return how many entries were moved by this call; 0 when there was nothing to do. A 0 with
     *   [hasPendingInstall] still true means the replay stopped on an unresolved entry (DAT-05) or
     *   an I/O failure; the journal is kept for the next start.
     */
    fun recoverPendingInstall(context: Context): Int {
        val workDir = workDirectoryPath(context)
        // R3-05: the hash is only meaningful when a plan exists, and hashing a large live database
        // on every launch is pure startup cost. The `isFile` check is the normal-launch path.
        if (!installJournal(workDir).isFile) return 0
        val liveDatabase = context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)
        return recoverPendingInstall(
            workDir,
            liveDatabaseHash = sha256File(liveDatabase),
            liveDatabaseToken = InstallGeneration.read(liveDatabase),
        ) { target ->
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
     * DAT-05/SEC-11: removes per-operation staging trees a killed restore abandoned.
     *
     * A staging directory is plaintext (the database copy and the extracted attachment bytes before
     * they are installed), so one left by a process kill must not survive forever. Two guards keep
     * this safe:
     *  - a directory referenced by the still-pending install journal is kept - a paused replay
     *    needs those exact sources;
     *  - only directories untouched for [STAGING_STALE_MILLIS] are removed, so a restore that
     *    WorkManager starts moments after `Application.onCreate` cannot lose its fresh tree.
     *
     * The durable journal, rollback and recovery directories are never touched.
     *
     * @return how many abandoned directories were removed.
     */
    fun cleanupAbandonedStaging(
        context: Context,
        now: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = STAGING_STALE_MILLIS,
        protectedDirectories: Set<String> = emptySet(),
    ): Int = cleanupAbandonedStaging(
        stagingRoot = stagingRoot(context),
        installJournal = installJournal(workDirectoryPath(context)),
        now = now,
        maxAgeMillis = maxAgeMillis,
        protectedDirectories = protectedDirectories,
    )

    /** File seam for [cleanupAbandonedStaging]; kept internal so tests need no Context. */
    internal fun cleanupAbandonedStaging(
        stagingRoot: File,
        installJournal: File?,
        now: Long,
        maxAgeMillis: Long,
        protectedDirectories: Set<String> = emptySet(),
    ): Int {
        if (!stagingRoot.isDirectory) return 0
        val referenced = referencedStagingDirectories(stagingRoot, installJournal) +
            protectedDirectories
        var removed = 0
        stagingRoot.listFiles().orEmpty().forEach { entry ->
            if (!entry.isDirectory) return@forEach
            val canonical = runCatching { entry.canonicalPath }.getOrNull() ?: return@forEach
            if (canonical in referenced) return@forEach
            if (now - entry.lastModified() < maxAgeMillis) return@forEach
            if (runCatching { entry.deleteRecursively() }.getOrDefault(false)) {
                removed++
            } else {
                Log.w(TAG, "Could not remove abandoned staging directory ${entry.name}")
            }
        }
        return removed
    }

    /**
     * DAT-10/REG-04: removes one finished operation's staging tree, unless the install journal
     * still names it.
     *
     * [install] keeps its journal when the rollback itself failed, and the replay at the next
     * startup needs the sources that were not installed yet. Deleting the whole operation here
     * would turn a repairable state into an unresolved "source missing" journal. Every restore
     * path calls this from its `finally` while still holding the shared restore mutex; successful
     * installs already deleted the journal, so their tree is removed as before.
     */
    internal fun deleteStagingUnlessReferenced(context: Context, stagingDir: File) {
        val referenced = referencedStagingDirectories(
            stagingRoot(context),
            installJournal(workDirectoryPath(context)),
        )
        val canonical = runCatching { stagingDir.canonicalPath }.getOrNull()
        if (canonical != null && canonical in referenced) {
            Log.w(TAG, "Keeping ${stagingDir.name}: the install journal still references it")
            return
        }
        stagingDir.deleteRecursively()
    }

    /**
     * The per-operation staging directories a pending install journal still names.
     *
     * Both layouts are covered: current journals point at `<staging>/<operation>/attachments/...`
     * and legacy ones at `<staging>/attachments/...`; walking up to the direct child of the
     * staging root keeps exactly the directory that must survive.
     */
    private fun referencedStagingDirectories(stagingRoot: File, installJournal: File?): Set<String> {
        if (installJournal == null || !installJournal.isFile) return emptySet()
        val root = runCatching { stagingRoot.canonicalPath }.getOrNull() ?: return emptySet()
        val entries = readInstallJournal(installJournal)?.entries ?: return emptySet()
        return entries.mapNotNull { (source, _) ->
            val canonical = runCatching { source.canonicalFile }.getOrNull()
                ?: return@mapNotNull null
            generateSequence(canonical.parentFile) { it.parentFile }
                .firstOrNull { it.parentFile?.canonicalPath == root }
                ?.canonicalPath
        }.toSet()
    }

    /**
     * [recoverPendingInstall] over an explicit work directory - the same location [install] uses
     * ([workDirectory]). Kept File-based so the tests never need a real `noBackupFilesDir`.
     */
    internal fun recoverPendingInstall(workDir: File): Int =
        recoverPendingInstall(workDir, null) { }

    /**
     * The File seam with an install hook, kept internal: [onInstalled] receives every attachment
     * target that is in place after a replay (newly moved *and* already installed before the
     * kill), which is how [recoverPendingInstall]'s Context variant re-encrypts without this file
     * I/O having to know about [MediaVault]. The hook is informational for the replay itself: it
     * must not throw, and a throw is not a replay failure.
     */
    internal fun recoverPendingInstall(
        workDir: File,
        liveDatabaseHash: String?,
        liveDatabaseToken: Int? = null,
        onInstalled: (File) -> Unit,
    ): Int {
        val journal = installJournal(workDir)
        if (!journal.isFile) return 0

        val plan = readInstallJournal(journal)
        if (plan == null) {
            // Torn or foreign: it cannot describe a plan, so replaying it could move wrong paths.
            // There is no old state to keep, and leaving it would block every later start.
            Log.w(TAG, "Ignoring unreadable attachment install journal")
            runCatching { journal.delete() }
            return 0
        }
        if (plan.entries.isEmpty()) {
            runCatching { journal.delete() }
            return 0
        }
        // R2-08/P1: the caller rolled the database back to `.bak` after the install failed. The
        // plan was prepared for the *restored* database that is no longer live; replaying it would
        // overwrite the old database's same-named files with the failed archive's bytes. Finish
        // the undo instead: put the displaced originals back where the live `.bak` expects them.
        //
        // R3-01: a journal written by the pre-fix build recorded the hash of the staged database
        // *before* `prepare` rewrote its attachment paths, so a normal forward install can also
        // mismatch. With an empty rollback tree there is nothing to restore and replaying is the
        // only safe direction; only a tree that still holds displaced bytes means the database
        // really went back.
        val plannedHash = plan.databaseHash
        val displacedBytes = plan.rollbackDir?.let(::hasDisplacedFiles) == true
        // R4-03: the hash is the fallback, not the decision. A restored database that a note save
        // (or the startup plaintext sweep) has touched no longer matches the hash recorded here,
        // and treating that as "the caller rolled back to `.bak`" moved old attachment bytes over
        // the files the live rows point at. The stamped generation token survives content writes;
        // only a database that is not this generation is a real rollback. Journals from older
        // builds have no token and keep the byte comparison they were written for.
        val rolledBack = when {
            !displacedBytes -> false
            plan.generationToken != null && liveDatabaseToken != null ->
                plan.generationToken != liveDatabaseToken
            // R5-02: a journal that recorded a token cannot be interpreted without the live
            // token. Falling back to the hash here would compare a pre-stamp hash (older build)
            // with post-stamp bytes and could move the old files over the restored database -
            // the exact overwrite this direction check exists to prevent. Keep the journal and
            // retry on a launch where the database can be identified.
            plan.generationToken != null -> {
                Log.e(
                    TAG,
                    "Install generation could not be read; keeping the journal instead of guessing " +
                        "the replay direction"
                )
                return 0
            }
            plannedHash != null && liveDatabaseHash != null -> plannedHash != liveDatabaseHash
            else -> false
        }
        if (rolledBack) {
            Log.w(
                TAG,
                "Attachment install plan belongs to a database that is no longer live; restoring " +
                    "the displaced files instead of replaying"
            )
            if (restoreRolledBackAttachments(plan)) {
                runCatching { journal.delete() }
            } else {
                // R3-01: a partial restore keeps the journal (and the marked rollback tree), so
                // the next start retries instead of silently leaving the old database with files
                // that are still in the rollback directory.
                Log.e(TAG, "Rollback restore incomplete; keeping the install journal for retry")
            }
            return 0
        }

        val entries = plan.entries

        val recoveryDir = uniqueDirectory(workDir, RECOVERY_PREFIX)
        var installed = 0
        var unresolved = 0
        try {
            entries.forEach { (source, target) ->
                if (!source.isFile) {
                    // Source gone + target present means the entry landed before the kill; the
                    // live file is authoritative now. DAT-05: with both gone the archive member
                    // never reached `filesDir` - the old "already installed" reading deleted the
                    // journal and left the restored database pointing at nothing.
                    if (target.isFile) {
                        // The hook still runs: a kill between the move and install's encryption
                        // leaves a plaintext target here, and this is the only second chance it gets.
                        runCatching { onInstalled(target) }
                    } else {
                        unresolved++
                        Log.w(TAG, "Neither the staged source nor the live target exists for ${target.name}")
                    }
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

        if (unresolved > 0) {
            // A staged member is missing and its target never landed: this is a failed recovery,
            // not a finished one. Keeping the journal keeps the failure visible (and the D7
            // reconciliation reports the dangling rows) instead of silently declaring success.
            Log.w(
                TAG,
                "Attachment install recovery could not resolve $unresolved entr(ies); keeping the journal"
            )
            return installed
        }

        runCatching { journal.delete() }
        pruneRecoveryDirectories(workDir, keep = recoveryDir.takeIf { it.exists() })
        return installed
    }

    /**
     * R2-08/P1: puts the files an incomplete install displaced back where the live (old) database
     * expects them.
     *
     * Runs only when the journal's database hash no longer matches the live database - the caller
     * rolled back to `.bak` after the install failed. Restoring the rollback tree is the inverse of
     * replaying the plan forward; both the tree and the targets are named by the journal, so this
     * stays a pure file operation.
     */
    private fun restoreRolledBackAttachments(plan: InstallPlan): Boolean {
        val rollbackDir = plan.rollbackDir ?: return true
        if (!rollbackDir.isDirectory) return true
        val savedFiles = rollbackFiles(rollbackDir)
        if (savedFiles.isEmpty()) {
            // Only the marker (or nothing at all) is left: a previous attempt finished the restore
            // but the journal deletion raced it. Nothing pairs with `.bak` here any more.
            runCatching { File(rollbackDir, ROLLBACK_KEEP_MARKER).delete() }
            runCatching { rollbackDir.deleteRecursively() }
            return true
        }
        // R5-06: mark before the first move, not only after a failure. A marker written up front
        // protects the tree from a later install's prune if this process dies mid-restore, and a
        // marker that cannot be written aborts before a single live file changes. The old order
        // swallowed the write failure, so the next successful install could delete the only copy
        // of the bytes this database still points at.
        val marker = File(rollbackDir, ROLLBACK_KEEP_MARKER)
        val marked = runCatching {
            marker.writeText(
                "Kept because restoring the previous database's attachments was incomplete.\n"
            )
        }.isSuccess || marker.isFile
        if (!marked) {
            Log.e(
                TAG,
                "Could not mark the rollback tree at ${rollbackDir.absolutePath}; keeping the " +
                    "journal instead of restoring without protection"
            )
            return false
        }

        val displaced = plan.entries.filter { (_, target) ->
            savedFiles.any { it.name == target.name || it.name.startsWith(target.name + ".") }
        }
        displaced.forEach { (_, target) ->
            val saved = savedFiles
                .filter { it.name == target.name || it.name.startsWith(target.name + ".") }
                .maxByOrNull { it.lastModified() }
                ?: return@forEach
            target.parentFile?.mkdirs()
            runCatching {
                if (target.exists() && !target.delete()) return@runCatching false
                restoreSavedFile(saved, target)
            }.getOrDefault(false)
        }
        // R3-01: completeness is "the rollback tree holds no displaced file", not "every plan
        // target exists" - entries that never displaced a file are not this restore's
        // responsibility. R4-02: the keep-marker is bookkeeping, never a displaced attachment;
        // counting it made a fully restored tree look incomplete forever.
        val remaining = rollbackFiles(rollbackDir)
        if (remaining.isEmpty()) {
            // Nothing is left to pair with `.bak`, so both the marker and the directory go.
            runCatching { marker.delete() }
            runCatching { rollbackDir.deleteRecursively() }
            return true
        }
        Log.w(
            TAG,
            "${remaining.size} displaced attachment(s) could not be put back; keeping " +
                rollbackDir.absolutePath
        )
        return false
    }

    /**
     * R4-02: the displaced attachments a rollback directory really holds.
     *
     * [ROLLBACK_KEEP_MARKER] is a durable "do not prune this tree" note, not a file belonging to
     * the old database. Every completeness/counting decision must exclude it.
     */
    private fun rollbackFiles(directory: File): List<File> =
        directory.listFiles().orEmpty().filter { it.isFile && it.name != ROLLBACK_KEEP_MARKER }

    private fun hasDisplacedFiles(directory: File): Boolean =
        directory.listFiles().orEmpty().any { it.isFile && it.name != ROLLBACK_KEEP_MARKER }

    /** R2-08/P1: SHA-256 of the live database, matching [RestoreJournal]'s journal hash format. */
    private fun sha256File(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * The journal location inside the durable work tree. Used to be `cacheDir`; that tree is
     * evictable by the OS, which is precisely what V-8 must survive.
     */
    internal fun installJournal(workDir: File): File = File(workDir, INSTALL_JOURNAL_NAME)

    /**
     * R3-01 test seam: the database hash the current install plan was recorded with.
     *
     * Production callers no longer supply it; [install] hashes the live database after the swap
     * and after [prepare] rewrote the staged copy, which is the file startup will later compare.
     */
    internal fun installJournalDatabaseHash(workDir: File): String? =
        readInstallJournal(installJournal(workDir))?.databaseHash

    /**
     * Writes the complete install plan with a magic/version header and `writeUTF` paths (no
     * delimiter to escape), fsyncs the bytes, and only then renames the `.part` sibling over the
     * real name. A kill during the write therefore leaves a `.part` file that recovery ignores,
     * never a truncated plan that could point at the wrong target.
     */
    internal fun writeInstallJournal(
        workDir: File,
        entries: List<Pair<File, File>>,
        databaseHash: String? = null,
        rollbackDir: File? = null,
        generationToken: Int? = null,
    ): File {
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
                    output.writeUTF(databaseHash.orEmpty())
                    output.writeUTF(rollbackDir?.absolutePath.orEmpty())
                    output.writeInt(generationToken ?: NO_GENERATION_TOKEN)
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

    /** R2-08/P1: a parsed journal; the two v2 fields are null in a legacy (v1) file. */
    private data class InstallPlan(
        val entries: List<Pair<File, File>>,
        val databaseHash: String?,
        val rollbackDir: File?,
        /** R4-03: the generation token stamped into the database this plan belongs to. */
        val generationToken: Int?,
    )

    /** Null when the journal is torn, foreign, or from another version; never partially parsed. */
    private fun readInstallJournal(journal: File): InstallPlan? =
        runCatching<InstallPlan?> {
            DataInputStream(BufferedInputStream(FileInputStream(journal))).use { input ->
                if (input.readInt() != JOURNAL_MAGIC) return@runCatching null
                val version = input.readInt()
                if (version !in JOURNAL_VERSION_MIN..JOURNAL_VERSION) return@runCatching null
                val count = input.readInt()
                if (count < 0 || count > JOURNAL_MAX_ENTRIES) return@runCatching null
                val entries = ArrayList<Pair<File, File>>(count)
                repeat(count) {
                    entries += File(input.readUTF()) to File(input.readUTF())
                }
                val databaseHash = if (version >= 2) {
                    input.readUTF().takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                val rollbackDir = if (version >= 2) {
                    input.readUTF().takeIf { it.isNotEmpty() }?.let(::File)
                } else {
                    null
                }
                val generationToken = if (version >= 3) {
                    input.readInt().takeIf { it != NO_GENERATION_TOKEN }
                } else {
                    null
                }
                InstallPlan(entries, databaseHash, rollbackDir, generationToken)
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

    /**
     * Reverses [install]'s work in reverse order: drop new files, then put saved ones back.
     *
     * DAT-10: returns false when **any** member could not be restored. A false result tells the
     * caller to keep the rollback directory (and the install journal): deleting them after a
     * partial undo would destroy the only remaining copy of files the previous database points
     * at. [restore] is injectable for the failure test; production uses the rename/copy fallback.
     */
    internal fun undoInstall(
        installed: List<File>,
        movedAside: List<Pair<File, File>>,
        restore: ((saved: File, target: File) -> Boolean)? = null,
    ): Boolean {
        var complete = true
        installed.asReversed().forEach { file ->
            if (file.exists() && !runCatching { file.delete() }.getOrDefault(false)) {
                complete = false
            }
        }
        movedAside.asReversed().forEach { (target, saved) ->
            val restored = runCatching {
                target.parentFile?.mkdirs()
                if (target.exists() && !target.delete()) return@runCatching false
                restore?.invoke(saved, target) ?: restoreSavedFile(saved, target)
            }.getOrDefault(false)
            if (!restored) complete = false
        }
        return complete
    }

    /** Put [saved] back at [target]: rename first, then a copy for a cross-volume fallback. */
    private fun restoreSavedFile(saved: File, target: File): Boolean {
        if (saved.renameTo(target)) return true
        return runCatching {
            saved.copyTo(target, overwrite = true)
            target.isFile
        }.getOrDefault(false)
    }

    /**
     * Keeps only [keep] under the work tree's rollback directories; older rounds belong to a
     * `.bak` that the next database swap has already replaced.
     */
    private fun pruneRollbackDirectories(workDir: File, keep: File?) {
        // R4-02: a pending plan's journal names the directory its entries displaced files into.
        // That tree must survive until the replay or its rollback finished - the marker an
        // incomplete undo writes only exists after the fact.
        val journalRollback = readInstallJournal(installJournal(workDir))?.rollbackDir
        workDir.listFiles { file ->
            file.isDirectory && file.name.startsWith(ROLLBACK_PREFIX)
        }.orEmpty()
            .filter { it.name != keep?.name }
            .filter { dir ->
                journalRollback == null || dir.absolutePath != journalRollback.absolutePath
            }
            .forEach { dir ->
                // R2-08: a directory marked by an incomplete undo is still the only copy of files
                // the live `.bak` database points at. Rotating it away here silently destroyed
                // them. R4-02: a marker with no displaced file left is stale bookkeeping - every
                // file was put back and only the completion raced the marker write - so the empty
                // tree is reclaimed instead of blocking the next rotation forever.
                if (File(dir, ROLLBACK_KEEP_MARKER).isFile && hasDisplacedFiles(dir)) return@forEach
                runCatching { dir.deleteRecursively() }
            }
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

/**
 * R4-03: the immutable identity of the database generation an install plan belongs to.
 *
 * The journal used to decide "forward replay vs. restore the rollback tree" by comparing the live
 * database's SHA-256 with the hash recorded at install time. That hash changes on the next note
 * save and during the startup plaintext sweep, so a perfectly restored database could look like a
 * rolled-back one - and recovery then moved the old attachment bytes over the files the live rows
 * reference.
 *
 * SQLite's `application_id` header field is read by neither SQLite nor Room during normal use. It
 * survives every content write, every WAL checkpoint and `VACUUM`, and it vanishes with the file -
 * exactly the lifetime of the plan. Stamping a random token there at install time gives recovery a
 * signal that content writes cannot forge: the restored database keeps the token, a `.bak` that was
 * swapped back in does not.
 */
internal object InstallGeneration {

    /** SQLite's fixed 16-byte file header. */
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * Writes a fresh token into [databaseFile] and returns it; null when the file is not a usable
     * SQLite database or cannot be opened read-write (the journal then records no token and
     * recovery falls back to the byte comparison).
     */
    fun stamp(databaseFile: File): Int? {
        if (!isSqliteDatabase(databaseFile)) return null
        val token = SecureRandom().nextInt().let { if (it == 0) 1 else it }
        return runCatching {
            openWithoutDeleting(databaseFile, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("PRAGMA application_id = $token")
                // R6-03: do not report a stamp that did not persist. A read-back failure keeps the
                // journal tokenless so the old hash comparison remains the only authority.
                val written = db.rawQuery("PRAGMA application_id", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                if (written != token) return@runCatching null
            }
            token
        }.getOrNull()
    }

    /** The token currently stamped in [databaseFile], or null when it cannot be read. */
    fun read(databaseFile: File): Int? {
        if (!isSqliteDatabase(databaseFile)) return null
        return runCatching {
            openWithoutDeleting(databaseFile, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA application_id", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else null
                }
            }
        }.getOrNull()
    }

    /**
     * R4-03/R5-01: a file that is not SQLite at all must not be handed to the database opener.
     *
     * Android's [SQLiteDatabase] installs `DefaultDatabaseErrorHandler`, whose corruption callback
     * deletes the database file. Asking it to open a torn or foreign file could therefore destroy
     * the very database recovery is trying to identify - and in the tests the live database is a
     * small fixture. The header check is also what makes the no-op handler below safe.
     */
    internal fun isSqliteDatabase(file: File): Boolean = runCatching {
        if (file.length() < SQLITE_HEADER.size) return false
        val header = ByteArray(SQLITE_HEADER.size)
        file.inputStream().use { stream ->
            var read = 0
            while (read < header.size) {
                val chunk = stream.read(header, read, header.size - read)
                if (chunk < 0) return false
                read += chunk
            }
        }
        header.contentEquals(SQLITE_HEADER)
    }.getOrDefault(false)

    /**
     * Opens the database with an error handler that never deletes it. A corruption discovered
     * here only fails the read/write; destroying the bytes would turn a recoverable problem into
     * data loss.
     */
    private fun openWithoutDeleting(file: File, flags: Int): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, flags) { }
}

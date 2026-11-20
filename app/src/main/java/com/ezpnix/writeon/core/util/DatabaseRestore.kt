package com.ezpnix.writeon.core.util

import android.util.Log
import com.ezpnix.writeon.core.constant.DatabaseConst
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Helpers shared by every "replace the live database from an archive" path.
 *
 * Both call sites previously deleted the database and then streamed a fresh copy into place. Any
 * interruption between the two steps - a truncated archive, a full disk, the process being killed -
 * left the user with no notes at all, and neither path kept the old database around to fall back
 * on. [replaceDatabaseAtomically] makes the swap a single rename instead, and keeps the previous
 * database as `.bak` until the caller confirms the restore worked.
 */
object DatabaseRestore {

    private const val TAG = "DatabaseRestore"

    /** Staged copy of the live database; renamed over it only after it has been fully written. */
    private const val STAGING_SUFFIX = ".new"

    /** Previous database, kept so a failed restore can still be undone by hand. */
    private const val BACKUP_SUFFIX = ".bak"

    /** Copy of `.bak` used by [rollbackFromBackup]; never written over the backup itself. */
    private const val ROLLBACK_SUFFIX = ".rollback"

    /** `.bak` next to a live database [target]; the one file [RestoreSnapshots] exposes. */
    fun backupFile(target: File): File = File(target.absolutePath + BACKUP_SUFFIX)

    /**
     * Upper bound for one archive member. A notes database is comfortably under a few megabytes, so
     * this only ever trips on a hostile or corrupt archive - which is exactly when we want to stop
     * reading rather than fill the cache directory.
     */
    const val MAX_ENTRY_BYTES: Long = 64L * 1024 * 1024

    /**
     * Upper bound for everything an archive extracts, combined. Notes plus attachments (recordings
     * dominate) can legitimately reach hundreds of megabytes, but an unbounded total lets one
     * hostile or corrupt archive fill the cache partition and take the whole app down with it.
     */
    const val MAX_TOTAL_BYTES: Long = 2L * 1024 * 1024 * 1024

    /**
     * Streams every member of [zip] into [stagingDir] and returns the extracted database.
     *
     * The backup manifest entry (when present) is skipped: it describes the archive rather than
     * belonging to it, and legacy archives without one extract exactly the same way.
     *
     * This replaces "read the whole archive into a ByteArray, write it to a temp file, then
     * extract": once attachments travel with the backup, the archive is as large as the user's
     * media and that model held several full copies in memory at once. Entry names are resolved
     * through [resolveEntry], so a zip-slip member is refused rather than written.
     *
     * @throws IOException if [databaseEntryName] is missing, or a member escapes [stagingDir] or
     *   exceeds the per-entry / total size budgets.
     */
    fun extractAll(zip: ZipInputStream, stagingDir: File, databaseEntryName: String): File {
        var restored: File? = null
        var total = 0L
        var entry = zip.nextEntry
        while (entry != null) {
            // The archive manifest (W6) is metadata, not payload: callers read it through
            // BackupArchiveCrypto.readManifest and it must never land in the staging directory
            // (or be mistaken for content by a restore path that only looks at entry order).
            if (entry.name == BackupArchiveCrypto.MANIFEST_ENTRY_NAME) {
                zip.closeEntry()
                entry = zip.nextEntry
                continue
            }
            if (!entry.isDirectory) {
                val target = resolveEntry(stagingDir, entry.name)
                val written = extractEntry(zip, target)
                total += written
                if (total > MAX_TOTAL_BYTES) {
                    throw IOException("Archive exceeds the ${MAX_TOTAL_BYTES} byte extraction budget")
                }
                if (entry.name == databaseEntryName) restored = target
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        return restored ?: throw IOException("Archive does not contain $databaseEntryName")
    }

    /**
     * Resolves [entryName] inside [destinationDir] and refuses anything that would escape it.
     *
     * [ZipInputStream] hands back the raw name from the archive, so an entry called
     * `../../databases/note-list.db` would otherwise be written wherever the process can reach. The
     * canonical paths are compared rather than the raw strings so that `..` segments, `.` segments
     * and redundant separators are all resolved before the check.
     *
     * @throws IOException if the entry name is absolute, or resolves outside [destinationDir].
     */
    fun resolveEntry(destinationDir: File, entryName: String): File {
        val root = destinationDir.canonicalFile

        // Reject absolute names before constructing the child. `File(root, "/tmp/outside")` does
        // not escape on Unix - it becomes `<root>/tmp/outside` - so relying on canonicalisation
        // alone let an absolute zip member through on CI even though the Windows test caught it.
        if (entryName.startsWith('/') || entryName.startsWith('\\') || File(entryName).isAbsolute) {
            throw IOException("Archive entry is absolute and cannot be extracted: $entryName")
        }

        val candidate = File(root, entryName).canonicalFile

        // canonicalPath on Windows is case-preserving but comparisons are case-insensitive, and a
        // sibling directory such as `unzipped_restore_evil` shares a string prefix with the root.
        val insideRoot = candidate.path.equals(root.path, ignoreCase = true) ||
            candidate.path.startsWith(root.path + File.separator, ignoreCase = true)

        if (!insideRoot) {
            throw IOException("Archive entry escapes the extraction directory: $entryName")
        }
        return candidate
    }

    /**
     * Extracts one entry from [source] into [targetFile], refusing to write more than
     * [MAX_ENTRY_BYTES] and making sure the bytes reached the disk before returning.
     *
     * @return the number of bytes written.
     */
    fun extractEntry(source: ZipInputStream, targetFile: File): Long {
        targetFile.parentFile?.mkdirs()
        var written = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        FileOutputStream(targetFile).use { output ->
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                written += read
                if (written > MAX_ENTRY_BYTES) {
                    throw IOException("Archive entry exceeds ${MAX_ENTRY_BYTES} bytes: ${targetFile.name}")
                }
                output.write(buffer, 0, read)
            }
            output.fd.sync()
        }
        return written
    }

    /**
     * Swaps [replacement] in for [target] without ever leaving the app without a readable database.
     *
     * The sequence is: stage next to the target, verify the staged file opens as a SQLite database
     * and passes the D8 compatibility gate ([assertArchiveCompatible]), copy the old database to
     * `.bak`, **delete the old `-wal`/`-shm`**, replace the target with a **single atomic move**,
     * delete any sidecar that reappeared, then run `quick_check` on the installed file and roll
     * back from `.bak` if it fails (DAT-5). The target therefore exists at every instant of the
     * operation: a crash can leave "old database" or "old database + `.bak` + new database", but
     * never "no database" - the window the previous two-rename sequence had (rename #1
     * target→`.bak`, rename #2 staged→target) is gone, because the old file is copied aside
     * instead of renamed away (W14).
     *
     * Sidecar ordering deserves its own note: SQLite replays `-wal` pages into whatever file sits
     * at the database path. Deleting it after the move (the old order) meant a crash in between
     * replayed the previous database's pages into the restored one, which no later check could
     * undo. Callers close Room first, then this function removes both sidecars *before* the move
     * and again afterwards in case something recreated them.
     *
     * `.bak` retention: exactly **one** backup is ever kept, and the next restore overwrites it.
     * That rotation is the cleanup outlet (W14) - the file can neither pile up nor disappear
     * before it has served its purpose. It is deliberately *not* deleted after a successful swap:
     * it is the undo for "restored the wrong backup", exposed by [RestoreSnapshots.restorePrevious]
     * (DAT-1).
     *
     * `.bak` is only the *default* destination; the overload below takes the file explicitly, so
     * the undo - which reads `.bak` - can park the replaced database elsewhere and rotate it in
     * after a successful install (V-4).
     *
     * @return the backup file holding the previous database, for a caller that wants to delete it
     *   once the restored database has proven usable.
     */
    fun replaceDatabaseAtomically(target: File, replacement: File): File? =
        replaceDatabaseAtomically(target, replacement, backupFile(target))

    /**
     * [replaceDatabaseAtomically] with the previous database parked at [backupDestination].
     *
     * Every restore path except the DAT-1 undo wants the default `.bak`: it is the one file
     * [RestoreSnapshots.info] exposes and the next restore rotates. That default is exactly wrong
     * for the undo itself, because there the snapshot *is* `.bak` - copying the current database
     * over it would consume the only copy before the replacement is installed, so a failure after
     * that copy could not be retried (V-4). [RestoreSnapshots.swapInPrevious] passes a separate
     * destination and publishes it over `.bak` only after the install has fully succeeded.
     *
     * @return [backupDestination] when it now holds the previous database, or null when there was
     *   no previous database to keep.
     */
    internal fun replaceDatabaseAtomically(
        target: File,
        replacement: File,
        backupDestination: File,
    ): File? = replaceDatabaseAtomically(target, replacement, backupDestination) { staged, installTarget ->
        // One atomic replacement: POSIX rename(2) over an existing target swaps the file in a
        // single step, and java.nio mirrors that on every platform this code reaches (verified
        // on the Windows JVM the unit tests run on, where File.renameTo refuses to overwrite
        // and would leave the restore failing forever).
        Files.move(
            staged.toPath(),
            installTarget.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    /**
     * [replaceDatabaseAtomically] with the final install move supplied by the caller.
     *
     * Production always uses the default move. This exists because the V-4 failure cannot be
     * produced any other way: the damage would happen *after* the live database has been copied
     * over the backup destination, so an injected failure has to land exactly on the move that
     * follows it. [RestoreSnapshotsTest] passes a move that throws and then asserts the snapshot
     * survived.
     */
    internal fun replaceDatabaseAtomically(
        target: File,
        replacement: File,
        backupDestination: File,
        move: (staged: File, target: File) -> Unit,
    ): File? {
        target.parentFile?.mkdirs()

        val staged = File(target.absolutePath + STAGING_SUFFIX)
        val backup = backupDestination
        // A caller-supplied destination may point outside the target's directory (it does not
        // today, but the parameter is file-shaped rather than a suffix).
        backup.parentFile?.mkdirs()

        // Stage on the same filesystem as the target so the final step is a rename, not a copy.
        staged.delete()
        replacement.copyTo(staged, overwrite = true)
        // `FileOutputStream(file)` opens with O_TRUNC: using it only to reach fd.sync() emptied the
        // freshly staged database, so the header check below could never pass and every restore
        // failed with "Archive does not contain a readable notes database". RandomAccessFile("rw")
        // keeps the bytes and still lets us force them to disk.
        RandomAccessFile(staged, "rw").use { it.fd.sync() }

        try {
            if (!looksLikeSqliteDatabase(staged)) {
                throw IOException("Archive does not contain a readable notes database")
            }
            // D8's last gate: everything before this line only looked at bytes; this refuses a
            // database from a newer app version (the "install a v12 backup on v11 = crash loop
            // on every launch" failure) or one this app's Room schema does not recognise, while
            // the live database is still untouched. Every restore path passes through here.
            assertArchiveCompatible(staged)
        } catch (e: Exception) {
            staged.delete()
            throw e
        }

        val hadPrevious = target.exists()
        if (hadPrevious) {
            try {
                // Copy, do not rename: renaming the live database away is what opened the
                // "crash between the two renames = no note-list.db" window in the first place.
                // The rotation comment on this function explains why the copy is kept.
                target.copyTo(backup, overwrite = true)
                RandomAccessFile(backup, "rw").use { it.fd.sync() }
            } catch (e: Exception) {
                staged.delete()
                throw IOException("Could not keep a backup of the current database", e)
            }
        }

        // DAT-5: the old connection's WAL/SHM describe the *previous* database. They used to be
        // deleted after the move, which left a crash window in which SQLite opened the freshly
        // restored file and replayed the old journal into it - page-level corruption rather than
        // merely stale rows. Callers have already closed Room by contract, so nothing can
        // recreate them; deleting them before the move means the only file SQLite can find
        // afterwards is the one just installed.
        deleteSidecars(target, "before installing the restored database")

        try {
            move(staged, target)
        } catch (e: Exception) {
            // The move is atomic: on failure the target still holds the old database, so there is
            // nothing to roll back - only the staged copy has to go.
            staged.delete()
            throw IOException("Could not install the restored database", e)
        }

        // A process that ignored the "close Room first" contract can recreate a sidecar between
        // the two steps above; clear whatever is next to the new file before it is opened.
        deleteSidecars(target, "after installing the restored database")

        // DAT-5: verify the file that is actually live, not only the staged copy. `quick_check`
        // returning null means Android's SQLite is a stub (plain-JVM unit tests); there is
        // nothing to verify there, and rolling back a byte-identical install would make every
        // JVM test fail. In production SQLite is always present, so `false` is a real failure.
        when (quickCheckOutcome(target)) {
            false -> {
                val rolledBack = rollbackInstall(target, if (hadPrevious) backup else null)
                if (rolledBack) {
                    throw IOException(
                        "The restored database failed its post-install integrity check; " +
                            "the previous database was put back"
                    )
                }
                throw IOException(
                    "The restored database failed its post-install integrity check" +
                        if (hadPrevious) " and the previous database could not be put back" else ""
                )
            }
            // true: verified; null: verification unavailable on this platform (JVM tests).
            else -> Unit
        }

        return if (hadPrevious) backup else null
    }

    /**
     * Deletes `-wal` and `-shm` beside [target], logging rather than throwing.
     *
     * These are the two files SQLite uses to replay committed pages. Leaving them next to a
     * replaced database is corruption, so a failed delete is worth a loud log even though the
     * restore itself has already succeeded.
     */
    private fun deleteSidecars(target: File, whenDescription: String) {
        listOf("-wal", "-shm").forEach { suffix ->
            val sidecar = File(target.absolutePath + suffix)
            if (sidecar.exists() && !sidecar.delete()) {
                // Log is a stub on the plain-JVM test runtime; a failed delete there must not
                // turn into a second exception on top of the one being handled.
                runCatching {
                    Log.w(
                        TAG,
                        "Could not delete ${sidecar.name} $whenDescription; " +
                            "SQLite may replay stale pages into it"
                    )
                }
            }
        }
    }

    /**
     * Put [target] back to the database held in [backup] (`on install failure` and
     * [rollbackFromBackup]).
     *
     * The `.bak` is copied to a temporary name first and *that* is atomically moved over the
     * target: moving the backup itself would make the undo single-use, and copying directly over
     * the live path is not atomic. The backup stays untouched, so a second attempt is still
     * possible.
     */
    private fun rollbackInstall(target: File, backup: File?): Boolean {
        if (backup == null || !backup.isFile || backup.length() == 0L) return false
        val temp = File(target.absolutePath + ROLLBACK_SUFFIX)
        return try {
            backup.copyTo(temp, overwrite = true)
            RandomAccessFile(temp, "rw").use { it.fd.sync() }
            deleteSidecars(target, "before rolling the database back")
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            deleteSidecars(target, "after rolling the database back")
            true
        } catch (e: Exception) {
            temp.delete()
            runCatching { Log.e(TAG, "Could not roll the database back from its .bak snapshot", e) }
            false
        }
    }

    /**
     * Puts the `.bak` beside [target] back in place, keeping the `.bak` itself intact.
     *
     * Used by the restore callers when the database swap succeeded but the attachment install
     * afterwards failed: the live database must go back to the one the still-live attachment
     * files belong to. Returns false when there is no usable backup or the swap itself fails;
     * the caller turns that into a user-visible error.
     */
    fun rollbackFromBackup(target: File): Boolean =
        rollbackInstall(target, backupFile(target))

    /** SQLite files start with the 16-byte ASCII string `SQLite format 3\0`. */
    private fun looksLikeSqliteDatabase(file: File): Boolean {
        // A valid SQLite header is 100 bytes; a 16-byte match alone would let a stub through.
        if (file.length() < SQLITE_HEADER_SIZE) return false
        val header = ByteArray(SQLITE_MAGIC.size)
        val read = file.inputStream().use { stream -> stream.read(header) }
        return read == SQLITE_MAGIC.size && header.contentEquals(SQLITE_MAGIC)
    }

    /**
     * Runs SQLite's own integrity check over [databaseFile].
     *
     * The header check in [replaceDatabaseAtomically] only proves the first 100 bytes are intact; a
     * truncated or corrupt archive passes it easily. `PRAGMA quick_check` walks the actual pages and
     * reports the first structural error, so a database that would open but silently lose rows is
     * rejected before it replaces the live file. Callers do this on the staged copy while the real
     * database is still untouched - a failed check degrades to "nothing changed".
     *
     * @return true when the file is a SQLite database and `quick_check` reports `ok`.
     */
    fun quickCheckPasses(file: File): Boolean = quickCheckOutcome(file) == true

    /**
     * [quickCheckPasses] with "the platform has no SQLite" kept distinct from "the file is bad".
     *
     * `android.jar` on a plain JVM (the unit-test runtime) throws `RuntimeException("Stub!")` from
     * every method, so both a corrupt file and the stub look like an exception. `null` says the
     * check could not run at all; callers like the post-install gate in
     * [replaceDatabaseAtomically] treat that as "nothing to verify" instead of rolling back a
     * byte-identical install. Production always gets `true`/`false`.
     */
    private fun quickCheckOutcome(file: File): Boolean? {
        if (!looksLikeSqliteDatabase(file)) return false
        return try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
                }
            }
        } catch (e: RuntimeException) {
            // Two stub runtimes reach this catch on the JVM:
            //  - the legacy mockable android.jar throws `RuntimeException("Stub!")`;
            //  - the current one throws `RuntimeException("Method ... not mocked.")` unless the
            //    test opts into return-default-values.
            // A real Android SQLite failure is also a RuntimeException (SQLiteException), so only
            // these two stub markers may degrade to "verification unavailable" (null). Treating
            // every RuntimeException as null would silently disable the post-install check in
            // production, where a corrupt database must roll back.
            val message = e.message.orEmpty()
            if (message.startsWith("Stub!") || message.contains("not mocked", ignoreCase = true)) {
                null
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private const val SQLITE_HEADER_SIZE = 100L

    // -----------------------------------------------------------------------------------------
    // D8: is this archive's database something this build may install?
    //
    // `quick_check` only proves the pages are intact; it happily accepts a database written by a
    // *newer* WriteOn (user_version 12+ has no migration back to 11, so Room throws on every
    // launch afterwards - no destructive fallback, no automatic rollback: a permanent crash loop
    // the user can only escape by clearing app data) and it accepts any other app's valid SQLite
    // file. Both are refused here, before the live database is replaced.
    // -----------------------------------------------------------------------------------------

    /**
     * The `identityHash` Room exported for [DatabaseConst.NOTES_DATABASE_VERSION] in
     * `app/schemas/com.ezpnix.writeon.data.local.database.NoteDatabase/<version>.json`.
     *
     * Frozen as a constant because schema JSON is not packaged into the APK. **When the schema
     * version is bumped, update this together with `DatabaseConst.NOTES_DATABASE_VERSION`** -
     * `DatabaseRestoreTest` reads the schema JSON back and fails if the two drift.
     */
    const val CURRENT_IDENTITY_HASH = "8598c93745210e14c65948bf3dd61048"

    /** Why an archive's database must not be installed. Drives the user-facing error string. */
    enum class Incompatibility {
        /** Backup written by a newer app version: no migration exists *backwards*. */
        NEWER_VERSION,

        /** Not a WriteOn/Room database at all (no `room_master_table`, no usable header). */
        NOT_WRITEON_DATABASE,

        /** Same version as this build but a different schema identity: changed without a bump. */
        SCHEMA_MISMATCH,
    }

    /**
     * Thrown by [assertArchiveCompatible] and [checkCompatibility].
     *
     * An [IOException] so every existing restore path - which already funnels failures through
     * `catch (e: IOException)` - keeps working, while callers that care can catch this type first
     * and show a specific message instead of a generic "restore failed" or a pointless retry.
     */
    class IncompatibleArchiveException(
        val incompatibility: Incompatibility,
        message: String,
    ) : IOException(message)

    /** Outcome of reading `identity_hash` out of an archive's `room_master_table`. */
    sealed class IdentityHashRead {
        /** The table exists and carries a hash. */
        data class Found(val hash: String) : IdentityHashRead()

        /** The table (and usually the whole schema) is missing - not a database we can install. */
        object TableMissing : IdentityHashRead()

        /**
         * The file could not be opened for reading at all.
         *
         * Readability is [quickCheckPasses]' job and callers run it first; a plain JVM unit test
         * has no SQLite to open either. Skipping the metadata comparison in this state keeps the
         * decision function testable and never *rejects* a file another check already vetted.
         */
        object Unreadable : IdentityHashRead()
    }

    /** Byte offset of SQLite's big-endian `user_version` field in the 100-byte file header. */
    private const val USER_VERSION_OFFSET = 60L

    /**
     * Reads the `user_version` straight from the SQLite header of [file] (pure `java.io`, so it
     * works in JVM unit tests), or null when the file is too short to hold a header.
     */
    fun readUserVersion(file: File): Int? {
        if (file.length() < SQLITE_HEADER_SIZE) return null
        return try {
            RandomAccessFile(file, "r").use { handle ->
                handle.seek(USER_VERSION_OFFSET)
                handle.readInt()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads `identity_hash` from the archive's `room_master_table`, read-only.
     *
     * A database without that table is not a Room database this app can open, so keeping
     * "missing" distinct from "unreadable" lets us reject the former while leaving the latter to
     * [quickCheckPasses].
     */
    fun readIdentityHash(file: File): IdentityHashRead {
        val database = try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
        } catch (e: Exception) {
            return IdentityHashRead.Unreadable
        }
        return try {
            database.use { open ->
                open.rawQuery("SELECT identity_hash FROM room_master_table", null).use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) {
                        IdentityHashRead.Found(cursor.getString(0))
                    } else {
                        IdentityHashRead.TableMissing
                    }
                }
            }
        } catch (e: Exception) {
            // "no such table: room_master_table" and friends all mean: not our schema.
            IdentityHashRead.TableMissing
        }
    }

    /**
     * The D8 rule itself - a pure decision over two values, so every branch is unit-testable on
     * the JVM without Android's SQLite.
     *
     * Rules, in order:
     * 1. no readable header → not a WriteOn database;
     * 2. `user_version` above this build → [Incompatibility.NEWER_VERSION] (the crash loop);
     * 3. metadata unreadable → accept; [quickCheckPasses] owns readability (see [IdentityHashRead]);
     * 4. `room_master_table` missing → not a WriteOn database;
     * 5. `user_version < 1` (only reachable when the table *was* readable) → not a WriteOn
     *    database: Room has always written the version it migrated to;
     * 6. same version as this build but a different hash → [Incompatibility.SCHEMA_MISMATCH];
     * 7. an **older** version with its own (different) hash is accepted on purpose: that is what
     *    the registered 1→11 migration chain exists for, and Room rewrites the hash after it runs.
     *
     * @throws IncompatibleArchiveException when the archive must not be installed.
     */
    fun checkCompatibility(
        userVersion: Int?,
        identityHash: IdentityHashRead,
        currentVersion: Int = DatabaseConst.NOTES_DATABASE_VERSION,
        expectedIdentityHash: String = CURRENT_IDENTITY_HASH,
    ) {
        if (userVersion == null) {
            throw IncompatibleArchiveException(
                Incompatibility.NOT_WRITEON_DATABASE,
                "Archive database has no readable SQLite header"
            )
        }
        if (userVersion > currentVersion) {
            throw IncompatibleArchiveException(
                Incompatibility.NEWER_VERSION,
                "Archive database is schema version $userVersion; this build understands up to $currentVersion"
            )
        }
        when (identityHash) {
            IdentityHashRead.Unreadable -> return
            IdentityHashRead.TableMissing -> throw IncompatibleArchiveException(
                Incompatibility.NOT_WRITEON_DATABASE,
                "Archive database has no room_master_table"
            )
            is IdentityHashRead.Found -> {
                if (userVersion < 1) {
                    throw IncompatibleArchiveException(
                        Incompatibility.NOT_WRITEON_DATABASE,
                        "Archive database reports user_version $userVersion"
                    )
                }
                if (userVersion == currentVersion && identityHash.hash != expectedIdentityHash) {
                    throw IncompatibleArchiveException(
                        Incompatibility.SCHEMA_MISMATCH,
                        "Archive database identity ${identityHash.hash} does not match this " +
                            "build's $expectedIdentityHash at version $currentVersion"
                    )
                }
            }
        }
    }

    /**
     * D8 gate for one file: header version first (works even when the file cannot be opened),
     * then the full rule from [checkCompatibility].
     *
     * Callers run it right next to [quickCheckPasses], before anything is moved, replaced or
     * rewritten; [replaceDatabaseAtomically] runs it again as the last gate before the live
     * database is touched, so a path that forgets its own check still cannot install an
     * incompatible database.
     *
     * @throws IncompatibleArchiveException when the archive must not be installed.
     */
    fun assertArchiveCompatible(file: File) {
        val userVersion = readUserVersion(file)
        // Fast path: a newer schema is refused from the 100-byte header alone, without opening
        // the file - the primary protection has no dependency on Android's SQLite at all.
        if (userVersion != null && userVersion > DatabaseConst.NOTES_DATABASE_VERSION) {
            throw IncompatibleArchiveException(
                Incompatibility.NEWER_VERSION,
                "Archive database is schema version $userVersion; this build understands up to " +
                    "${DatabaseConst.NOTES_DATABASE_VERSION}"
            )
        }
        checkCompatibility(userVersion, readIdentityHash(file))
    }
}

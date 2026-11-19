package com.ezpnix.writeon.core.util

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

    /** Staged copy of the live database; renamed over it only after it has been fully written. */
    private const val STAGING_SUFFIX = ".new"

    /** Previous database, kept so a failed restore can still be undone by hand. */
    private const val BACKUP_SUFFIX = ".bak"

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
     * `.bak`, then replace the target with a **single atomic move**. The target therefore exists at
     * every instant of the operation: a crash can leave "old database" or "old database + `.bak` +
     * new database", but never "no database" - the window the previous two-rename sequence had
     * (rename #1 target→`.bak`, rename #2 staged→target) is gone, because the old file is copied
     * aside instead of renamed away (W14).
     *
     * `.bak` retention: exactly **one** backup is ever kept, and the next restore overwrites it.
     * That rotation is the cleanup outlet (W14) - the file can neither pile up nor disappear
     * before it has served its purpose. It is deliberately *not* deleted after a successful swap:
     * nothing verifies the installed database yet, so the `.bak` is the only undo for "restored
     * the wrong backup". Consuming it earlier (startup auto-rollback when the main file is
     * missing, or a "restore from .bak" entry in the UI) needs a caller outside this file and
     * stays open work.
     *
     * @return the backup file holding the previous database, for a caller that wants to delete it
     *   once the restored database has proven usable.
     */
    fun replaceDatabaseAtomically(target: File, replacement: File): File? {
        target.parentFile?.mkdirs()

        val staged = File(target.absolutePath + STAGING_SUFFIX)
        val backup = File(target.absolutePath + BACKUP_SUFFIX)

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

        try {
            // One atomic replacement: POSIX rename(2) over an existing target swaps the file in a
            // single step, and java.nio mirrors that on every platform this code reaches (verified
            // on the Windows JVM the unit tests run on, where File.renameTo refuses to overwrite
            // and would leave the restore failing forever).
            Files.move(
                staged.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            // The move is atomic: on failure the target still holds the old database, so there is
            // nothing to roll back - only the staged copy has to go.
            staged.delete()
            throw IOException("Could not install the restored database", e)
        }

        // The old handle's WAL still describes the previous database; leaving it behind would let
        // SQLite replay stale pages over the freshly restored file.
        File(target.absolutePath + "-wal").delete()
        File(target.absolutePath + "-shm").delete()

        return if (hadPrevious) backup else null
    }

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
    fun quickCheckPasses(file: File): Boolean {
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

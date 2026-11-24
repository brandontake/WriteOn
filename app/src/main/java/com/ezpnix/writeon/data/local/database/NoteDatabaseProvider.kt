package com.ezpnix.writeon.data.local.database

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.database.Converters
import com.ezpnix.writeon.data.local.dao.AttachmentDao
import com.ezpnix.writeon.data.local.dao.EncryptedAttachmentDao
import com.ezpnix.writeon.data.local.dao.NoteDao
import com.ezpnix.writeon.domain.model.Attachment
import java.io.File

@TypeConverters(Converters::class)
class NoteDatabaseProvider(private val application: Application) {
    /**
     * The open connection is shared by every provider instance for the process.
     *
     * A per-instance connection meant that `NoteDatabaseProvider(context).close()` - the call a
     * restore makes before swapping the database file - closed a connection nobody was using and
     * left the app's own one open on the file that had just been replaced. Keeping the connection
     * in the companion makes "close the database" mean what it says, whichever instance asks.
     */
    fun instance(): NoteDatabase {
        // Both the open and the close path take the *companion* lock, not the provider instance's
        // monitor. `@Synchronized` on an instance method only serialises calls through that same
        // provider, and Hilt hands one provider to the UI while `BackupDatabase.provider(context)`
        // constructs another for a worker: a worker could close the process-wide connection while
        // the UI's `instance()` was half-way through reopening it. The shared lock is what makes
        // "close the database" mean it for every caller in the process.
        return synchronized(lock) {
            sharedDatabase ?: buildDatabase().also { sharedDatabase = it }
        }
    }

    private fun buildDatabase(): NoteDatabase {
        return Room.databaseBuilder(
            application.applicationContext,
            NoteDatabase::class.java,
            DatabaseConst.NOTES_DATABASE_FILE_NAME
        )
            .addMigrations(
                NoteMigrations.MIGRATION_1_2,
                NoteMigrations.MIGRATION_2_3,
                NoteMigrations.MIGRATION_3_4,
                NoteMigrations.MIGRATION_2_4,
                NoteMigrations.MIGRATION_4_5,
                NoteMigrations.MIGRATION_5_6,
                NoteMigrations.MIGRATION_6_7,
                NoteMigrations.MIGRATION_7_8,
                NoteMigrations.MIGRATION_8_9,
                NoteMigrations.MIGRATION_9_10,
                NoteMigrations.MIGRATION_10_11,
            )
            .build()
    }

    fun close() {
        // Same lock as instance(): a concurrent open must never observe the connection that is
        // being closed, and a close must never clear a connection another thread just opened.
        synchronized(lock) {
            sharedDatabase?.close()
            sharedDatabase = null
        }
    }

    private companion object {
        const val TAG = "NoteDatabaseProvider"

        @Volatile
        var sharedDatabase: NoteDatabase? = null

        val lock = Any()

        /**
         * W12: notes read per page while writing the portable copy.
         *
         * Small enough that a page of long bodies stays well under a megabyte, large enough that a
         * ten-thousand-note library costs two hundred round trips instead of ten thousand.
         */
        const val EXPORT_PAGE_SIZE = 50
    }

    fun noteDao(): NoteDao = encryptedNoteDao()

    /**
     * SEC-5: seals note rows that were stored before this app encrypted text at rest.
     *
     * Exposed here because the encrypting DAO is private: the sweep has to go through the same
     * [NoteCrypto] instance (and its cache) every other read and write uses. Callers treat it as a
     * once-per-process startup job - see `NoteUseCase.observe` - and check [LegacySealResult.failedRows]
     * before recording success.
     */
    suspend fun sealLegacyPlaintextRows(): LegacySealResult = encryptedNoteDao().sealLegacyPlaintextRows()

    private fun encryptedNoteDao(): EncryptedNoteDao {
        // Resolved at failure time so the placeholder follows the active locale; the DAO itself
        // has no Context and must not grow one just for a string.
        return EncryptedNoteDao(instance().noteDao(), noteCrypto) {
            application.getString(R.string.decrypt_failed_placeholder)
        }
    }

    /**
     * SEC-4: attachment captions are user-authored note content and are sealed at rest with the same
     * [NoteCrypto] as the body, while `path` stays readable for the media layer.
     *
     * The wrapper is built around the raw Room DAO on every call, sharing the provider's one
     * [NoteCrypto] instance (and its plaintext cache). Export and backup paths call this DAO too, so
     * they receive decrypted captions: that is intentional - a portable archive is already protected
     * by its own password, and encrypting the copy with a Keystore key that cannot leave this device
     * would make it unreadable after restore.
     */
    fun attachmentDao(): AttachmentDao = EncryptedAttachmentDao(instance().attachmentDao(), noteCrypto)

    /**
     * SEC-4: seals captions that a restore brought back as plaintext, through the same [NoteCrypto]
     * (and cache) every other write uses.
     *
     * Like [sealLegacyPlaintextRows], callers treat it as a once-per-process startup job and check
     * the returned failure count before recording success - see [LegacyCaptionBackfillFlag].
     */
    suspend fun sealLegacyCaptions(): Int =
        EncryptedAttachmentDao(instance().attachmentDao(), noteCrypto).sealLegacyCaptions()

    /**
     * PERF-1: drops the in-memory plaintext cache when the app locks.
     *
     * `NoteCrypto` keeps decrypted bodies so a Room emission does not re-run the Keystore for every
     * unchanged row. Callers that hide the UI behind a lock (or hand the process to the background)
     * call this so the cache cannot outlive the lock screen.
     */
    fun clearCryptoCache() {
        noteCrypto.clearCache()
    }

    /**
     * Writes a copy of the note database in which readable notes are stored as plain text again, so
     * that the file can be restored on another device. The content is read through the normal
     * (encrypting) [noteDao], written into a throwaway database and copied out of the databases
     * directory.
     *
     * DAT-3: a row this process cannot open keeps its original `w1:` ciphertext, and a vault row
     * keeps whichever password-sealed form it has (`v3:` envelope, `v2:` PBKDF2, or legacy).
     * New writes always use `v3:` (PERF-2), but old databases are still readable here. All of
     * these are the only recoverable form of that note; substituting the display placeholder
     * would destroy it on restore.
     *
     * D9: the note rows and the attachment rows are read inside **one** [withTransaction] block, so
     * both queries hit the same connection and therefore the same database snapshot. Read one after
     * the other (the old shape), a save that prunes an attachment row between the two queries
     * exported a body still pointing at a row that was no longer there - and the restored copy kept
     * that dangling reference forever. Room routes every query made from the transaction coroutine
     * onto the transaction's own connection, so no other write can slip in between; the decryption
     * in [EncryptedNoteDao] happens in its map layer, after the row is read, and needs no special
     * handling here.
     *
     * @return the same attachment rows written into [destination]. N-5: backup callers must put
     *   **this** list into the zip instead of re-querying the live table after the transaction.
     *   The second query was outside the snapshot, so a save that pruned an attachment in between
     *   produced an archive with a row but no file (or a file but no row).
     */
    suspend fun writePortableDatabaseCopy(destination: File): List<Attachment> {
        // cacheDir, not getDatabasePath(): the export is a plaintext copy of the whole library, and
        // the databases directory is where a half-finished run used to leave one behind (a killed
        // process skips the finally). cacheDir is excluded from Android's auto backup by default
        // and is cleared when the device runs low on space, so a leaked file is both private and
        // short-lived.
        val exportFile = File.createTempFile("writeon-portable-", ".db", application.applicationContext.cacheDir)

        val exportDatabase = Room.databaseBuilder(
            application.applicationContext,
            NoteDatabase::class.java,
            // An absolute path makes Room create the file exactly where createTempFile made it;
            // with a bare name it would fall back to the databases directory.
            exportFile.absolutePath
        )
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()

        // DAT-3: rows the Keystore cannot open are exported as their original `w1:` ciphertext
        // (see EncryptedNoteDao.getNotesPage), never as the localized placeholder. Count them so
        // the result is visible in logcat; a placeholder here would permanently replace the only
        // copy of the ciphertext once the archive was restored.
        var undecryptableRows = 0
        var vaultRows = 0
        var attachmentRows: List<Attachment> = emptyList()

        try {
            // W12: same single transaction as before (D9's consistent snapshot), but only one page
            // of notes is ever alive. Room routes queries issued from the transaction coroutine
            // onto the transaction's own connection, so every page sees the same snapshot and no
            // save can slip in between; the decryption in [EncryptedNoteDao] happens in its map
            // layer, after each row is read. The portable database is a separate connection, so
            // writing into it from here cannot join or extend the source transaction.
            instance().withTransaction {
                val dao = encryptedNoteDao()
                var afterId = Int.MIN_VALUE
                while (true) {
                    val page = dao.getNotesPageForExport(afterId, EXPORT_PAGE_SIZE)
                    if (page.notes.isEmpty()) break
                    page.notes.forEach { note ->
                        if (note.encrypted) {
                            vaultRows++
                        } else if (note.id in page.undecryptableIds) {
                            undecryptableRows++
                        }
                        exportDatabase.noteDao().addNote(note)
                    }
                    afterId = page.notes.last().id
                }
                // 2.0 bodies only store attachment ids, so the exported file must carry the
                // id -> path map too, otherwise a restored note cannot resolve a single image. The
                // paths themselves are only meaningful on this device; see STATUS.md for the
                // cross-device caveat. Attachment rows hold no body, so they stay in one query.
                attachmentRows = attachmentDao().getAll()
                if (attachmentRows.isNotEmpty()) exportDatabase.attachmentDao().upsert(attachmentRows)
            }
        } finally {
            exportDatabase.close()
        }

        if (undecryptableRows > 0 || vaultRows > 0) {
            Log.w(
                TAG,
                "Portable export kept $undecryptableRows unreadable rows as their original " +
                    "`w1:` ciphertext and $vaultRows vault rows password-sealed; no placeholder " +
                    "text was written"
            )
        }

        try {
            exportFile.copyTo(destination, overwrite = true)
        } finally {
            exportFile.delete()
        }
        return attachmentRows
    }

    private val noteCrypto by lazy { NoteCrypto() }
}

/**
 * Every schema step the app can upgrade along, in one place.
 *
 * These used to be private top-level values in this file. They moved into an object so the
 * migration test (`NoteDatabaseMigrationTest`) drives the exact objects production registers
 * instead of re-declaring copies that could drift from the shipped migrations. The provider is
 * the only consumer besides that test, and the behaviour is unchanged: same objects, same SQL.
 */
internal object NoteMigrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // The default must be a constant. This used to interpolate System.currentTimeMillis(),
            // so the DDL differed per migration run: every pre-v2 note came back with created_at set
            // to the moment its owner upgraded, which silently re-sorted the whole library and
            // destroyed the original ordering with no way to recover it. SQLite requires a constant
            // DEFAULT in ALTER TABLE ADD COLUMN, so a fixed sentinel is the only option here.
            //
            // 0 is treated as "unknown" downstream: NoteUseCase replaces a 0 createdAt with
            // System.currentTimeMillis() the next time the note is saved, so a note only keeps the
            // sentinel if it is never edited again.
            db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `encrypted` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_2_4 = object : Migration(2, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `encrypted` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `trashed` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `tags` TEXT NOT NULL DEFAULT '[]'")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * The 7 -> 8 body rewrite itself lives in [LegacyBodyRewriteMigration]; the instance here is
     * what production installs. The test constructs its own copy with a Keystore-free cipher.
     */
    val MIGRATION_7_8 = LegacyBodyRewriteMigration()

    /**
     * 2.0.2 gives attachments a recycle bin of their own: deleting one from the library, from the
     * editor or from a rendered preview only stamps `trashed_at`, so the row and its file can be
     * restored. Old rows were all alive, which is exactly what NULL means here.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `attachments` ADD COLUMN `trashed_at` INTEGER")
        }
    }

    /**
     * 2.0.3 indexes the three columns the list screens filter and sort on.
     *
     * The note list collects `WHERE trashed = 0` (plus pinned / updated_at ordering) on every save,
     * pin or tag write; without indices each of those was a full-table scan whose cost grew with the
     * user's library. Index names follow Room's convention so the schema export matches the
     * migration.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes-table_trashed` ON `notes-table` (`trashed`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes-table_pinned` ON `notes-table` (`pinned`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes-table_updated_at` ON `notes-table` (`updated_at`)")
        }
    }

    /**
     * 2.0.4 / D5: the attachment library filters `WHERE trashed_at IS NULL`, the recycle bin
     * filters the opposite, and the library then narrows by `type`. All three queries run on
     * every attachment-list emission, and none of those columns was indexed before this step.
     * Index names follow Room's convention so the exported schema matches the migration.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_trashed_at` ON `attachments` (`trashed_at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_type` ON `attachments` (`type`)")
        }
    }
}

/**
 * The cipher the 7 -> 8 rewrite needs in order to read and re-seal note text.
 *
 * Production always uses [realNoteCrypto], a thin wrapper around [NoteCrypto] - the same fresh
 * instance per migration run the code has always created. The migration test runs under Robolectric, where
 * the Android Keystore behind [NoteCrypto] does not exist, so it hands [LegacyBodyRewriteMigration]
 * its own implementation through the constructor. Both methods keep [NoteCrypto]'s contract, so the
 * migration body itself is identical for either implementation.
 */
internal interface MigrationNoteCrypto {

    /** Store [plaintext]; same contract as `NoteCrypto.seal`. */
    fun seal(plaintext: String): String

    /**
     * Plain text passes through unchanged, unreadable ciphertext returns null so the caller can
     * tell "nothing to do" from "data I must not touch"; same contract as `NoteCrypto.openOrNull`.
     */
    fun openOrNull(stored: String): String?
}

private class RealNoteCrypto : MigrationNoteCrypto {
    private val delegate = NoteCrypto()

    override fun seal(plaintext: String): String = delegate.seal(plaintext)

    override fun openOrNull(stored: String): String? = delegate.openOrNull(stored)
}

private fun realNoteCrypto(): MigrationNoteCrypto = RealNoteCrypto()

/**
 * 7 -> 8: creates the `attachments` table and rewrites every legacy body once.
 *
 * 2.0 replaces the private `!(path)` / `?audio(path)` body syntax with Markdown links that point at
 * an id in the new `attachments` table, so every existing body has to be rewritten once.
 *
 * Vault rows (`encrypted = 1`) are skipped on purpose: their description is ciphertext derived from
 * the user's password, which this process cannot open. They are upgraded lazily the next time the
 * note is saved from the editor (see `EditViewModel.saveNote`), which is also why the rewrite is
 * idempotent - it finds nothing to do on an already-migrated body.
 *
 * Room runs migrations inside a transaction, and SQLite will not let a write interrupt an open
 * cursor on the same table, so every rewritten row is collected first and written afterwards.
 *
 * The cipher is created through [newCrypto] rather than constructed inline: that is the seam the
 * migration test uses to run without an Android Keystore. The default - and therefore production -
 * creates one [NoteCrypto] per migration run, exactly as this migration did before.
 */
internal class LegacyBodyRewriteMigration(
    private val newCrypto: () -> MigrationNoteCrypto = ::realNoteCrypto
) : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `attachments` (" +
                "`attachment-id` TEXT NOT NULL, " +
                "`note-id` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`path` TEXT NOT NULL, " +
                "`caption` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`attachment-id`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attachments_note-id` ON `attachments` (`note-id`)"
        )

        val crypto = newCrypto()
        val pendingNotes = ArrayList<Pair<Int, String>>()
        val pendingAttachments = ArrayList<Attachment>()

        db.query("SELECT `id`, `note-description`, `encrypted` FROM `notes-table`").use { cursor ->
            while (cursor.moveToNext()) {
                val noteId = cursor.getInt(0)
                val stored = cursor.getString(1) ?: ""
                val vaultRow = cursor.getInt(2) == 1

                if (vaultRow) continue

                val plain = crypto.openOrNull(stored) ?: continue
                val result = LegacyBodyMigration.rewrite(plain, noteId)
                if (!result.changed) continue

                pendingNotes += noteId to crypto.seal(result.body)
                pendingAttachments += result.attachments
            }
        }

        pendingNotes.forEach { (noteId, sealedBody) ->
            db.execSQL(
                "UPDATE `notes-table` SET `note-description` = ? WHERE `id` = ?",
                arrayOf<Any?>(sealedBody, noteId)
            )
        }

        pendingAttachments.forEach { attachment ->
            db.execSQL(
                "INSERT OR REPLACE INTO `attachments` " +
                    "(`attachment-id`, `note-id`, `type`, `path`, `caption`, `created_at`) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    attachment.id,
                    attachment.noteId,
                    attachment.type,
                    attachment.path,
                    attachment.caption,
                    attachment.createdAt
                )
            )
        }
    }
}

/**
 * SEC-4: the install-wide "legacy attachment captions have been sealed" marker.
 *
 * The note equivalent lives in `LegacyPlaintextBackfillFlag`; this is deliberately a separate flag
 * in the same `notes_prefs` file because the two sweeps protect different columns and can succeed
 * independently. A database restored from a portable archive carries plaintext captions again, so
 * the SAF/WebDAV restore paths clear this marker next to the note one. `RestoreSnapshots` only
 * knows the note marker, so [com.ezpnix.writeon.domain.usecase.NoteUseCase] also clears this one at
 * startup when it sees the note marker absent - before the note sweep re-marks it.
 */
object LegacyCaptionBackfillFlag {

    private const val PREFS_NAME = "notes_prefs"
    private const val KEY = "legacy_caption_backfill_done"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, true)
            .apply()
    }

    @SuppressLint("ApplySharedPref")
    fun reset(context: Context) {
        // commit(): same reason as LegacyPlaintextBackfillFlag.reset - the restore path restarts
        // the process right after this call, and an apply() that loses the race would skip the
        // caption sweep for the restored rows.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .commit()
    }
}

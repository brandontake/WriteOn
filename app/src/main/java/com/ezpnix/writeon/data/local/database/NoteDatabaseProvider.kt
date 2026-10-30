package com.ezpnix.writeon.data.local.database

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.database.Converters
import com.ezpnix.writeon.data.local.dao.AttachmentDao
import com.ezpnix.writeon.data.local.dao.NoteDao
import com.ezpnix.writeon.domain.model.Attachment
import java.io.File
import kotlinx.coroutines.flow.first

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
    @Synchronized
    fun instance(): NoteDatabase {
        return sharedDatabase ?: synchronized(lock) {
            sharedDatabase ?: buildDatabase().also { sharedDatabase = it }
        }
    }

    private fun buildDatabase(): NoteDatabase {
        return Room.databaseBuilder(
            application.applicationContext,
            NoteDatabase::class.java,
            DatabaseConst.NOTES_DATABASE_FILE_NAME
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_2_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            .build()
    }

    @Synchronized
    fun close() {
        sharedDatabase?.close()
        sharedDatabase = null
    }

    private companion object {
        @Volatile
        var sharedDatabase: NoteDatabase? = null

        val lock = Any()
    }

    fun noteDao(): NoteDao {
        return EncryptedNoteDao(instance().noteDao(), noteCrypto)
    }

    fun attachmentDao(): AttachmentDao = instance().attachmentDao()

    /**
     * Writes a copy of the note database in which every note is stored as plain text again, so that
     * the file can be restored on another device. The content is read through the normal
     * (encrypting) [noteDao], written into a throwaway database and copied out of the databases
     * directory.
     */
    suspend fun writePortableDatabaseCopy(destination: File) {
        val dao = noteDao()
        val notes = dao.getAllNotesSync() + dao.getTrashedNotes().first()
        // 2.0 bodies only store attachment ids, so the exported file must carry the id -> path map
        // too, otherwise a restored note cannot resolve a single image. The paths themselves are
        // only meaningful on this device; see STATUS.md for the cross-device caveat.
        val attachments = attachmentDao().getAll()

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

        try {
            notes.forEach { exportDatabase.noteDao().addNote(it) }
            if (attachments.isNotEmpty()) exportDatabase.attachmentDao().upsert(attachments)
        } finally {
            exportDatabase.close()
        }

        try {
            exportFile.copyTo(destination, overwrite = true)
        } finally {
            exportFile.delete()
        }
    }

    private val noteCrypto by lazy { NoteCrypto() }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The default must be a constant. This used to interpolate System.currentTimeMillis(), so
        // the DDL differed per migration run: every pre-v2 note came back with created_at set to the
        // moment its owner upgraded, which silently re-sorted the whole library and destroyed the
        // original ordering with no way to recover it. SQLite requires a constant DEFAULT in ALTER
        // TABLE ADD COLUMN, so a fixed sentinel is the only option here.
        //
        // 0 is treated as "unknown" downstream: NoteUseCase replaces a 0 createdAt with
        // System.currentTimeMillis() the next time the note is saved, so a note only keeps the
        // sentinel if it is never edited again.
        db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `encrypted` INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_2_4 = object : Migration(2, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `encrypted` INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `trashed` INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `tags` TEXT NOT NULL DEFAULT '[]'")
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes-table` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
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
 */
private val MIGRATION_7_8 = object : Migration(7, 8) {
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

        val crypto = NoteCrypto()
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
                arrayOf(sealedBody, noteId)
            )
        }

        pendingAttachments.forEach { attachment ->
            db.execSQL(
                "INSERT OR REPLACE INTO `attachments` " +
                    "(`attachment-id`, `note-id`, `type`, `path`, `caption`, `created_at`) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(
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
 * 2.0.2 gives attachments a recycle bin of their own: deleting one from the library, from the
 * editor or from a rendered preview only stamps `trashed_at`, so the row and its file can be
 * restored. Old rows were all alive, which is exactly what NULL means here.
 */
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `attachments` ADD COLUMN `trashed_at` INTEGER")
    }
}

/**
 * 2.0.3 indexes the three columns the list screens filter and sort on.
 *
 * The note list collects `WHERE trashed = 0` (plus pinned / updated_at ordering) on every save,
 * pin or tag write; without indices each of those was a full-table scan whose cost grew with the
 * user's library. Index names follow Room's convention so the schema export matches the migration.
 */
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes-table_trashed` ON `notes-table` (`trashed`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes-table_pinned` ON `notes-table` (`pinned`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes-table_updated_at` ON `notes-table` (`updated_at`)")
    }
}
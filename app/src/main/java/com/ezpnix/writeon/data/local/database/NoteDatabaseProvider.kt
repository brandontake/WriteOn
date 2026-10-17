package com.ezpnix.writeon.data.local.database

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.data.local.dao.NoteDao
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.flow.first

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        val listType = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, listType) ?: emptyList()
    }
}

@TypeConverters(Converters::class)
class NoteDatabaseProvider(private val application: Application) {
    @Volatile
    private var database: NoteDatabase? = null

    @Synchronized
    fun instance(): NoteDatabase {
        return database ?: synchronized(this) {
            database ?: buildDatabase().also { database = it }
        }
    }

    private fun buildDatabase(): NoteDatabase {
        return Room.databaseBuilder(
            application.applicationContext,
            NoteDatabase::class.java,
            DatabaseConst.NOTES_DATABASE_FILE_NAME
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_2_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()
    }

    @Synchronized
    fun close() {
        database?.close()
        database = null
    }

    fun noteDao(): NoteDao {
        return EncryptedNoteDao(instance().noteDao(), noteCrypto)
    }

    /**
     * Writes a copy of the note database in which every note is stored as plain text again, so that
     * the file can be restored on another device. The content is read through the normal
     * (encrypting) [noteDao], written into a throwaway database and copied out of the databases
     * directory.
     */
    suspend fun writePortableDatabaseCopy(destination: File) {
        val dao = noteDao()
        val notes = dao.getAllNotesSync() + dao.getTrashedNotes().first()

        val exportName = "writeon-portable-${System.currentTimeMillis()}.db"
        val exportFile = application.applicationContext.getDatabasePath(exportName)
        exportFile.parentFile?.mkdirs()

        val exportDatabase = Room.databaseBuilder(
            application.applicationContext,
            NoteDatabase::class.java,
            exportName
        )
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()

        try {
            notes.forEach { exportDatabase.noteDao().addNote(it) }
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
package com.ezpnix.writeon.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.data.local.dao.AttachmentDao
import com.ezpnix.writeon.data.local.dao.NoteDao
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note

@Database(
    entities = [Note::class, Attachment::class],
    version = DatabaseConst.NOTES_DATABASE_VERSION,
    // Room writes the generated DDL into app/schemas on every build. Without it there is no record
    // of what a given version's schema actually looked like, so a migration cannot be tested
    // offline and schema drift cannot be detected without running the app on two devices. The
    // directory is checked in deliberately; see schemaDirectory in build.gradle.kts.
    exportSchema = true
)
abstract class NoteDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao

    abstract fun attachmentDao(): AttachmentDao
}

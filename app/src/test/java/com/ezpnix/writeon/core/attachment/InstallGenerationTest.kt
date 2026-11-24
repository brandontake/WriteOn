package com.ezpnix.writeon.core.attachment

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R4-03: the generation token is a SQLite header field, so it must survive ordinary content writes
 * and be absent from a database this install never stamped.
 */
@RunWith(RobolectricTestRunner::class)
class InstallGenerationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a stamped token survives content writes and reads back`() {
        val database = File(temporaryFolder.newFolder("db"), "notes.db")
        SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
            db.execSQL("CREATE TABLE t (x TEXT)")
        }

        val token = InstallGeneration.stamp(database)
        assertNotNull("a real SQLite file must accept a generation token", token)
        assertEquals(token, InstallGeneration.read(database))

        // The startup plaintext sweep and every note save write rows; neither may change identity.
        SQLiteDatabase.openDatabase(
            database.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            repeat(3) { db.execSQL("INSERT INTO t VALUES ('a note')") }
        }

        assertEquals(token, InstallGeneration.read(database))
    }

    @Test
    fun `an unstamped database reads as generation zero`() {
        val database = File(temporaryFolder.newFolder("plain"), "notes.db")
        SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
            db.execSQL("CREATE TABLE t (x TEXT)")
        }

        assertEquals(0, InstallGeneration.read(database))
    }

    @Test
    fun `a file that is not a database cannot be stamped`() {
        val notADatabase = temporaryFolder.newFile("broken.db").apply {
            writeText("this is not sqlite")
        }

        assertEquals(null, InstallGeneration.stamp(notADatabase))
    }
}

package com.ezpnix.writeon.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.data.local.database.LegacyCaptionBackfillFlag
import com.ezpnix.writeon.data.local.database.LegacyPlaintextBackfillFlag
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R5-01: the attachment install stamps the live database's generation, which changes its bytes.
 *
 * Before this fix `RestoreJournal` still decided "did the swap happen?" by comparing the live
 * file's whole SHA-256 with the pre-swap hash. A kill after `AttachmentArchive.install()` and
 * before `RestoreJournal.clear()` therefore looked like "the move never happened": the journal was
 * discarded and the archive's deck/salt were never applied. The staged database is now stamped
 * *before* the journal hashes it, and install reuses that token, so the bytes stay put.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreJournalStampTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun liveDb(): File =
        context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME).apply { parentFile?.mkdirs() }

    @Before
    fun reset() {
        RestoreJournal.clear(context)
        LegacyPlaintextBackfillFlag.reset(context)
        LegacyCaptionBackfillFlag.reset(context)
        AttachmentArchive.installJournal(AttachmentArchive.workDirectory(context)).delete()
    }

    @After
    fun cleanup() {
        RestoreJournal.clear(context)
        File(context.cacheDir, "stamped-restore.db").delete()
        File(context.cacheDir, "stamped-attachment.jpg").delete()
        File(AttachmentArchive.restoreDirectory(context), "stamped.jpg").delete()
        liveDb().delete()
    }

    @Test
    fun `a token journal whose live database cannot be read keeps the journal`() {
        val restored = File(context.cacheDir, "stamped-restore.db")
        createDatabase(restored)
        assertTrue(
            RestoreJournal.write(
                context = context,
                restoredDb = restored,
                newSalt = null,
                newDeckSealed = null,
                setSalt = false,
                setDeck = false,
                stampGeneration = true,
            )
        )

        // A non-SQLite live file makes InstallGeneration.read return null. The journal has a
        // token, so R6-03 must keep it instead of falling back to a hash that cannot match.
        liveDb().writeText("not a sqlite database")

        val result = RestoreJournal.recoverWith(context)

        assertTrue(result is RestoreJournal.RecoverResult.Failed)
        assertEquals(
            "database generation",
            (result as RestoreJournal.RecoverResult.Failed).stage,
        )
        assertTrue("the journal must survive for the next launch", RestoreJournal.file(context).exists())
    }

    @Test
    fun `a completed restore survives the attachment install's generation stamp`() {
        val restored = File(context.cacheDir, "stamped-restore.db")
        createDatabase(restored)

        assertTrue(
            "the restore journal must accept a stampable database",
            RestoreJournal.write(
                context = context,
                restoredDb = restored,
                newSalt = null,
                newDeckSealed = "sealed-deck",
                setSalt = false,
                setDeck = true,
                stampGeneration = true,
            )
        )

        // The atomic swap: the stamped staged file becomes the live database.
        liveDb().delete()
        restored.copyTo(liveDb(), overwrite = true)

        // The real install runs after the swap. It must reuse the staged generation instead of
        // stamping a new token (which is what changed the journal's hash before R5-01).
        val source = File(context.cacheDir, "stamped-attachment.jpg").apply { writeText("new") }
        val target = File(AttachmentArchive.restoreDirectory(context), "stamped.jpg").apply { delete() }
        assertEquals(
            1,
            AttachmentArchive.install(
                context,
                AttachmentArchive.PreparedAttachments(
                    stagedFiles = listOf(source to target),
                    pathsById = mapOf("stamped" to target.absolutePath),
                )
            )
        )

        // The process dies here: the journal was never cleared. The next start must finish it.
        LegacyPlaintextBackfillFlag.markDone(context)
        LegacyCaptionBackfillFlag.markDone(context)
        var appliedDeck: String? = null
        val result = RestoreJournal.recoverWith(
            context,
            applyDeck = { _, sealed ->
                appliedDeck = sealed
                true
            },
        )

        assertEquals(
            "the stamp in install() must not make the completed move look discarded",
            RestoreJournal.RecoverResult.Completed,
            result,
        )
        assertEquals("sealed-deck", appliedDeck)
        assertFalse("a completed move clears the plaintext backfill marker", LegacyPlaintextBackfillFlag.isDone(context))
        assertFalse("a completed move clears the caption backfill marker", LegacyCaptionBackfillFlag.isDone(context))
        assertFalse(RestoreJournal.file(context).exists())
    }

    @Test
    fun `a restore killed before the swap still discards the journal`() {
        val restored = File(context.cacheDir, "stamped-restore.db")
        createDatabase(restored)
        assertTrue(
            RestoreJournal.write(
                context = context,
                restoredDb = restored,
                newSalt = null,
                newDeckSealed = "sealed-deck",
                setSalt = false,
                setDeck = true,
                stampGeneration = true,
            )
        )
        // The live file is still the old database (a different generation).
        liveDb().delete()
        createDatabase(liveDb())

        var appliedDeck: String? = null
        val result = RestoreJournal.recoverWith(
            context,
            applyDeck = { _, sealed ->
                appliedDeck = sealed
                true
            },
        )

        assertEquals(RestoreJournal.RecoverResult.Discarded, result)
        assertEquals("the archive's deck must not be applied", null, appliedDeck)
        assertFalse(RestoreJournal.file(context).exists())
    }

    private fun createDatabase(file: File) {
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT)")
            db.execSQL("INSERT INTO notes (id, body) VALUES (1, 'hello')")
        }
    }
}

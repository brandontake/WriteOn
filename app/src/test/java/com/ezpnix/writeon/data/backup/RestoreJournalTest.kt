package com.ezpnix.writeon.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.security.SharedPreferencesVaultKeyStore
import com.ezpnix.writeon.data.local.database.LegacyCaptionBackfillFlag
import com.ezpnix.writeon.data.local.database.LegacyPlaintextBackfillFlag
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * N-1 restart half: a kill between the database move and the salt/deck apply.
 *
 * The journal decides by bytes, not by a phase flag: the live file either equals the staged
 * database that was about to be installed or it does not. These tests pin both outcomes and the
 * flag reset that must accompany a completed move.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreJournalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun liveDb(): File =
        context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME).apply { parentFile?.mkdirs() }

    private fun stagedDb(): File =
        File(context.cacheDir, "journal-staged.db").apply { writeBytes("restored database".toByteArray()) }

    private fun salt(fill: Byte): String =
        Base64.getEncoder().encodeToString(ByteArray(16) { fill })

    @Before
    fun resetState() {
        RestoreJournal.clear(context)
        LegacyPlaintextBackfillFlag.reset(context)
        LegacyCaptionBackfillFlag.reset(context)
    }

    @Test
    fun `a moved database gets the journal's salt and the backfill flags cleared`() {
        val restored = stagedDb()
        val newSalt = salt(2)
        val oldSalt = salt(1)
        SharedPreferencesVaultKeyStore(context).setEncodedSalt(oldSalt)
        LegacyPlaintextBackfillFlag.markDone(context)
        LegacyCaptionBackfillFlag.markDone(context)

        assertTrue(RestoreJournal.write(context, restored, newSalt, newDeckSealed = null))
        liveDb().writeBytes(restored.readBytes())

        RestoreJournal.recover(context)

        assertEquals(newSalt, SharedPreferencesVaultKeyStore(context).encodedSalt())
        assertFalse(LegacyPlaintextBackfillFlag.isDone(context))
        assertFalse(LegacyCaptionBackfillFlag.isDone(context))
        assertFalse(RestoreJournal.file(context).exists())
    }

    @Test
    fun `a database that never moved keeps the old salt`() {
        val restored = stagedDb()
        val oldSalt = salt(3)
        SharedPreferencesVaultKeyStore(context).setEncodedSalt(oldSalt)

        assertTrue(RestoreJournal.write(context, restored, salt(4), newDeckSealed = null))
        liveDb().writeBytes("still the old database".toByteArray())

        RestoreJournal.recover(context)

        assertEquals(oldSalt, SharedPreferencesVaultKeyStore(context).encodedSalt())
        assertFalse(RestoreJournal.file(context).exists())
    }

    @Test
    fun `an undo journal can clear a salt and deck the snapshot did not have`() {
        val restored = stagedDb()
        SharedPreferencesVaultKeyStore(context).setEncodedSalt(salt(6))
        context.getSharedPreferences("flashcards_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("flashcards_key", "deck-from-the-archive")
            .commit()

        assertTrue(
            RestoreJournal.write(
                context,
                restored,
                newSalt = null,
                newDeckSealed = null,
                setSalt = true,
                setDeck = true,
            )
        )
        liveDb().writeBytes(restored.readBytes())

        RestoreJournal.recover(context)

        assertEquals(null, SharedPreferencesVaultKeyStore(context).encodedSalt())
        assertEquals(
            null,
            context.getSharedPreferences("flashcards_prefs", Context.MODE_PRIVATE)
                .getString("flashcards_key", null)
        )
    }

    @Test
    fun `an unreadable journal is discarded without touching prefs`() {
        val oldSalt = salt(5)
        SharedPreferencesVaultKeyStore(context).setEncodedSalt(oldSalt)
        RestoreJournal.file(context).writeText("not json", Charsets.UTF_8)

        RestoreJournal.recover(context)

        assertEquals(oldSalt, SharedPreferencesVaultKeyStore(context).encodedSalt())
        assertFalse(RestoreJournal.file(context).exists())
    }
}

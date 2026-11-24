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
 * DAT-04: the restore journal may only disappear after every apply succeeded.
 *
 * The journal holds the only copy of the salt that belongs to the database move it records. A salt
 * commit that fails (storage full, read-only prefs) used to be ignored and the journal deleted
 * anyway, orphaning every restored `v3:` row for good. These tests drive the failing store through
 * the internal seam and pin both outcomes.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreJournalFailureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetJournal() {
        RestoreJournal.clear(context)
    }

    @Test
    fun `a failed salt apply keeps the journal and the pending salt`() {
        val restored = stagedDb()
        val oldSalt = salt(11)
        val newSalt = salt(12)
        SharedPreferencesVaultKeyStore(context).setEncodedSalt(oldSalt)
        assertTrue(RestoreJournal.write(context, restored, newSalt, newDeckSealed = null))
        liveDb().writeBytes(restored.readBytes())

        val result = RestoreJournal.recoverWith(
            context,
            applySalt = { _, _ -> false },
        )

        assertTrue(
            "the journal is the only durable record of the transition",
            RestoreJournal.file(context).exists()
        )
        assertTrue(result is RestoreJournal.RecoverResult.Failed)
        assertEquals(
            "a failed apply must not overwrite the salt the journal still names",
            oldSalt,
            SharedPreferencesVaultKeyStore(context).encodedSalt()
        )
    }

    @Test
    fun `a working salt apply finishes the transition and removes the journal`() {
        val restored = stagedDb()
        val newSalt = salt(13)
        SharedPreferencesVaultKeyStore(context).setEncodedSalt(salt(14))
        assertTrue(RestoreJournal.write(context, restored, newSalt, newDeckSealed = null))
        liveDb().writeBytes(restored.readBytes())

        val result = RestoreJournal.recoverWith(context)

        assertEquals(RestoreJournal.RecoverResult.Completed, result)
        assertEquals(newSalt, SharedPreferencesVaultKeyStore(context).encodedSalt())
        assertFalse("a finished transition has no journal", RestoreJournal.file(context).exists())
    }

    @Test
    fun `a database that never moved is still discarded`() {
        val restored = stagedDb()
        val oldSalt = salt(15)
        SharedPreferencesVaultKeyStore(context).setEncodedSalt(oldSalt)
        assertTrue(RestoreJournal.write(context, restored, salt(16), newDeckSealed = null))
        liveDb().writeBytes("still the old database".toByteArray())

        val result = RestoreJournal.recoverWith(
            context,
            applySalt = { _, _ -> error("a failed move must not apply the salt") },
        )

        assertEquals(RestoreJournal.RecoverResult.Discarded, result)
        assertFalse(RestoreJournal.file(context).exists())
        assertEquals(oldSalt, SharedPreferencesVaultKeyStore(context).encodedSalt())
    }

    @Test
    fun `a failed apply after the move still clears both plaintext backfill flags`() {
        // REG-01: the restored database is plaintext and live even when the deck apply fails.
        // Leaving the install-wide flags set made every later launch skip the sealing sweep.
        val restored = stagedDb()
        SharedPreferencesVaultKeyStore(context).setEncodedSalt(salt(21))
        LegacyPlaintextBackfillFlag.markDone(context)
        LegacyCaptionBackfillFlag.markDone(context)
        assertTrue(
            RestoreJournal.write(context, restored, salt(22), newDeckSealed = "sealed-deck")
        )
        liveDb().writeBytes(restored.readBytes())

        val result = RestoreJournal.recoverWith(
            context,
            applySalt = { target, encoded -> VaultSaltArchive.applyEncoded(target, encoded) },
            applyDeck = { _, _ -> false },
        )

        assertTrue(result is RestoreJournal.RecoverResult.Failed)
        assertTrue("the journal still names what has to be retried", RestoreJournal.file(context).exists())
        assertFalse(
            "plaintext rows from the restored database must be swept",
            LegacyPlaintextBackfillFlag.isDone(context)
        )
        assertFalse(
            "plaintext captions from the restored database must be swept",
            LegacyCaptionBackfillFlag.isDone(context)
        )
        assertEquals(salt(22), SharedPreferencesVaultKeyStore(context).encodedSalt())
    }

    @Test
    fun `a journal that requires a deck it cannot provide keeps the journal`() {
        // DAT-04: this is not the undo's explicit "the snapshot had no deck" (setDeck with a null
        // sealed value). A required-but-missing deck is a failed apply and must never be treated
        // as a no-op that clears the current deck.
        val restored = stagedDb()
        liveDb().writeBytes(restored.readBytes())
        assertTrue(
            RestoreJournal.write(
                context,
                restored,
                newSalt = null,
                newDeckSealed = null,
                setSalt = false,
                setDeck = true,
                deckRequired = true,
            )
        )

        val result = RestoreJournal.recoverWith(context)

        assertTrue(result is RestoreJournal.RecoverResult.Failed)
        assertTrue(RestoreJournal.file(context).exists())
    }

    private fun liveDb(): File =
        context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)
            .apply { parentFile?.mkdirs() }

    private fun stagedDb(): File =
        File(context.cacheDir, "journal-failure-staged.db")
            .apply { writeBytes("restored database".toByteArray()) }

    private fun salt(fill: Byte): String =
        Base64.getEncoder().encodeToString(ByteArray(16) { fill })
}

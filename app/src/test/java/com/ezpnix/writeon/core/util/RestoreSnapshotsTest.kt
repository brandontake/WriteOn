package com.ezpnix.writeon.core.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.attachment.AttachmentSnapshots
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.security.SharedPreferencesVaultKeyStore
import com.ezpnix.writeon.data.backup.SnapshotSidecar
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAT-1 backend tests: the `.bak` snapshot is discoverable, its absence is reported clearly, and a
 * restore that fails cannot consume it (V-4).
 *
 * The swap itself is a real SQLite operation; Robolectric supplies a real SQLite, so the snapshot
 * fixtures here are databases this build's D8 gate actually accepts. That is what lets the
 * failure test drive the real `replaceDatabaseAtomically` path with only its final move failing -
 * the exact window in which the current database has already been parked beside `.bak`.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreSnapshotsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun liveDatabase(): File =
        context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME).apply {
            parentFile?.mkdirs()
        }

    private fun snapshot(): File = DatabaseRestore.backupFile(liveDatabase())

    private fun replacedCopy(): File =
        File(liveDatabase().absolutePath + RestoreSnapshots.REPLACED_SUFFIX)

    private fun stagingCopy(): File =
        File(liveDatabase().absolutePath + ".snapshot-restore")

    @Test
    fun `without a backup there is no snapshot`() {
        snapshot().delete()

        assertNull(RestoreSnapshots.info(context))
        assertFalse(RestoreSnapshots.hasSnapshot(context))
    }

    @Test
    fun `info describes a non-empty backup`() {
        val backup = snapshot().apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
            setLastModified(1_700_000_000_000L)
        }

        val info = RestoreSnapshots.info(context)

        assertEquals(backup.absolutePath, info?.file?.absolutePath)
        assertEquals(4L, info?.sizeBytes)
        assertEquals(1_700_000_000_000L, info?.savedAtMillis)
        assertTrue(RestoreSnapshots.hasSnapshot(context))
    }

    @Test
    fun `an empty backup is not a snapshot`() {
        snapshot().apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(0))
        }

        assertNull("a zero-byte .bak could never be restored", RestoreSnapshots.info(context))
        assertFalse(RestoreSnapshots.hasSnapshot(context))
    }

    @Test
    fun `restorePrevious fails with a clear message when there is no snapshot`() = runBlocking {
        snapshot().delete()

        val result = RestoreSnapshots.restorePrevious(context)

        assertTrue("restoring without a snapshot must fail", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "the message should explain the missing snapshot, was: $message",
            message.contains("no previous database snapshot", ignoreCase = true)
        )
    }

    @Test
    fun `restorePrevious refuses a snapshot that is not a database without touching it`() = runBlocking {
        val original = "not a database".toByteArray()
        val backup = snapshot().apply {
            parentFile?.mkdirs()
            writeBytes(original)
        }

        val result = RestoreSnapshots.restorePrevious(context)

        assertTrue("a corrupt snapshot must not be installed", result.isFailure)
        assertTrue(
            "the failure should name the integrity check",
            result.exceptionOrNull()?.message.orEmpty().contains("integrity check", ignoreCase = true)
        )
        // V-4: the rejection happens before anything is copied anywhere, so the bytes the user
        // might still recover by hand (or with a fixed build) are exactly as they were.
        assertArrayEquals("a refused snapshot must be left byte for byte", original, backup.readBytes())
        assertTrue("the snapshot must still be offered", RestoreSnapshots.hasSnapshot(context))
        assertFalse("no parked copy is left behind", replacedCopy().exists())
    }

    /**
     * V-4: the swap used to copy the live database over `.bak` before installing the replacement,
     * so a failure *after* that copy left the user with no snapshot at all - a failed "undo" that
     * could not even be retried.
     */
    @Test
    fun `a failed install puts the original snapshot back so the restore can be retried`() = runBlocking {
        val live = liveDatabase()
        val currentBytes = "current database".toByteArray()
        val snapshotBytes = compatibleDatabase(temporaryFolder.newFile("previous.db")).readBytes()
        live.writeBytes(currentBytes)
        val backup = snapshot().apply {
            parentFile?.mkdirs()
            writeBytes(snapshotBytes)
        }

        var attempts = 0
        val failed = RestoreSnapshots.restorePrevious(context) { target, staged, replaced ->
            attempts++
            // The real swap with only the final move failing: the current database has already
            // been parked in `replaced` when the injected failure lands.
            DatabaseRestore.replaceDatabaseAtomically(target, staged, replaced) { _, _ ->
                throw IOException("injected move failure")
            }
        }

        assertEquals("the install must be reached exactly once", 1, attempts)
        assertTrue("the injected move failure must be reported", failed.isFailure)
        assertArrayEquals(
            "the only snapshot must survive a failed undo",
            snapshotBytes,
            backup.readBytes()
        )
        assertTrue("a retry must still be possible", RestoreSnapshots.hasSnapshot(context))
        assertArrayEquals(
            "a failed restore must not touch the live database",
            currentBytes,
            live.readBytes()
        )
        assertFalse("the parked current database is dropped after failure", replacedCopy().exists())
        assertFalse("no staging copy survives either", stagingCopy().exists())

        // The retry is the point of the guarantee: same snapshot, real swap, no injection.
        val retried = RestoreSnapshots.restorePrevious(context)

        assertTrue("the retry must succeed, was: ${retried.exceptionOrNull()}", retried.isSuccess)
        assertArrayEquals("the snapshot must now be live", snapshotBytes, live.readBytes())
        assertArrayEquals(
            "the replaced database must now be the .bak, so the undo stays undoable",
            currentBytes,
            backup.readBytes()
        )
        assertFalse("no parked copy is left after success", replacedCopy().exists())
        assertFalse("no staging copy is left after success", stagingCopy().exists())
    }

    @Test
    fun `a successful restore rotates the replaced database into the backup and leaves no temp`() =
        runBlocking {
            val live = liveDatabase()
            val currentBytes = "current database".toByteArray()
            val snapshotBytes = compatibleDatabase(temporaryFolder.newFile("previous.db")).readBytes()
            live.writeBytes(currentBytes)
            val backup = snapshot().apply {
                parentFile?.mkdirs()
                writeBytes(snapshotBytes)
            }

            val result = RestoreSnapshots.restorePrevious(context)

            assertTrue("the snapshot must install, was: ${result.exceptionOrNull()}", result.isSuccess)
            assertArrayEquals("the snapshot becomes the live database", snapshotBytes, live.readBytes())
            assertArrayEquals(
                "the replaced database becomes the new snapshot",
                currentBytes,
                backup.readBytes()
            )
            assertFalse("the parked copy was rotated away", replacedCopy().exists())
            assertFalse("the staging copy was deleted", stagingCopy().exists())
        }

    /**
     * N-1/N-2: the `.bak` is only the database half of the snapshot. Its sidecar holds the salt
     * and deck those rows were written with; the undo must put all three back together, and rotate
     * the pre-undo state into the new `.bak` so the undo stays undoable.
     */
    @Test
    fun `undo restores the snapshot's vault salt and flashcard deck`() = runBlocking {
        val live = liveDatabase()
        live.writeBytes("current database".toByteArray())
        val snapshotBytes = compatibleDatabase(temporaryFolder.newFile("previous.db")).readBytes()
        val backup = snapshot().apply {
            parentFile?.mkdirs()
            writeBytes(snapshotBytes)
        }

        val currentSalt = encodedSalt(1)
        val snapshotSalt = encodedSalt(2)
        SharedPreferencesVaultKeyStore(context).setEncodedSalt(currentSalt)
        setDeck("deck-A")
        assertTrue(SnapshotSidecar.write(backup, snapshotSalt, "deck-B"))

        val result = RestoreSnapshots.restorePrevious(context)

        assertTrue("the snapshot must install, was: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("the old rows need the salt they were sealed with", snapshotSalt, SharedPreferencesVaultKeyStore(context).encodedSalt())
        assertEquals("the deck must follow the database", "deck-B", deck())
        val rotated = (SnapshotSidecar.read(backup) as SnapshotSidecar.ReadResult.Found).contents
        assertEquals("the new .bak must carry the pre-undo salt", currentSalt, rotated.vaultSalt)
        assertEquals("the new .bak must carry the pre-undo deck", "deck-A", rotated.flashcards)
    }

    /**
     * If the captured salt cannot be applied, the database install is still reversible: the parked
     * current database goes back and both prefs stores keep their pre-undo values.
     */
    @Test
    fun `an unappliable snapshot salt puts the current database and prefs back`() = runBlocking {
        val live = liveDatabase()
        val currentBytes = "current database".toByteArray()
        live.writeBytes(currentBytes)
        val snapshotBytes = compatibleDatabase(temporaryFolder.newFile("previous.db")).readBytes()
        val backup = snapshot().apply {
            parentFile?.mkdirs()
            writeBytes(snapshotBytes)
        }
        val currentSalt = encodedSalt(3)
        SharedPreferencesVaultKeyStore(context).setEncodedSalt(currentSalt)
        setDeck("deck-A")
        assertTrue(SnapshotSidecar.write(backup, vaultSalt = "not-base64", flashcards = "deck-B"))

        val result = RestoreSnapshots.restorePrevious(context)

        assertTrue("an unappliable sidecar must fail the undo", result.isFailure)
        assertArrayEquals("the current database must be live again", currentBytes, live.readBytes())
        assertArrayEquals("the snapshot must survive for a retry", snapshotBytes, backup.readBytes())
        assertEquals(currentSalt, SharedPreferencesVaultKeyStore(context).encodedSalt())
        assertEquals("deck-A", deck())
        assertFalse("the parked copy was moved back", replacedCopy().exists())
        assertEquals(
            "the failed apply must not corrupt the pairing either",
            "not-base64",
            (SnapshotSidecar.read(backup) as SnapshotSidecar.ReadResult.Found).contents.vaultSalt
        )
    }

    /**
     * N-2: the undo must put the old database, its salt/deck and its attachment tree back as one
     * set, and pair the displaced tree with the new `.bak` so the undo stays undoable.
     */
    @Test
    fun `undo restores the snapshot's attachment tree and pairs the redo tree`() = runBlocking {
        val live = liveDatabase()
        live.writeBytes("current database".toByteArray())
        val snapshotBytes = compatibleDatabase(temporaryFolder.newFile("previous.db")).readBytes()
        val backup = snapshot().apply {
            parentFile?.mkdirs()
            writeBytes(snapshotBytes)
        }

        val attachments = context.filesDir
        val oldImage = File(attachments, "attachments/old.jpg").apply {
            parentFile?.mkdirs()
            writeBytes("old-image".toByteArray())
        }
        val oldSnapshot = AttachmentSnapshots.capture(context)!!

        // The live tree changed after the snapshot was captured; the undo must not keep these.
        val temp = File(oldImage.parentFile, oldImage.name + ".tmp")
        temp.writeBytes("current-image".toByteArray())
        java.nio.file.Files.move(
            temp.toPath(),
            oldImage.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        File(attachments, "attachments/current.jpg").writeBytes("current-extra".toByteArray())

        assertTrue(SnapshotSidecar.write(backup, null, null, oldSnapshot))

        val result = RestoreSnapshots.restorePrevious(context)

        assertTrue("the undo must succeed, was: ${result.exceptionOrNull()}", result.isSuccess)
        assertArrayEquals("the snapshot's image bytes must be live", "old-image".toByteArray(), oldImage.readBytes())
        assertFalse("a file added after the snapshot must be gone", File(attachments, "attachments/current.jpg").exists())
        assertFalse("the consumed snapshot is removed", AttachmentSnapshots.exists(context, oldSnapshot))

        val rotated = SnapshotSidecar.read(backup) as SnapshotSidecar.ReadResult.Found
        val redo = rotated.contents.attachmentSnapshot
        assertTrue("the new .bak must carry the displaced tree", AttachmentSnapshots.exists(context, redo))
        assertTrue(
            "the redo tree holds the pre-undo image",
            File(AttachmentSnapshots.directory(context, redo!!), "attachments/old.jpg")
                .readBytes()
                .contentEquals("current-image".toByteArray())
        )
        AttachmentSnapshots.pruneExcept(context, null)
    }

    @Test
    fun `a legacy snapshot with v3 rows is refused instead of using the current salt`() = runBlocking {
        val live = liveDatabase()
        val currentBytes = "current database".toByteArray()
        live.writeBytes(currentBytes)
        val snapshotBytes = envelopeDatabase(temporaryFolder.newFile("legacy-v3.db")).readBytes()
        val backup = snapshot().apply {
            parentFile?.mkdirs()
            writeBytes(snapshotBytes)
        }

        val result = RestoreSnapshots.restorePrevious(context)

        assertTrue("a v3 snapshot without a recorded salt must be refused", result.isFailure)
        assertTrue(
            "the failure must explain the missing Vault metadata, was: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message.orEmpty().contains("predates Vault metadata", ignoreCase = true)
        )
        assertArrayEquals("the refused snapshot must survive byte for byte", snapshotBytes, backup.readBytes())
        assertArrayEquals("the live database must not change", currentBytes, live.readBytes())
    }

    /**
     * N-7: another restore cannot replace `.bak` between the sidecar read and the shared lock.
     * The test holds the lock, starts the undo, replaces the snapshot with different bytes while it
     * is waiting, then releases: the lock-scoped re-read must notice the sidecar hash mismatch and
     * leave the live database untouched.
     */
    @Test
    fun `snapshot metadata is re-read after the restore lock is acquired`() = runBlocking {
        val live = liveDatabase()
        val currentBytes = "current database".toByteArray()
        live.writeBytes(currentBytes)
        val first = compatibleDatabase(temporaryFolder.newFile("first.db")).readBytes()
        val backup = snapshot().apply {
            parentFile?.mkdirs()
            writeBytes(first)
        }
        assertTrue(SnapshotSidecar.write(backup, null, null))

        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            BackupRestoreLock.mutex.withLock {
                locked.complete(Unit)
                release.await()
            }
        }
        locked.await()

        val restore = async(Dispatchers.IO) { RestoreSnapshots.restorePrevious(context) }
        delay(100)
        // Simulate a concurrent restore that replaced both `.bak` and its sidecar while this undo
        // was waiting for the lock. The old sidecar now describes different bytes.
        val second = compatibleDatabase(temporaryFolder.newFile("second.db")).readBytes() +
            byteArrayOf(0x42)
        backup.writeBytes(second)

        release.complete(Unit)
        holder.join()
        val result = restore.await()

        assertTrue("a sidecar that no longer matches must be refused", result.isFailure)
        assertTrue(
            "the failure must name the metadata hash, was: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message.orEmpty().contains("does not match", ignoreCase = true)
        )
        assertArrayEquals("the live database must not change", currentBytes, live.readBytes())
    }

    @Test
    fun `startup cleanup keeps only the attachment tree named by the sidecar`() {
        val live = liveDatabase()
        live.writeBytes("current database".toByteArray())
        val backup = snapshot().apply {
            parentFile?.mkdirs()
            writeBytes("previous database".toByteArray())
        }
        val keep = AttachmentSnapshots.capture(context)!!
        val drop = AttachmentSnapshots.capture(context)!!
        assertTrue(SnapshotSidecar.write(backup, null, null, keep))

        RestoreSnapshots.pruneAttachmentSnapshots(context)

        assertTrue(AttachmentSnapshots.exists(context, keep))
        assertFalse(AttachmentSnapshots.exists(context, drop))
    }

    private fun encodedSalt(fill: Byte): String =
        Base64.getEncoder().encodeToString(ByteArray(16) { fill })

    private fun deckPrefs() =
        context.getSharedPreferences("flashcards_prefs", Context.MODE_PRIVATE)

    private fun deck(): String? = deckPrefs().getString("flashcards_key", null)

    private fun setDeck(value: String) {
        deckPrefs().edit().putString("flashcards_key", value).commit()
    }

    /**
     * A database this build would install: the current `user_version` plus the matching
     * `room_master_table.identity_hash`, i.e. everything [DatabaseRestore.assertArchiveCompatible]
     * and the post-install `quick_check` ask for.
     */
    private fun compatibleDatabase(file: File): File {
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            database.execSQL(
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            database.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(DatabaseRestore.CURRENT_IDENTITY_HASH)
            )
            database.version = DatabaseConst.NOTES_DATABASE_VERSION
        } finally {
            database.close()
        }
        return file
    }

    /** A compatible database carrying one `v3:` row but (deliberately) no sidecar. */
    private fun envelopeDatabase(file: File): File {
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            database.execSQL(
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            database.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(DatabaseRestore.CURRENT_IDENTITY_HASH)
            )
            database.execSQL(
                "CREATE TABLE \"notes-table\" (\"note-name\" TEXT, \"note-description\" TEXT)"
            )
            database.execSQL(
                "INSERT INTO \"notes-table\" VALUES ('v3:wrap:dek:iv:body', '')"
            )
            database.version = DatabaseConst.NOTES_DATABASE_VERSION
        } finally {
            database.close()
        }
        return file
    }
}

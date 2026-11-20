package com.ezpnix.writeon.data.backup

import java.io.File
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
 * N-1/N-2: the `.bak` sidecar must describe exactly the database bytes beside it.
 *
 * These tests pin the two failure modes that would otherwise be silent data loss: a sidecar from a
 * different snapshot being applied (wrong KEK to every `v3:` row) and a valid-looking file with a
 * corrupt shape. Robolectric only supplies `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
class SnapshotSidecarTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun backup(bytes: ByteArray = "previous database".toByteArray()): File =
        temporaryFolder.newFile("note-list.db.bak").apply { writeBytes(bytes) }

    @Test
    fun `a written sidecar reads back both values`() {
        val backup = backup()

        assertTrue(SnapshotSidecar.write(backup, vaultSalt = "salt-S1", flashcards = "deck-A"))

        val result = SnapshotSidecar.read(backup)
        assertTrue(result is SnapshotSidecar.ReadResult.Found)
        val contents = (result as SnapshotSidecar.ReadResult.Found).contents
        assertEquals("salt-S1", contents.vaultSalt)
        assertEquals("deck-A", contents.flashcards)
    }

    @Test
    fun `null values are authoritative and survive the round trip`() {
        val backup = backup()

        assertTrue(SnapshotSidecar.write(backup, vaultSalt = null, flashcards = null))

        val contents = (SnapshotSidecar.read(backup) as SnapshotSidecar.ReadResult.Found).contents
        assertNull("a snapshot with no vault must clear the archive's salt on undo", contents.vaultSalt)
        assertNull("a snapshot with no deck must clear the archive's deck on undo", contents.flashcards)
    }

    @Test
    fun `a sidecar for different database bytes is refused`() {
        val backup = backup()
        assertTrue(SnapshotSidecar.write(backup, "S1", "A"))

        // The classic crash window: the database was replaced but the sidecar was not rewritten.
        backup.writeBytes("different database".toByteArray())

        assertEquals(SnapshotSidecar.ReadResult.Mismatch, SnapshotSidecar.read(backup))
    }

    @Test
    fun `a malformed sidecar is refused rather than treated as missing`() {
        val backup = backup()
        SnapshotSidecar.fileFor(backup).writeText("not json", Charsets.UTF_8)

        assertEquals(SnapshotSidecar.ReadResult.Mismatch, SnapshotSidecar.read(backup))
    }

    @Test
    fun `a missing sidecar is reported as missing`() {
        assertEquals(SnapshotSidecar.ReadResult.Missing, SnapshotSidecar.read(backup()))
    }

    @Test
    fun `a fresh install without a previous database writes nothing`() {
        val missing = File(temporaryFolder.root, "note-list.db.bak")

        assertTrue(SnapshotSidecar.write(missing, "S1", "A"))
        assertFalse(SnapshotSidecar.fileFor(missing).exists())
    }
}

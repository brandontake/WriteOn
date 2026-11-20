package com.ezpnix.writeon.data.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAT-2: retention only counts archives that finished and authenticated.
 *
 * These are plain-file tests over [BackupRotation]: the worker's encryption needs the Android
 * Keystore, but the bug being pinned is in the folder arithmetic - a truncated write used to enter
 * rotation under the final `.zip.enc` name and push out a working backup.
 *
 * Robolectric only supplies `android.util.Log`, which the rotation logs through; the files
 * themselves are plain `java.io`.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRotationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val baseTime = 1_700_000_000_000L

    /** Creates a file of [size] bytes with a deterministic age (higher = newer). */
    private fun archive(name: String, size: Int, ageMillis: Long): File =
        File(temporaryFolder.root, name).apply {
            writeBytes(ByteArray(size) { index -> index.toByte() })
            setLastModified(baseTime + ageMillis)
        }

    @Test
    fun `partial archives are never counted or deleted by rotation`() {
        val newest = archive("new.zip.enc", 100, 2_000)
        val older = archive("old.zip.enc", 100, 1_000)
        val part = archive("new.zip.enc.part", 100, 3_000)

        BackupRotation.rotate(
            temporaryFolder.root,
            maxArchives = 1,
            minArchives = 1,
            maxTotalBytes = Long.MAX_VALUE,
        )

        assertTrue("a part is not a restore point", part.exists())
        assertTrue(newest.exists())
        assertFalse("the count budget must still apply to real archives", older.exists())
    }

    @Test
    fun `zero-length archives are not restore points and are cleaned up`() {
        val valid = (1..3).map { archive("valid-$it.zip.enc", 10, it * 1_000L) }
        val empty = archive("empty.zip.enc", 0, 4_000)

        BackupRotation.rotate(
            temporaryFolder.root,
            maxArchives = 3,
            minArchives = 2,
            maxTotalBytes = Long.MAX_VALUE,
        )

        assertFalse("an interrupted old write must not occupy a retention slot", empty.exists())
        assertEquals(3, valid.count { it.exists() })
    }

    @Test
    fun `newest minimum archives survive an exceeded size budget`() {
        val archives = (1..5).map { archive("a$it.zip.enc", 32, it * 1_000L) }

        BackupRotation.rotate(
            temporaryFolder.root,
            maxArchives = 7,
            minArchives = 3,
            maxTotalBytes = 1,
        )

        assertEquals(
            "the size budget may not cut below the newest three restore points",
            listOf("a3.zip.enc", "a4.zip.enc", "a5.zip.enc"),
            archives.filter { it.exists() }.map { it.name },
        )
    }

    @Test
    fun `archives beyond the count budget are rotated oldest first`() {
        val archives = (1..10).map { archive("b%02d.zip.enc".format(it), 10, it * 1_000L) }

        BackupRotation.rotate(
            temporaryFolder.root,
            maxArchives = 7,
            minArchives = 3,
            maxTotalBytes = Long.MAX_VALUE,
        )

        assertEquals(
            (4..10).map { "b%02d.zip.enc".format(it) },
            archives.filter { it.exists() }.map { it.name },
        )
    }

    @Test
    fun `stale parts are removed while fresh ones are left to the run that owns them`() {
        val now = baseTime
        val stale = archive("stale.zip.enc.part", 10, -25L * 60 * 60 * 1000)
        val fresh = archive("fresh.zip.enc.part", 10, -1_000)

        BackupRotation.deleteStaleParts(temporaryFolder.root, now = now)

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }
}

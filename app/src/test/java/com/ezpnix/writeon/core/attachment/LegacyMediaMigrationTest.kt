package com.ezpnix.writeon.core.attachment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SEC-08: the one-time plaintext-media migration.
 *
 * The header policy and the walk are plain file logic, so most of this runs against a temporary
 * tree and a fake encryptor. Only the completion marker needs a Context; both retry cases are
 * driven through [LegacyMediaMigration.migrateIfNeeded] with injected roots, which keeps the test
 * independent of whatever Robolectric's shared external root happens to contain.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyMediaMigrationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearMarker() {
        // deleteSharedPreferences, not prefs.edit().clear(): a previous test's SharedPreferences
        // instance can keep serving its in-memory map, while the migration reads a fresh file.
        context.deleteSharedPreferences(LegacyMediaMigration.PREFS_NAME)
    }

    @Test
    fun `only a regular file without the container header needs the pass`() {
        val plain = temporaryFolder.newFile("picture.jpg").apply { writeText("jpeg-bytes") }
        val sealed = temporaryFolder.newFile("recording.m4a").apply {
            writeBytes(wmv1Header() + "ciphertext".toByteArray())
        }
        val shortFile = temporaryFolder.newFile("tiny").apply { writeText("WMV") }
        val directory = temporaryFolder.newFolder("not-a-file")

        assertTrue("plaintext media is the migration target", LegacyMediaMigration.needsAtRestEncryption(plain))
        assertFalse("an existing WMV1 file must never be re-encrypted", LegacyMediaMigration.needsAtRestEncryption(sealed))
        assertTrue("a header shorter than five bytes is not a container", LegacyMediaMigration.needsAtRestEncryption(shortFile))
        assertFalse("only regular files are candidates", LegacyMediaMigration.needsAtRestEncryption(directory))
        assertFalse(
            "a missing path is never a candidate",
            LegacyMediaMigration.needsAtRestEncryption(File(temporaryFolder.root, "missing.jpg"))
        )
    }

    @Test
    fun `a pass encrypts only plaintext direct children and counts the already encrypted`() {
        val images = temporaryFolder.newFolder("attachments")
        val audio = temporaryFolder.newFolder("audios")
        val plainImage = File(images, "image.jpg").apply { writeText("plain-image") }
        val plainAudio = File(audio, "voice.m4a").apply { writeText("plain-audio") }
        val alreadySealed = File(images, "sealed.jpg").apply {
            writeBytes(wmv1Header() + "ciphertext".toByteArray())
        }
        val nestedDirectory = File(images, "sub").apply { mkdirs() }
        val nestedFile = File(nestedDirectory, "nested.jpg").apply { writeText("nested") }
        val unrelated = temporaryFolder.newFile("outside.jpg").apply { writeText("outside") }

        val encryptedNames = mutableListOf<String>()
        val result = LegacyMediaMigration.migrateDirectories(
            directories = listOf(images, audio, File(temporaryFolder.root, "missing")),
            encrypt = { file ->
                encryptedNames += file.name
                file.writeBytes(wmv1Header() + "sealed".toByteArray())
                true
            },
            recentWindowMillis = 0,
        )

        assertEquals("both plaintext files are encrypted", 2, result.encrypted)
        assertEquals("the container is counted, not rewritten", 1, result.alreadyEncrypted)
        assertEquals("nothing failed", 0, result.failures)
        assertTrue(result.complete)
        assertEquals(setOf("image.jpg", "voice.m4a"), encryptedNames.toSet())
        assertEquals("subdirectories are outside this pass", "nested", nestedFile.readText())
        assertEquals("files outside the roots are untouched", "outside", unrelated.readText())
        assertTrue(MediaFileCipher.hasEncryptedHeader(plainImage))
        assertTrue(MediaFileCipher.hasEncryptedHeader(plainAudio))
        assertTrue(MediaFileCipher.hasEncryptedHeader(alreadySealed))
    }

    @Test
    fun `a failed file leaves the marker absent and the next pass retries only it`() {
        val images = temporaryFolder.newFolder("attachments")
        val failing = File(images, "failing.jpg").apply { writeText("plain") }
        val sealing = File(images, "sealing.jpg").apply { writeText("plain") }

        val first = LegacyMediaMigration.migrateIfNeeded(
            context = context,
            directories = listOf(images),
            encrypt = { file ->
                if (file == failing) {
                    false
                } else {
                    file.writeBytes(wmv1Header())
                    true
                }
            },
            recentWindowMillis = 0,
        )

        assertEquals(1, first.encrypted)
        assertEquals(1, first.failures)
        assertFalse("a partial pass must not mark the tree as done", first.complete)
        assertFalse(LegacyMediaMigration.isComplete(context))
        assertTrue("the file that did succeed is already a container", MediaFileCipher.hasEncryptedHeader(sealing))
        assertEquals("the failed file is still plaintext", "plain", failing.readText())

        val attempted = mutableListOf<String>()
        val second = LegacyMediaMigration.migrateIfNeeded(
            context = context,
            directories = listOf(images),
            encrypt = { file ->
                attempted += file.name
                file.writeBytes(wmv1Header())
                true
            },
            recentWindowMillis = 0,
        )

        assertEquals("the retry only touches the file that failed", listOf("failing.jpg"), attempted)
        assertEquals(1, second.encrypted)
        assertEquals("the earlier success is skipped by its header", 1, second.alreadyEncrypted)
        assertEquals(0, second.failures)
        assertTrue("a clean pass writes the marker", LegacyMediaMigration.isComplete(context))

        val third = LegacyMediaMigration.migrateIfNeeded(
            context = context,
            directories = listOf(images),
            encrypt = { error("a completed migration must not touch the tree again") },
        )

        assertEquals("the completed marker short-circuits the scan", 0, third.attempted)
        assertEquals(0, third.failures)
        assertTrue(LegacyMediaMigration.isComplete(context))
    }

    @Test
    fun `an encryptor that throws is a failure and keeps the marker absent`() {
        val images = temporaryFolder.newFolder("attachments")
        File(images, "hostile.jpg").writeText("plain")

        val result = LegacyMediaMigration.migrateIfNeeded(
            context = context,
            directories = listOf(images),
            encrypt = { throw IllegalStateException("no key") },
            recentWindowMillis = 0,
        )

        assertEquals(1, result.failures)
        assertFalse(result.complete)
        assertFalse(LegacyMediaMigration.isComplete(context))
    }

    @Test
    fun `a file that is still being written is deferred instead of renamed underneath the writer`() {
        val audios = temporaryFolder.newFolder("audios")
        val recording = File(audios, "REC_now.m4a").apply { writeText("recording-so-far") }
        recording.setLastModified(System.currentTimeMillis())

        val deferredPass = LegacyMediaMigration.migrateIfNeeded(
            context = context,
            directories = listOf(audios),
            encrypt = { error("an actively written file must not be encrypted") },
            recentWindowMillis = 60_000,
        )

        assertEquals("the recent file is deferred", 1, deferredPass.deferred)
        assertFalse("a deferred file must keep the marker absent", deferredPass.complete)
        assertFalse(LegacyMediaMigration.isComplete(context))
        assertEquals("the writer's bytes are untouched", "recording-so-far", recording.readText())

        // The next launch, after the writer stopped, encrypts it normally.
        val nextPass = LegacyMediaMigration.migrateIfNeeded(
            context = context,
            directories = listOf(audios),
            encrypt = { file ->
                file.writeBytes(wmv1Header())
                true
            },
            recentWindowMillis = 0,
        )

        assertEquals(1, nextPass.encrypted)
        assertTrue(LegacyMediaMigration.isComplete(context))
    }

    @Test
    fun `the snapshot-undo reset makes the next launch scan again`() {
        val images = temporaryFolder.newFolder("attachments")
        File(images, "image.jpg").apply {
            writeBytes(wmv1Header())
        }

        val completed = LegacyMediaMigration.migrateIfNeeded(
            context = context,
            directories = listOf(images),
            encrypt = { error("an already encrypted file must not be touched") },
        )
        assertTrue(completed.complete)
        assertTrue(LegacyMediaMigration.isComplete(context))

        LegacyMediaMigration.reset(context)

        assertFalse(
            "a restored .bak attachment tree may be plaintext again; the next launch must rescan",
            LegacyMediaMigration.isComplete(context)
        )
    }

    private fun wmv1Header(): ByteArray =
        byteArrayOf('W'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte(), 1)
}

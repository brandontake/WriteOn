package com.ezpnix.writeon.core.attachment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.domain.model.Attachment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SEC-4: the at-rest contract of the archive and delete paths, as far as a JVM can see it.
 *
 * Robolectric has no Android Keystore, so this file deliberately only covers the policies that do
 * not need one:
 * - [AttachmentArchive.writeTo] with a `Context` copies plaintext unchanged and **throws** when a
 *   member cannot be copied (the difference between a backup and a backup that looks fine but
 *   contains an unreadable file);
 * - the deprecated two-argument overload still copies bytes raw, which is why no production
 *   backup may call it;
 * - [AttachmentFiles.deletePath] and [AttachmentFiles.quarantine] remove the attachment's
 *   decrypted cache copy as well;
 * - [AttachmentArchive.recoverPendingInstall] re-encrypts installed targets and treats a missing
 *   key as non-fatal.
 *
 * The encryption itself is [MediaFileCipher]'s job and is covered by [MediaVaultFormatTest]; the
 * Keystore half stays instrumented.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentMediaAtRestTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `plaintext media is copied unchanged through the context overload`() {
        val image = temporaryFolder.newFile("photo.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val audio = temporaryFolder.newFile("memo.m4a").apply { writeBytes(byteArrayOf(4, 5, 6)) }

        val entries = zipEntries(
            listOf(
                attachment("img001", Attachment.TYPE_IMAGE, image),
                attachment("aud002", Attachment.TYPE_AUDIO, audio)
            )
        )

        assertEquals(
            setOf("attachments/img001.png", "attachments/aud002.m4a"),
            entries.keys
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), entries.getValue("attachments/img001.png"))
        assertArrayEquals(byteArrayOf(4, 5, 6), entries.getValue("attachments/aud002.m4a"))
    }

    @Test
    fun `a member that cannot be decrypted aborts the archive`() {
        // A WMV1 header on bytes no key can open: without the Keystore `MediaVault.copyTo` fails,
        // and the archive must fail with it rather than store the ciphertext as if it were media.
        val broken = temporaryFolder.newFile("broken.m4a").apply { writeBytes(wmvHeader()) }

        val failure = assertThrows(IOException::class.java) {
            AttachmentArchive.writeTo(
                ZipOutputStream(ByteArrayOutputStream()),
                listOf(attachment("aud-broken", Attachment.TYPE_AUDIO, broken)),
                context
            )
        }

        assertTrue(
            "the message must name the member so a log points at the file: ${failure.message}",
            failure.message.orEmpty().contains("aud-broken")
        )
    }

    @Test
    fun `deleting an attachment also removes its decrypted cache copy`() {
        val attachmentsDir = File(context.filesDir, AttachmentArchive.DIRECTORY).apply { mkdirs() }
        val source = File(attachmentsDir, "img-cache-test.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val cached = cacheCopyFor(source)

        assertTrue(AttachmentFiles.deletePath(context, source.absolutePath, Attachment.TYPE_IMAGE))

        assertFalse("the source file is gone", source.exists())
        assertFalse("a plaintext copy must not outlive the attachment", cached.isFile)
    }

    @Test
    fun `quarantining an attachment also removes its decrypted cache copy`() {
        val attachmentsDir = File(context.filesDir, AttachmentArchive.DIRECTORY).apply { mkdirs() }
        val source = File(attachmentsDir, "img-quarantine-test.jpg").apply {
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val cached = cacheCopyFor(source)

        assertTrue(AttachmentFiles.quarantine(context, source))

        assertFalse("the live file moved out of the library", source.exists())
        assertFalse(
            "quarantine is not a back door that keeps a plaintext cache copy alive",
            cached.isFile
        )
        val quarantined = AttachmentFiles.quarantineDirectory(context)
            .walkTopDown()
            .firstOrNull { it.isFile && it.name == source.name }
        assertTrue("the bytes stay recoverable inside quarantine", quarantined?.isFile == true)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `the deprecated raw overload still copies bytes unchanged`() {
        // Documents the old contract the deprecated overload keeps for tests/legacy callers: it
        // copies the stored file byte for byte. That is exactly why no production backup may use
        // it - a `WMV1` source would be archived as ciphertext.
        val image = temporaryFolder.newFile("photo.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val attachments = listOf(attachment("img-raw", Attachment.TYPE_IMAGE, image))

        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip -> AttachmentArchive.writeTo(zip, attachments) }
        }.toByteArray()
        val entries = unzip(archive)

        assertArrayEquals(byteArrayOf(1, 2, 3), entries.getValue("attachments/img-raw.png"))
    }

    @Test
    fun `recovery re-encrypts installed targets without failing when the key is unavailable`() {
        // SEC-4: a replay installs plaintext archive members, so the Context variant tries to
        // re-encrypt each installed target. Robolectric has no usable Keystore, which is the
        // important half of the contract here: a failed encryption is logged, not fatal, and the
        // recovered file must survive it intact.
        val workDir = AttachmentArchive.workDirectory(context)
        val stagedDir = temporaryFolder.newFolder("staged")
        val targetDir = File(context.filesDir, AttachmentArchive.DIRECTORY).apply { mkdirs() }

        val source = File(stagedDir, "img-recover.jpg").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val target = File(targetDir, "img-recover.jpg")
        // A second entry whose source is gone: installed before the kill, so recovery only
        // encrypts the live target.
        val alreadyInstalled = File(targetDir, "img-already-installed.jpg").apply {
            writeBytes(byteArrayOf(1, 1, 1))
        }
        AttachmentArchive.writeInstallJournal(
            workDir,
            listOf(
                source to target,
                File(stagedDir, "gone.jpg") to alreadyInstalled
            )
        )

        val recovered = AttachmentArchive.recoverPendingInstall(context)

        assertEquals(1, recovered)
        assertTrue("the staged member was installed", target.isFile)
        assertTrue("an already installed target is left in place", alreadyInstalled.isFile)
        assertFalse("recovery finished the plan", AttachmentArchive.hasPendingInstall(context))
        if (!MediaFileCipher.hasEncryptedHeader(target)) {
            assertArrayEquals(
                "a failed encryption must leave the recovered bytes untouched",
                byteArrayOf(7, 8, 9),
                target.readBytes()
            )
        }
    }

    private fun cacheCopyFor(source: File): File = File(
        File(context.cacheDir, MediaVault.CACHE_DIRECTORY).apply { mkdirs() },
        MediaCacheNames.fileName(
            source.absolutePath,
            source.length(),
            source.lastModified(),
            source.extension
        )
    ).apply { writeBytes(byteArrayOf(9, 9, 9)) }

    private fun attachment(id: String, type: String, file: File): Attachment =
        Attachment(id = id, type = type, path = file.absolutePath)

    private fun zipEntries(attachments: Collection<Attachment>): Map<String, ByteArray> {
        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                AttachmentArchive.writeTo(zip, attachments, context)
            }
        }.toByteArray()
        return unzip(archive)
    }

    private fun unzip(archive: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    /** The header [MediaFileCipher] recognises, without a payload any key could authenticate. */
    private fun wmvHeader(): ByteArray {
        val header = ByteArray(MediaFileCipher.HEADER_BYTES)
        "WMV1".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[4] = MediaFileCipher.FORMAT_VERSION.toByte()
        return header
    }
}

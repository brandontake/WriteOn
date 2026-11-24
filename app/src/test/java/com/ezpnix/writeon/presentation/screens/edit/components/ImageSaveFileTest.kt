package com.ezpnix.writeon.presentation.screens.edit.components

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAT-08: an image pick must never truncate the attachment already behind the target name.
 *
 * The target name is derived from the picker URI, so picking the same picture twice used to reopen
 * the same path with `FileOutputStream` and destroy the live bytes - and a `.bak` attachment
 * snapshot hard-linked to them. These tests drive the file half with an injected encryptor: the
 * failure case is the one that proves the old target survives, the success case proves the publish
 * is a rename, and both check that no staging file is left behind.
 */
@RunWith(RobolectricTestRunner::class)
class ImageSaveFileTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun directory(): File = temporaryFolder.newFolder("attachments")

    private fun wmv1Header(): ByteArray =
        byteArrayOf('W'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte(), 1)

    private fun contents(directory: File): Set<String> =
        directory.listFiles().orEmpty().map { it.name }.toSet()

    @Test
    fun `a failed encryption leaves an existing target byte for byte`() {
        val directory = directory()
        val target = File(directory, "photo.jpg").apply { writeBytes("old-image".toByteArray()) }

        val result = saveImageFile(
            directory = directory,
            fileName = target.name,
            source = ByteArrayInputStream("new-image".toByteArray()),
            encrypt = { false },
        )

        assertTrue("the save must fail", result is ImageSaveResult.Failure)
        assertEquals(
            "the caller must hear the encryption reason, not a generic write failure",
            com.ezpnix.writeon.R.string.image_insert_encrypt_failed,
            (result as ImageSaveResult.Failure).reasonRes
        )
        assertArrayEquals("the existing attachment must survive", "old-image".toByteArray(), target.readBytes())
        assertEquals("only the target remains; the staging file was deleted", setOf("photo.jpg"), contents(directory))
    }

    @Test
    fun `a successful run replaces the target with the encrypted bytes and leaves no temp`() {
        val directory = directory()
        val target = File(directory, "photo.jpg").apply { writeBytes("old-image".toByteArray()) }
        val encryptedPaths = mutableListOf<File>()

        val result = saveImageFile(
            directory = directory,
            fileName = target.name,
            source = ByteArrayInputStream("new-image".toByteArray()),
            encrypt = { temp ->
                encryptedPaths += temp
                temp.writeBytes(wmv1Header() + "ciphertext".toByteArray())
                true
            },
        )

        assertTrue("the save must succeed", result is ImageSaveResult.Success)
        assertEquals(target.absolutePath, (result as ImageSaveResult.Success).path)
        assertTrue("the target holds the encrypted container", MediaVaultHeader.hasHeader(target))
        assertEquals("the old bytes are gone from the live entry", false, target.readText().contains("old-image"))
        assertEquals("encryption ran on the sibling, not the target", 1, encryptedPaths.size)
        assertTrue("the staging file lives in the attachment directory", encryptedPaths.single().parentFile == directory)
        assertTrue("the staging file is not the target", encryptedPaths.single().name != target.name)
        assertEquals("no staging file survives the publish", setOf("photo.jpg"), contents(directory))
    }

    @Test
    fun `a pick over the size limit leaves an existing target untouched and creates no temp`() {
        val directory = directory()
        val target = File(directory, "photo.jpg").apply { writeBytes("old-image".toByteArray()) }

        val result = saveImageFile(
            directory = directory,
            fileName = target.name,
            source = ByteArrayInputStream(ByteArray(64) { 1 }),
            maxBytes = 16,
            encrypt = { error("an oversized pick must not reach the cipher") },
        )

        assertTrue("the save must fail", result is ImageSaveResult.Failure)
        assertEquals(
            com.ezpnix.writeon.R.string.image_insert_too_large,
            (result as ImageSaveResult.Failure).reasonRes
        )
        assertArrayEquals("the existing attachment must survive a rejected pick", "old-image".toByteArray(), target.readBytes())
        assertEquals("no partial copy is left", setOf("photo.jpg"), contents(directory))
    }

    @Test
    fun `replacing a pick does not mutate the snapshot sharing its inode`() {
        val directory = directory()
        val target = File(directory, "photo.jpg").apply { writeBytes("old-image".toByteArray()) }
        val snapshot = File(temporaryFolder.newFolder("snapshot"), "photo.jpg")
        val linked = try {
            Files.createLink(snapshot.toPath(), target.toPath())
            true
        } catch (e: Exception) {
            // Some filesystems (and non-NTFS Windows volumes) refuse links; the snapshot would
            // then be a byte copy and this test would prove nothing about the inode.
            false
        }
        assumeTrue("hard links are not supported on this filesystem", linked)

        val result = saveImageFile(
            directory = directory,
            fileName = target.name,
            source = ByteArrayInputStream("new-image".toByteArray()),
            encrypt = { temp ->
                temp.writeBytes(wmv1Header() + "ciphertext".toByteArray())
                true
            },
        )

        assertTrue("the save must succeed", result is ImageSaveResult.Success)
        assertArrayEquals(
            "the .bak snapshot keeps the old inode's bytes; the rename must not truncate them",
            "old-image".toByteArray(),
            snapshot.readBytes()
        )
        assertTrue("the live entry holds the new encrypted bytes", MediaVaultHeader.hasHeader(target))
    }

    @Test
    fun `the attachment directory is created on demand`() {
        val parent = temporaryFolder.newFolder("files")
        val directory = File(parent, "attachments")

        val result = saveImageFile(
            directory = directory,
            fileName = "first.jpg",
            source = ByteArrayInputStream("bytes".toByteArray()),
            encrypt = { temp ->
                temp.writeBytes(wmv1Header())
                true
            },
        )

        assertTrue("the save must succeed", result is ImageSaveResult.Success)
        assertTrue("the directory is created for the first saved image", directory.isDirectory)
        assertEquals(setOf("first.jpg"), contents(directory))
    }

    /**
     * Header check without touching [MediaVault], which needs an Android Keystore on some paths.
     * The container magic is the only thing these tests care about.
     */
    private object MediaVaultHeader {
        fun hasHeader(file: File): Boolean {
            if (!file.isFile || file.length() < 5) return false
            val head = file.readBytes().copyOf(5)
            return String(head, 0, 4, Charsets.US_ASCII) == "WMV1"
        }
    }
}

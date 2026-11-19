package com.ezpnix.writeon.core.attachment

import com.ezpnix.writeon.domain.model.Attachment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the archive half of [AttachmentArchive] (T7).
 *
 * [AttachmentArchive.writeTo] only needs ordinary files and a zip stream, so the entry naming,
 * extension fallback, deduplication and skip rules are all testable without a Context. The restore
 * half needs a Context, a SQLite database and the Room identity hash, and stays with the
 * on-device restore tests.
 */
class AttachmentArchiveTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `entries use the attachments directory and the id plus file extension`() {
        val image = temporaryFolder.newFile("photo.png").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val audio = temporaryFolder.newFile("memo.m4a").apply {
            writeBytes(byteArrayOf(4, 5, 6))
        }

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
    fun `an audio file without an extension falls back to m4a`() {
        val recording = temporaryFolder.newFile("recording").apply {
            writeBytes(byteArrayOf(7))
        }

        val entries = zipEntries(
            listOf(attachment("aud003", Attachment.TYPE_AUDIO, recording))
        )

        assertEquals(setOf("attachments/aud003.m4a"), entries.keys)
    }

    @Test
    fun `an image with an unusable extension falls back to jpg`() {
        // Longer than the 8-character guard, and a hyphen is not allowed either.
        val longExtension = temporaryFolder.newFile("scan.verylongextension")
        val hyphenated = temporaryFolder.newFile("scan.bad-ext")

        val entries = zipEntries(
            listOf(
                attachment("img004", Attachment.TYPE_IMAGE, longExtension),
                attachment("img005", Attachment.TYPE_IMAGE, hyphenated)
            )
        )

        assertEquals(
            setOf("attachments/img004.jpg", "attachments/img005.jpg"),
            entries.keys
        )
    }

    @Test
    fun `blank paths missing files and directories are skipped`() {
        val directory = temporaryFolder.newFolder("not-a-file")
        val missing = java.io.File(temporaryFolder.root, "missing.png")

        val entries = zipEntries(
            listOf(
                attachment("blank", Attachment.TYPE_IMAGE, null),
                attachment("missing", Attachment.TYPE_IMAGE, missing),
                attachment("directory", Attachment.TYPE_IMAGE, directory)
            )
        )

        assertTrue("unreadable sources must not produce archive entries", entries.isEmpty())
    }

    @Test
    fun `duplicate ids are written once`() {
        val first = temporaryFolder.newFile("first.png").apply {
            writeBytes(byteArrayOf(1))
        }
        val second = temporaryFolder.newFile("second.png").apply {
            writeBytes(byteArrayOf(2))
        }

        val entries = zipEntries(
            listOf(
                attachment("same-id", Attachment.TYPE_IMAGE, first),
                attachment("same-id", Attachment.TYPE_IMAGE, second)
            )
        )

        assertEquals(setOf("attachments/same-id.png"), entries.keys)
        assertArrayEquals(
            "the first row wins; a duplicate id must not overwrite its entry",
            byteArrayOf(1),
            entries.getValue("attachments/same-id.png")
        )
    }

    @Test
    fun `the archive directory name is frozen`() {
        assertEquals("attachments", AttachmentArchive.DIRECTORY)
    }

    private fun attachment(id: String, type: String, file: java.io.File?): Attachment =
        Attachment(
            id = id,
            type = type,
            path = file?.absolutePath.orEmpty()
        )

    private fun zipEntries(attachments: Collection<Attachment>): Map<String, ByteArray> {
        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                AttachmentArchive.writeTo(zip, attachments)
            }
        }.toByteArray()

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
}

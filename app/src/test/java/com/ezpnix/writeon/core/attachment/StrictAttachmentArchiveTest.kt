package com.ezpnix.writeon.core.attachment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.data.backup.BackupArchiveWriter
import com.ezpnix.writeon.domain.model.Attachment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAT-09: a backup producer must fail rather than silently omit a referenced live attachment.
 *
 * The old context overload skipped a row whose file was missing or unreadable, so a pruned or
 * half-restored library produced archives that reported success while missing media. Production
 * must be strict; the explicit `skipMissing` flag keeps the legacy behaviour available.
 */
@RunWith(RobolectricTestRunner::class)
class StrictAttachmentArchiveTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var mediaRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mediaRoot = File(context.filesDir, AttachmentArchive.DIRECTORY).apply { mkdirs() }
    }

    @Test
    fun `a missing referenced file fails the archive`() {
        val good = File(mediaRoot, "strict-good-${System.nanoTime()}.jpg").apply {
            writeText("image bytes")
        }
        val missing = File(temporaryFolder.root, "gone.jpg")

        val failure = assertThrows(IOException::class.java) {
            AttachmentArchive.writeTo(
                ZipOutputStream(ByteArrayOutputStream()),
                listOf(
                    attachment("good", Attachment.TYPE_IMAGE, good),
                    attachment("missing", Attachment.TYPE_IMAGE, missing),
                ),
                context,
            )
        }

        assertTrue(
            "the failure must name the missing row: ${failure.message}",
            failure.message.orEmpty().contains("missing")
        )
        good.delete()
    }

    @Test
    fun `the shared backup writer aborts instead of producing a partial archive`() {
        val portable = File(temporaryFolder.newFolder("portable"), "portable.db").apply {
            writeText("portable database bytes")
        }
        val missing = File(temporaryFolder.root, "gone.jpg")

        assertThrows(IOException::class.java) {
            ZipOutputStream(ByteArrayOutputStream()).use { zip ->
                BackupArchiveWriter.write(
                    zip = zip,
                    context = context,
                    portableDatabase = portable,
                    attachments = listOf(attachment("missing", Attachment.TYPE_IMAGE, missing)),
                )
            }
        }
    }

    @Test
    fun `a successful archive still contains every readable member`() {
        val image = File(mediaRoot, "strict-image-${System.nanoTime()}.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val audio = File(mediaRoot, "strict-audio-${System.nanoTime()}.m4a").apply {
            writeBytes(byteArrayOf(4, 5, 6))
        }

        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                AttachmentArchive.writeTo(
                    zip,
                    listOf(
                        attachment("img", Attachment.TYPE_IMAGE, image),
                        attachment("aud", Attachment.TYPE_AUDIO, audio),
                    ),
                    context,
                )
            }
        }.toByteArray()

        val entries = unzip(archive)
        assertArrayEquals(byteArrayOf(1, 2, 3), entries.getValue("attachments/img.jpg"))
        assertArrayEquals(byteArrayOf(4, 5, 6), entries.getValue("attachments/aud.m4a"))
        assertEquals(2, entries.size)

        image.delete()
        audio.delete()
    }

    @Test
    fun `the explicit skip flag keeps the legacy omission behaviour`() {
        val good = File(mediaRoot, "strict-skip-${System.nanoTime()}.jpg").apply {
            writeText("kept")
        }

        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                AttachmentArchive.writeTo(
                    zip,
                    listOf(
                        attachment("good", Attachment.TYPE_IMAGE, good),
                        attachment("missing", Attachment.TYPE_IMAGE, File(temporaryFolder.root, "gone.jpg")),
                    ),
                    context,
                    skipMissing = true,
                )
            }
        }.toByteArray()

        assertEquals(setOf("attachments/good.jpg"), unzip(archive).keys)
        good.delete()
    }

    private fun attachment(id: String, type: String, file: File): Attachment =
        Attachment(id = id, type = type, path = file.absolutePath)

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
}

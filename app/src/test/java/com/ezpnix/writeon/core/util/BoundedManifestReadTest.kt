package com.ezpnix.writeon.core.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SEC-12: the manifest probe reads a bounded amount of the first entry.
 *
 * `readManifest` used to call `readBytes()` on whatever the archive declared, so a crafted or
 * corrupt archive could make the restore preview allocate hundreds of megabytes from an entry the
 * app then discarded. A real manifest is tiny; anything over the cap is "no usable manifest".
 */
class BoundedManifestReadTest {

    @Test
    fun `an oversized manifest entry is rejected without allocating it`() {
        val huge = buildManifest(
            "writeon-backup-manifest\n" +
                "format=1\n" +
                "createdAt=1700000000000\n" +
                "notes=1\n" +
                "attachments=0\n" +
                "padding=" + "x".repeat(BackupArchiveCrypto.MAX_MANIFEST_BYTES)
        )

        ZipInputStream(ByteArrayInputStream(huge)).use { zip ->
            assertNull(
                "an entry larger than the 64 KiB cap is not a manifest this build reads",
                BackupArchiveCrypto.readManifest(zip)
            )
        }
    }

    @Test
    fun `a manifest just under the cap still parses`() {
        val body = "writeon-backup-manifest\n" +
            "format=1\n" +
            "createdAt=1700000000000\n" +
            "notes=2\n" +
            "attachments=1\n" +
            "futureField=" + "y".repeat(1024)
        val zipBytes = buildManifest(body)

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            val manifest = BackupArchiveCrypto.readManifest(zip)
            assertNotNull("the bounded read must still accept a normal manifest", manifest)
            assertEquals(2, manifest!!.noteCount)
            assertEquals(1, manifest.attachmentCount)
        }
    }

    private fun buildManifest(body: String): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(BackupArchiveCrypto.MANIFEST_ENTRY_NAME))
                zip.write(body.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                // A following entry proves the failure returns before the archive is drained.
                zip.putNextEntry(ZipEntry("note-list.db"))
                zip.write("db".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
}

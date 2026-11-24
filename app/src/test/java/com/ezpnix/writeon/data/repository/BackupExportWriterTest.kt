package com.ezpnix.writeon.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.data.backup.BackupArchiveWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAT-06: an export must not truncate the user's chosen backup before the archive is ready.
 *
 * The old pipeline opened the SAF destination first (which truncates an existing document) and
 * built the zip afterwards, so a failing attachment copy destroyed the previous good backup. The
 * writer seam is what lets these tests fail the archive build deterministically.
 */
@RunWith(RobolectricTestRunner::class)
class BackupExportWriterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a failing archive writer leaves an existing destination untouched`() {
        runBlocking {
            val destination = File(context.cacheDir, "export-target-${System.nanoTime()}.zip").apply {
                writeText("previous good backup")
            }

            // The public export path maps this exception to BackupResult.Error; the property under
            // test is that the destination is still untouched when the builder fails.
            val failure = runCatching {
                BackupExportWriter.write(
                    context = context,
                    destination = Uri.fromFile(destination),
                    password = null,
                    writePortableCopy = { file ->
                        file.writeText("portable database")
                        emptyList()
                    },
                ) { _, _, _ ->
                    throw IOException("attachment copy failed")
                }
            }

            assertTrue("the failure must be reported", failure.isFailure)
            assertEquals(
                "the destination must not be opened before the archive exists",
                "previous good backup",
                destination.readText()
            )
            destination.delete()
        }
    }

    @Test
    fun `a complete archive is copied to the destination`() {
        runBlocking {
            val destination = File(context.cacheDir, "export-target-${System.nanoTime()}.zip")

            val result = BackupExportWriter.write(
                context = context,
                destination = Uri.fromFile(destination),
                password = null,
                writePortableCopy = { file ->
                    file.writeText("portable database")
                    emptyList()
                },
            ) { zip, attachments, portable ->
                // The production caller delegates to the shared writer; doing the same here proves
                // this temp-first path produces the common archive layout.
                BackupArchiveWriter.write(zip, context, portable, attachments)
            }

            assertNull(result)
            assertTrue(destination.length() > 0L)
            val entries = readEntries(destination)
            assertEquals(
                "the portable database entry must come first",
                DatabaseConst.NOTES_DATABASE_FILE_NAME,
                entries.first()
            )
            destination.delete()
        }
    }

    /** Entry names in archive order, read from the written file. */
    private fun readEntries(file: File): List<String> {
        val entries = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(file.readBytes())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += entry.name
                entry = zip.nextEntry
            }
        }
        return entries
    }
}

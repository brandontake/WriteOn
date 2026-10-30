package com.ezpnix.writeon.presentation.screens.settings

import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class TxtBackupWorkerTest {
    @Test
    fun `reading multiple entries keeps each entry content separate`() {
        val bytes = java.io.ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("1_First.txt"))
                zip.write("first\ncontent".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("2_Second.txt"))
                zip.write("second\ncontent".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            assertEquals("1_First.txt", zip.nextEntry.name)
            assertEquals("first\ncontent", TxtBackupHelper.readCurrentZipEntry(zip))
            zip.closeEntry()
            assertEquals("2_Second.txt", zip.nextEntry.name)
            assertEquals("second\ncontent", TxtBackupHelper.readCurrentZipEntry(zip))
        }
    }
}

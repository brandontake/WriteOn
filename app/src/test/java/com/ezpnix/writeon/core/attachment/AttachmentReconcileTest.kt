package com.ezpnix.writeon.core.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the D7 rows <-> files decision (see [AttachmentFiles.decideReconcile]).
 *
 * The decision is pure: row ids in, file names in, two sets out. Everything that touches a
 * Context, a database or the file system - plus the conservative "report rows, quarantine
 * files" policy - lives in the callers and is deliberately not exercised here.
 */
class AttachmentReconcileTest {

    @Test
    fun `rows and files that match produce no work`() {
        val decision = AttachmentFiles.decideReconcile(
            rowIds = setOf("abc12345", "def67890"),
            fileNames = setOf("abc12345.jpg", "def67890.m4a"),
        )
        assertTrue(decision.rowsWithoutFile.isEmpty())
        assertTrue(decision.orphanFiles.isEmpty())
    }

    @Test
    fun `a row whose file is gone is reported and never dropped silently`() {
        val decision = AttachmentFiles.decideReconcile(
            rowIds = setOf("abc12345", "gone0000"),
            fileNames = setOf("abc12345.jpg"),
        )
        assertEquals(setOf("gone0000"), decision.rowsWithoutFile)
        assertTrue(decision.orphanFiles.isEmpty())
    }

    @Test
    fun `a file no row references is an orphan candidate`() {
        val decision = AttachmentFiles.decideReconcile(
            rowIds = setOf("abc12345"),
            fileNames = setOf("abc12345.jpg", "stray.png"),
        )
        assertTrue(decision.rowsWithoutFile.isEmpty())
        assertEquals(setOf("stray.png"), decision.orphanFiles)
    }

    @Test
    fun `a picker-named file kept by its row path is neither missing nor orphaned`() {
        // Picked pictures are stored under their original name (IMG_20240101.jpg), not under the
        // attachment id: id-only matching would report the row missing AND quarantine its file.
        val decision = AttachmentFiles.decideReconcile(
            rowIds = setOf("abc12345"),
            fileNames = setOf("IMG_20240101.jpg"),
            rowPaths = mapOf("abc12345" to "/data/user/0/app/files/attachments/IMG_20240101.jpg"),
        )
        assertTrue(decision.rowsWithoutFile.isEmpty())
        assertTrue(decision.orphanFiles.isEmpty())
    }

    @Test
    fun `a row path protects a file even when only the stem matches`() {
        val decision = AttachmentFiles.decideReconcile(
            rowIds = setOf("abc12345"),
            fileNames = setOf("IMG_20240101.png"),
            rowPaths = mapOf("abc12345" to "/legacy/attachments/IMG_20240101.jpg"),
        )
        assertTrue(decision.orphanFiles.isEmpty())
    }

    @Test
    fun `a file without an extension matches a row by its whole name`() {
        val decision = AttachmentFiles.decideReconcile(
            rowIds = setOf("noext"),
            fileNames = setOf("noext"),
        )
        assertTrue(decision.rowsWithoutFile.isEmpty())
        assertTrue(decision.orphanFiles.isEmpty())
    }

    @Test
    fun `a name that cannot be attributed is left alone`() {
        // A blank stem (".nomedia") ties the file to no id at all; the conservative policy keeps it.
        val decision = AttachmentFiles.decideReconcile(
            rowIds = emptySet(),
            fileNames = setOf(".nomedia"),
        )
        assertTrue(decision.rowsWithoutFile.isEmpty())
        assertTrue(decision.orphanFiles.isEmpty())
    }

    @Test
    fun `a row whose file lives outside the scanned roots is reported`() {
        // Only app-owned roots are passed in; a file in the legacy external directory is not seen
        // here (the caller re-checks the real path before the report is emitted).
        val decision = AttachmentFiles.decideReconcile(
            rowIds = setOf("abc12345"),
            fileNames = emptySet(),
            rowPaths = mapOf("abc12345" to "/storage/emulated/0/Android/data/app/files/legacy.jpg"),
        )
        assertEquals(setOf("abc12345"), decision.rowsWithoutFile)
    }

    @Test
    fun `an empty library decides nothing`() {
        val decision = AttachmentFiles.decideReconcile(emptySet(), emptySet())
        assertTrue(decision.rowsWithoutFile.isEmpty())
        assertTrue(decision.orphanFiles.isEmpty())
    }
}

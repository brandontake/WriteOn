package com.ezpnix.writeon.core.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression tests for the restore path.
 *
 * The previous implementation opened the staged file with `FileOutputStream(file)` just to call
 * `fd.sync()`, which truncates the file (O_TRUNC). Every restore therefore ended with a zero-byte
 * database and an error, and no test covered the function. These tests use plain `java.io` only.
 */
class DatabaseRestoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun sqliteBytes(payload: String): ByteArray {
        val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val body = ByteArray(100 - header.size)
        return header + body + payload.toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `restoring keeps the replacement bytes and preserves a backup`() {
        val target = temporaryFolder.newFile("note-list.db").apply {
            writeBytes(sqliteBytes("old database"))
        }
        val replacement = temporaryFolder.newFile("note-list.db.restored").apply {
            writeBytes(sqliteBytes("new database"))
        }
        val oldBytes = target.readBytes()
        val newBytes = replacement.readBytes()

        val backup = DatabaseRestore.replaceDatabaseAtomically(target, replacement)

        assertNotNull("the previous database must be kept as .bak", backup)
        assertTrue("the .bak file must exist", backup!!.exists())
        assertArrayEquals("the restored database must keep every byte", newBytes, target.readBytes())
        assertArrayEquals("the .bak must hold the previous database", oldBytes, backup.readBytes())
        assertTrue("the staged file must not be left behind", !File(target.absolutePath + ".new").exists())
    }

    @Test
    fun `a first restore has no backup but still installs the database`() {
        val target = File(temporaryFolder.root, "note-list.db")
        val replacement = temporaryFolder.newFile("restored.db").apply {
            writeBytes(sqliteBytes("first database"))
        }

        val backup = DatabaseRestore.replaceDatabaseAtomically(target, replacement)

        assertNull(backup)
        assertArrayEquals(sqliteBytes("first database"), target.readBytes())
    }

    @Test
    fun `a non-sqlite archive is refused and leaves the live database untouched`() {
        val target = temporaryFolder.newFile("note-list.db").apply {
            writeBytes(sqliteBytes("live database"))
        }
        val oldBytes = target.readBytes()
        val replacement = temporaryFolder.newFile("not-a-database.db").apply {
            writeBytes("this is not sqlite".toByteArray())
        }

        var failed = false
        try {
            DatabaseRestore.replaceDatabaseAtomically(target, replacement)
        } catch (_: IOException) {
            failed = true
        }

        assertTrue("a corrupt archive must be rejected", failed)
        assertArrayEquals("the live database must not change", oldBytes, target.readBytes())
        assertTrue("the staged file must be cleaned up", !File(target.absolutePath + ".new").exists())
    }

    // -----------------------------------------------------------------------------------------
    // resolveEntry: the zip-slip guard. Archive entry names are attacker-controlled, so a name
    // that escapes the staging directory would let a crafted backup write anywhere the process
    // can reach. These are pure java.io and cover the canonicalisation the check relies on.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `resolveEntry keeps a normal member inside the staging directory`() {
        val dir = temporaryFolder.newFolder("stage")
        val resolved = DatabaseRestore.resolveEntry(dir, "attachments/abc.jpg")
        assertTrue(resolved.canonicalPath.startsWith(dir.canonicalPath))
    }

    @Test
    fun `resolveEntry rejects a traversal that escapes the directory`() {
        val dir = temporaryFolder.newFolder("stage")
        var rejected = false
        try {
            DatabaseRestore.resolveEntry(dir, "../../evil.db")
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue("a ../ traversal must be refused", rejected)
    }

    @Test
    fun `resolveEntry rejects an absolute path`() {
        val dir = temporaryFolder.newFolder("stage")
        // A genuinely absolute path on any platform (built from the temp root). An archive member
        // with such a name must never be written, wherever it points.
        val absolute = File(temporaryFolder.root, "outside.db").absolutePath
        var rejected = false
        try {
            DatabaseRestore.resolveEntry(dir, absolute)
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue("an absolute entry name must be refused", rejected)
    }

    // -----------------------------------------------------------------------------------------
    // extractAll: streams members out, enforces the per-entry and total budgets, and requires the
    // notes database to be present. A backup without its database is useless, so that is an error.
    // -----------------------------------------------------------------------------------------

    private fun zipWith(vararg members: Pair<String, ByteArray>): java.util.zip.ZipInputStream {
        val bytes = ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                members.forEach { (name, data) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        return java.util.zip.ZipInputStream(ByteArrayInputStream(bytes))
    }

    @Test
    fun `extractAll pulls every member out and returns the database`() {
        val dir = temporaryFolder.newFolder("stage")
        val dbBytes = sqliteBytes("db-payload")
        val image = byteArrayOf(1, 2, 3)

        val restored = zipWith(
            "note-list.db" to dbBytes,
            "attachments/a.jpg" to image
        ).use { zip -> DatabaseRestore.extractAll(zip, dir, "note-list.db") }

        assertTrue("the database must be returned", restored.name == "note-list.db")
        assertArrayEquals("database bytes must survive extraction", dbBytes, restored.readBytes())
        val extractedImage = File(dir, "attachments/a.jpg")
        assertTrue("attachment members must be extracted too", extractedImage.isFile)
        assertArrayEquals(image, extractedImage.readBytes())
    }

    @Test
    fun `extractAll rejects an archive without the notes database`() {
        val dir = temporaryFolder.newFolder("stage")
        var rejected = false
        try {
            zipWith("attachments/a.jpg" to byteArrayOf(1)).use { zip ->
                DatabaseRestore.extractAll(zip, dir, "note-list.db")
            }
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue("an archive missing the database must be refused", rejected)
    }

    @Test
    fun `extractAll refuses a member that escapes the staging directory`() {
        val dir = temporaryFolder.newFolder("stage")
        var rejected = false
        try {
            zipWith("../escape.db" to sqliteBytes("x")).use { zip ->
                DatabaseRestore.extractAll(zip, dir, "note-list.db")
            }
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue("a zip-slip member must be refused", rejected)
        assertTrue("nothing may be written outside the staging directory",
            !File(dir.parentFile, "escape.db").exists())
    }

    @Test
    fun `extractAll rejects an oversized single member`() {
        val dir = temporaryFolder.newFolder("stage")
        // One byte past the per-entry cap. Writing it all would be 64 MiB + 1, so the budget
        // check must trip before the copy finishes rather than filling the disk.
        val oversized = ByteArray((DatabaseRestore.MAX_ENTRY_BYTES + 1).toInt())
        var rejected = false
        try {
            zipWith("note-list.db" to oversized).use { zip ->
                DatabaseRestore.extractAll(zip, dir, "note-list.db")
            }
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue("an oversized member must be refused", rejected)
    }
}

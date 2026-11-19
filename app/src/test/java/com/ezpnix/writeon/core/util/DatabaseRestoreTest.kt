package com.ezpnix.writeon.core.util

import com.ezpnix.writeon.core.constant.DatabaseConst
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        // Cover the two shapes an archive can actually carry: a host-absolute path and the
        // POSIX/UNC roots that are absolute even when the JVM runs on the other OS. The second
        // and third cases are what failed on the Linux CI runner before the explicit check.
        val names = listOf(
            File(temporaryFolder.root, "outside.db").absolutePath,
            "/etc/passwd",
            "\\\\server\\share\\outside.db",
        )
        names.forEach { name ->
            var rejected = false
            try {
                DatabaseRestore.resolveEntry(dir, name)
            } catch (_: IOException) {
                rejected = true
            }
            assertTrue("an absolute entry name must be refused: $name", rejected)
        }
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

    // -----------------------------------------------------------------------------------------
    // D8: an archive may only be installed if its schema is something this build understands
    // (user_version from the 100-byte header, identity_hash from room_master_table). The rule is
    // a pure decision, so every branch runs on the JVM; the end-to-end refusal through
    // replaceDatabaseAtomically needs only the header, which the crafted files below carry.
    // -----------------------------------------------------------------------------------------

    private fun sqliteBytesWithVersion(version: Int): ByteArray {
        val bytes = sqliteBytes("schema payload")
        // user_version lives at offset 60 of the SQLite header and is big-endian.
        bytes[60] = (version ushr 24).toByte()
        bytes[61] = (version ushr 16).toByte()
        bytes[62] = (version ushr 8).toByte()
        bytes[63] = version.toByte()
        return bytes
    }

    @Test
    fun `replaceDatabaseAtomically refuses a database from a newer app version`() {
        val target = temporaryFolder.newFile("note-list.db").apply {
            writeBytes(sqliteBytes("live database"))
        }
        val oldBytes = target.readBytes()
        val replacement = temporaryFolder.newFile("future.db").apply {
            writeBytes(sqliteBytesWithVersion(DatabaseConst.NOTES_DATABASE_VERSION + 1))
        }

        var rejected: Throwable? = null
        try {
            DatabaseRestore.replaceDatabaseAtomically(target, replacement)
        } catch (e: IOException) {
            rejected = e
        }

        val incompatible = rejected as? DatabaseRestore.IncompatibleArchiveException
        assertNotNull("a schema this build cannot migrate must be refused", incompatible)
        assertEquals(
            DatabaseRestore.Incompatibility.NEWER_VERSION,
            incompatible!!.incompatibility
        )
        assertArrayEquals("the live database must not change", oldBytes, target.readBytes())
        assertTrue("the staged file must be cleaned up", !File(target.absolutePath + ".new").exists())
    }

    @Test
    fun `readUserVersion reads the big-endian header field`() {
        val versioned = temporaryFolder.newFile("versioned.db").apply {
            writeBytes(sqliteBytesWithVersion(7))
        }
        assertEquals(7, DatabaseRestore.readUserVersion(versioned))

        val tooShort = temporaryFolder.newFile("short.db").apply {
            writeBytes(ByteArray(50))
        }
        assertNull("no header, no version", DatabaseRestore.readUserVersion(tooShort))
    }

    @Test
    fun `checkCompatibility accepts this build's own schema`() {
        DatabaseRestore.checkCompatibility(
            DatabaseConst.NOTES_DATABASE_VERSION,
            DatabaseRestore.IdentityHashRead.Found(DatabaseRestore.CURRENT_IDENTITY_HASH)
        )
    }

    @Test
    fun `checkCompatibility accepts an older schema so migrations can run`() {
        // A v9 backup legitimately carries v9's hash; the registered 1->10 chain upgrades it and
        // rewrites the hash afterwards, so an older identity must NOT be refused.
        DatabaseRestore.checkCompatibility(
            9,
            DatabaseRestore.IdentityHashRead.Found("an older schema's hash")
        )
    }

    @Test
    fun `checkCompatibility rejects a schema newer than this build`() {
        var rejected: Throwable? = null
        try {
            DatabaseRestore.checkCompatibility(
                DatabaseConst.NOTES_DATABASE_VERSION + 1,
                DatabaseRestore.IdentityHashRead.Found(DatabaseRestore.CURRENT_IDENTITY_HASH)
            )
        } catch (e: IOException) {
            rejected = e
        }
        val incompatible = rejected as? DatabaseRestore.IncompatibleArchiveException
        assertNotNull("a future user_version must be refused", incompatible)
        assertEquals(
            DatabaseRestore.Incompatibility.NEWER_VERSION,
            incompatible!!.incompatibility
        )
    }

    @Test
    fun `checkCompatibility rejects a same-version database with a foreign identity`() {
        var rejected: Throwable? = null
        try {
            DatabaseRestore.checkCompatibility(
                DatabaseConst.NOTES_DATABASE_VERSION,
                DatabaseRestore.IdentityHashRead.Found("some other app's room hash")
            )
        } catch (e: IOException) {
            rejected = e
        }
        val incompatible = rejected as? DatabaseRestore.IncompatibleArchiveException
        assertNotNull("an identity mismatch at the current version must be refused", incompatible)
        assertEquals(
            DatabaseRestore.Incompatibility.SCHEMA_MISMATCH,
            incompatible!!.incompatibility
        )
    }

    @Test
    fun `checkCompatibility rejects a database without room_master_table`() {
        var rejected: Throwable? = null
        try {
            DatabaseRestore.checkCompatibility(
                DatabaseConst.NOTES_DATABASE_VERSION,
                DatabaseRestore.IdentityHashRead.TableMissing
            )
        } catch (e: IOException) {
            rejected = e
        }
        val incompatible = rejected as? DatabaseRestore.IncompatibleArchiveException
        assertNotNull("a database without Room metadata must be refused", incompatible)
        assertEquals(
            DatabaseRestore.Incompatibility.NOT_WRITEON_DATABASE,
            incompatible!!.incompatibility
        )
    }

    @Test
    fun `checkCompatibility leaves unreadable files to quick_check`() {
        // The file cannot be opened at all - exactly what a plain JVM test of
        // replaceDatabaseAtomically sees. Readability belongs to quick_check, so this state must
        // neither throw nor block the swap.
        DatabaseRestore.checkCompatibility(
            DatabaseConst.NOTES_DATABASE_VERSION,
            DatabaseRestore.IdentityHashRead.Unreadable
        )
    }

    @Test
    fun `the frozen identity hash matches the exported Room schema`() {
        // CURRENT_IDENTITY_HASH is copied out of the schema JSON because the APK cannot read that
        // file at runtime. Reading the JSON back from the working tree means bumping the schema
        // version without updating the constant fails here instead of refusing every backup.
        val version = DatabaseConst.NOTES_DATABASE_VERSION
        val schema = findSchemaFile(version)
            ?: error("schema JSON for version $version not found from ${System.getProperty("user.dir")}")
        val json = schema.readText()
        val jsonVersion = Regex("\"version\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt()
        val jsonHash = Regex("\"identityHash\"\\s*:\\s*\"([0-9a-f]+)\"").find(json)?.groupValues?.get(1)
        assertEquals("schema JSON version must match DatabaseConst", version, jsonVersion)
        assertEquals(
            "DatabaseRestore.CURRENT_IDENTITY_HASH must match the exported schema",
            jsonHash,
            DatabaseRestore.CURRENT_IDENTITY_HASH
        )
    }

    /** Walks up from the test working directory to find `app/schemas` (or `schemas`). */
    private fun findSchemaFile(version: Int): File? {
        val relative = "com.ezpnix.writeon.data.local.database.NoteDatabase/$version.json"
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (prefix in listOf("app/schemas", "schemas")) {
                val candidate = File(File(dir, prefix), relative)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        return null
    }
}

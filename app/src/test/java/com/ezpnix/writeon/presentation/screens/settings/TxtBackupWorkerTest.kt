package com.ezpnix.writeon.presentation.screens.settings

import com.ezpnix.writeon.data.backup.TxtBackupHelper
import com.ezpnix.writeon.domain.model.Note
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            val first = TxtBackupHelper.readCurrentZipEntry(zip)
            assertEquals("first\ncontent", first.text)
            assertFalse(first.truncated)
            zip.closeEntry()
            assertEquals("2_Second.txt", zip.nextEntry.name)
            val second = TxtBackupHelper.readCurrentZipEntry(zip)
            assertEquals("second\ncontent", second.text)
            assertFalse(second.truncated)
        }
    }

    /** D12: an entry past the byte cap is cut, flagged, and still leaves the rest readable. */
    @Test
    fun `entries larger than the cap are truncated and flagged`() {
        val big = "a".repeat(TxtBackupHelper.MAX_ENTRY_BYTES + 4096)
        val bytes = entryBytes("1_Big.txt", big, tail = "small tail")

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            zip.nextEntry
            val read = TxtBackupHelper.readCurrentZipEntry(zip)
            assertTrue(read.truncated)
            assertEquals(TxtBackupHelper.MAX_ENTRY_BYTES, read.text.length)
            assertEquals('a', read.text.last())
            zip.closeEntry()

            // The following entry must still parse: the cap must not desynchronise the stream.
            assertEquals("2_Tail.txt", zip.nextEntry.name)
            val tail = TxtBackupHelper.readCurrentZipEntry(zip)
            assertEquals("small tail", tail.text)
            assertFalse(tail.truncated)
        }
    }

    /** D12: an entry that ends exactly at the cap is complete, not truncated. */
    @Test
    fun `entry ending exactly at the cap is not flagged truncated`() {
        val exact = "b".repeat(64)
        val bytes = entryBytes("1_Exact.txt", exact)

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            zip.nextEntry
            val read = TxtBackupHelper.readCurrentZipEntry(zip, maxBytes = 64)
            assertEquals(exact, read.text)
            assertFalse(read.truncated)
        }
    }

    /** D12: a cut through a multi-byte character drops the partial sequence, never a U+FFFD. */
    @Test
    fun `truncation never splits a multi-byte character into a replacement char`() {
        // 3 bytes per char: cutting at 4 bytes lands in the middle of the second character.
        val bytes = entryBytes("1_Cjk.txt", "中中中")

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            zip.nextEntry
            val read = TxtBackupHelper.readCurrentZipEntry(zip, maxBytes = 4)
            assertTrue(read.truncated)
            assertEquals("中", read.text)
            assertFalse(read.text.contains('\uFFFD'))
        }
    }

    @Test
    fun `decodeEntry drops an incomplete tail but keeps complete sequences`() {
        val complete = "中中".toByteArray(StandardCharsets.UTF_8)
        assertEquals("中中", TxtBackupHelper.decodeEntry(complete))

        val cutMidChar = complete.copyOf(complete.size - 1) // drops one continuation byte
        assertEquals("中", TxtBackupHelper.decodeEntry(cutMidChar))

        val loneLead = complete.copyOf(1) // just0xE4
        assertEquals("", TxtBackupHelper.decodeEntry(loneLead))

        // ASCII is never touched, and pre-existing invalid bytes pass through as-is.
        assertEquals("ab", TxtBackupHelper.decodeEntry("ab".toByteArray()))
        val invalid = byteArrayOf(0xFF.toByte(), 0x41)
        assertEquals(
            String(invalid, StandardCharsets.UTF_8),
            TxtBackupHelper.decodeEntry(invalid),
        )
    }

    @Test
    fun `clipToLimit cuts on a code point boundary and is idempotent`() {
        val text = "日本語テキスト"
        val bytes = text.toByteArray(StandardCharsets.UTF_8)

        val clipped = TxtBackupHelper.clipToLimit(text, 4) // 3-byte chars: lands mid-second char
        assertEquals("日", clipped)
        assertFalse(clipped.contains('\uFFFD'))
        // Clipping the clipped text again changes nothing (the key must be stable).
        assertEquals(clipped, TxtBackupHelper.clipToLimit(clipped, 4))
        // Under the limit the text is returned untouched.
        assertEquals(text, TxtBackupHelper.clipToLimit(text, bytes.size))
    }

    /** D12: re-importing a package against the library it was exported from adds nothing. */
    @Test
    fun `importing the same package twice adds no duplicates`() {
        val untitled = "Untitled"
        val library = mutableSetOf<Pair<String, String>>()
        val packageEntries = listOf(
            "1_Meeting notes" to "agenda\nactions",
            "2_Grocery" to "milk\nbread",
            "3_untitled-ish" to "", // blank body is a legitimate note, not a duplicate of anything
        )

        // First import: every entry lands.
        packageEntries.forEach { (file, body) ->
            val title = file.substringAfter("_").ifBlank { untitled }
            if (!TxtBackupHelper.isDuplicate(library, title, body, untitled)) {
                library.add(TxtBackupHelper.noteKey(title, body, untitled))
            }
        }
        assertEquals(3, library.size)

        // Second import of the exact same package: nothing lands.
        var added = 0
        packageEntries.forEach { (file, body) ->
            val title = file.substringAfter("_").ifBlank { untitled }
            if (!TxtBackupHelper.isDuplicate(library, title, body, untitled)) {
                library.add(TxtBackupHelper.noteKey(title, body, untitled))
                added++
            }
        }
        assertEquals("re-import must be a no-op", 0, added)
        assertEquals(3, library.size)
    }

    /** D12: the key meets in the middle - sanitized export name vs. raw library name. */
    @Test
    fun `noteKey matches a library note against its sanitized export file name`() {
        val untitled = "Untitled"
        // Export sanitizes the title into the file name; the library keeps the raw name.
        val exportedTitle = TxtBackupHelper.sanitizeFileName("Q3: plan <draft>")
        assertEquals(
            TxtBackupHelper.noteKey("Q3: plan <draft>", "body", untitled),
            TxtBackupHelper.noteKey(exportedTitle, "body", untitled),
        )
        // Blank titles were exported under the localised placeholder.
        assertEquals(
            TxtBackupHelper.noteKey("", "body", untitled),
            TxtBackupHelper.noteKey(untitled, "body", untitled),
        )
        // Different bodies with the same title are different notes.
        assertTrue(
            TxtBackupHelper.noteKey("Same", "one", untitled) !=
                TxtBackupHelper.noteKey("Same", "two", untitled),
        )
    }

    /** DAT-3/DAT-13: a TXT package must never contain a placeholder, a vault body, or ciphertext. */
    @Test
    fun `txt export drops vault undecryptable and still-sealed rows`() {
        val placeholder = "Cannot decrypt this note"
        val notes = listOf(
            Note(id = 1, name = "Plain", description = "body"),
            Note(id = 2, name = "v2:salt:iv", description = "v2:salt:iv:payload", encrypted = true),
            Note(id = 3, name = placeholder, description = placeholder),
            // Structurally valid `w1:` payload: base64 of 32 bytes, longer than the 12-byte IV.
            Note(
                id = 4,
                name = "w1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                description = "w1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            ),
            Note(id = 5, name = "Title", description = "v2:leaked-body"),
            // V-11: this is a plain note that merely starts with the marker and must be exported.
            Note(id = 6, name = "w1:hello", description = "plain body"),
        )

        val selection = TxtBackupHelper.selectExportableNotes(notes, failedIds = setOf(3))

        assertEquals(listOf("Plain", "w1:hello"), selection.notes.map { it.title })
        assertEquals(listOf("body", "plain body"), selection.notes.map { it.content })
        assertEquals(1, selection.skippedVault)
        assertEquals(1, selection.skippedUndecryptable)
        assertEquals(2, selection.skippedSealedText)
        assertEquals(4, selection.skippedTotal)
        assertTrue(
            "no marker or placeholder may reach the archive",
            selection.notes.none {
                it.content == placeholder ||
                    it.content.contains("v2:") ||
                    it.content.contains("w1:")
            },
        )
    }

    /** D12: an over-long library body compares equal to the truncated text an import stored. */
    @Test
    fun `noteKey treats an over-long body as equal to its truncated copy`() {
        val untitled = "Untitled"
        val overLong = "x".repeat(TxtBackupHelper.MAX_ENTRY_BYTES + 100)
        assertEquals(
            TxtBackupHelper.noteKey("Title", overLong, untitled),
            TxtBackupHelper.noteKey("Title", TxtBackupHelper.clipToLimit(overLong, TxtBackupHelper.MAX_ENTRY_BYTES), untitled),
        )
    }

    /** Builds a zip containing the given entry plus an optional trailing entry. */
    private fun entryBytes(name: String, content: String, tail: String? = null): ByteArray =
        java.io.ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                if (tail != null) {
                    zip.putNextEntry(ZipEntry("2_Tail.txt"))
                    zip.write(tail.toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }.toByteArray()
}

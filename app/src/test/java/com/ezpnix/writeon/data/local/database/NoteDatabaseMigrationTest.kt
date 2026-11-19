package com.ezpnix.writeon.data.local.database

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.ezpnix.writeon.core.attachment.AttachmentSyntax
import com.ezpnix.writeon.domain.model.Attachment
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The whole 6 -> 11 upgrade over a real SQLite file: [NoteMigrations.MIGRATION_6_7] through
 * [NoteMigrations.MIGRATION_10_11] plus the 7 -> 8 body rewrite, the one step that rewrites every
 * note in the library.
 *
 * In Room 2.6.1 [MigrationTestHelper.runMigrationsAndValidate] folds "walk the chain" and
 * "validate the result against the exported schema" into one call, so a passing call proves the
 * migrated schema equals 11.json (columns, defaults, indices, no dropped or stray tables).
 *
 * Robolectric has no Android Keystore, so 7 -> 8 runs with [FakeNoteCrypto] instead of
 * [NoteCrypto]. The stand-in keeps the two semantics the migration relies on: plain text passes
 * through `openOrNull` unchanged, while a `w1:` value cannot be opened there and returns null so
 * the row is skipped - exactly what the real cipher does when the Keystore is unavailable. `seal`
 * is the one difference: it must succeed for the rewrite to be observable at all, so it wraps the
 * plaintext in the same `w1:` shape and the test decodes it with [FakeNoteCrypto.open].
 */
@RunWith(RobolectricTestRunner::class)
class NoteDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NoteDatabase::class.java
    )

    @Test
    fun `the 6 to 11 chain produces the version 11 schema`() {
        seedVersionSixDatabase()

        helper.runMigrationsAndValidate(TEST_DB, 11, true, *migrationChain()).use { db ->
            // 6 -> 7 adds `updated_at NOT NULL DEFAULT 0`: every pre-existing row must come back
            // on the sentinel, not stamped with the moment its owner upgraded.
            db.query("SELECT `updated_at` FROM `notes-table`").use { cursor ->
                var rows = 0
                while (cursor.moveToNext()) {
                    rows++
                    assertEquals("pre-upgrade rows keep the 0 sentinel", 0, cursor.getInt(0))
                }
                assertEquals("all seeded rows survive the chain", 3, rows)
            }

            // 9 -> 10 indexes the three columns the list screens filter and sort on.
            val noteIndices = db.indexNames(NOTES_TABLE)
            assertTrue(
                "9 -> 10 must create the three notes-table indices, found $noteIndices",
                noteIndices.containsAll(NOTE_INDICES)
            )

            // 7 -> 8 creates attachments with its note-id index; 10 -> 11 adds the two hot
            // filter columns the library and recycle bin query.
            val attachmentIndices = db.indexNames(ATTACHMENTS_TABLE)
            assertTrue(
                "7 -> 8 must index attachments.note-id, found $attachmentIndices",
                attachmentIndices.contains("index_attachments_note-id")
            )
            assertTrue(
                "10 -> 11 must index attachments.trashed_at and .type, found $attachmentIndices",
                attachmentIndices.containsAll(ATTACHMENT_INDICES)
            )

            // 8 -> 9 adds attachments.trashed_at; migrated rows were alive, so it stays NULL.
            val attachmentColumns = db.columnNames(ATTACHMENTS_TABLE)
            assertTrue(
                "8 -> 9 must add attachments.trashed_at, found $attachmentColumns",
                attachmentColumns.contains("trashed_at")
            )
        }
    }

    @Test
    fun `legacy body syntax becomes attachment rows and markdown links`() {
        seedVersionSixDatabase()

        helper.runMigrationsAndValidate(TEST_DB, 11, true, *migrationChain()).use { db ->
            val (legacyId, stored) = db.query(
                "SELECT `id`, `note-description` FROM `notes-table` WHERE `note-name` = ?",
                arrayOf(LEGACY_NOTE_NAME)
            ).use { cursor ->
                assertTrue("the legacy row must exist", cursor.moveToFirst())
                cursor.getInt(0) to cursor.getString(1)
            }

            // The rewrite is written back through the cipher, never stored as raw text.
            assertTrue(
                "the rewritten body must be sealed again",
                stored.startsWith(FakeNoteCrypto.MARKER)
            )
            val rewritten = FakeNoteCrypto.open(stored)

            assertFalse("the legacy !() token must be gone", rewritten.contains("!("))
            assertFalse("the legacy ?audio() token must be gone", rewritten.contains("?audio("))
            assertTrue(rewritten.startsWith("intro line"))
            assertTrue(rewritten.endsWith("trailing line"))

            val rows = db.query(
                "SELECT `attachment-id`, `note-id`, `type`, `path`, `caption`, `trashed_at` " +
                    "FROM `attachments`"
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            AttachmentRow(
                                id = cursor.getString(0),
                                noteId = cursor.getInt(1),
                                type = cursor.getString(2),
                                path = cursor.getString(3),
                                caption = cursor.getString(4),
                                trashedAt = if (cursor.isNull(5)) null else cursor.getLong(5),
                            )
                        )
                    }
                }
            }

            assertEquals("one image token and one audio token", 2, rows.size)
            rows.forEach { assertEquals("rows belong to the rewritten note", legacyId, it.noteId) }
            rows.forEach { assertNull("migrated rows are alive, not trashed", it.trashedAt) }

            val image = rows.single { it.type == Attachment.TYPE_IMAGE }
            assertEquals(LEGACY_IMAGE_PATH, image.path)
            val audio = rows.single { it.type == Attachment.TYPE_AUDIO }
            assertEquals(LEGACY_AUDIO_PATH, audio.path)
            assertEquals("20261018-2245", audio.caption)

            assertEquals(
                "the body must reference exactly the rows that were created",
                rows.map { it.id }.toSet(),
                AttachmentSyntax.idsIn(rewritten)
            )
        }
    }

    @Test
    fun `vault and sealed rows are left byte for byte untouched`() {
        seedVersionSixDatabase()

        helper.runMigrationsAndValidate(TEST_DB, 11, true, *migrationChain()).use { db ->
            // Vault rows (encrypted = 1) are skipped before the cipher is ever asked to open them.
            assertEquals(VAULT_BODY, db.descriptionOf(VAULT_NOTE_NAME))
            assertEquals(
                1,
                db.encryptedFlagOf(VAULT_NOTE_NAME)
            )

            // A w1: value cannot be opened without the Keystore, so openOrNull returns null and
            // the migration must leave the ciphertext exactly as it found it.
            assertEquals(SEALED_BODY, db.descriptionOf(SEALED_NOTE_NAME))
            assertEquals(
                0,
                db.encryptedFlagOf(SEALED_NOTE_NAME)
            )
        }
    }

    /** Seeds the three shapes the 7 -> 8 rewrite has to tell apart, at schema version 6. */
    private fun seedVersionSixDatabase() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(INSERT_NOTE, arrayOf(LEGACY_NOTE_NAME, LEGACY_BODY, 0, 0, SEEDED_AT, 0, "[]"))
            db.execSQL(INSERT_NOTE, arrayOf(VAULT_NOTE_NAME, VAULT_BODY, 0, 1, SEEDED_AT, 0, "[]"))
            db.execSQL(INSERT_NOTE, arrayOf(SEALED_NOTE_NAME, SEALED_BODY, 0, 0, SEEDED_AT, 0, "[]"))
        }
    }

    /**
     * The production chain, with only the Keystore-dependent 7 -> 8 cipher swapped for the
     * stand-in; everything else is the exact object [NoteDatabaseProvider] registers.
     */
    private fun migrationChain(): Array<Migration> = arrayOf(
        NoteMigrations.MIGRATION_6_7,
        LegacyBodyRewriteMigration { FakeNoteCrypto() },
        NoteMigrations.MIGRATION_8_9,
        NoteMigrations.MIGRATION_9_10,
        NoteMigrations.MIGRATION_10_11,
    )

    private fun SupportSQLiteDatabase.indexNames(table: String): List<String> =
        query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?",
            arrayOf(table)
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun SupportSQLiteDatabase.columnNames(table: String): List<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            // PRAGMA table_info columns: cid, name, type, notnull, dflt_value, pk.
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }

    private fun SupportSQLiteDatabase.descriptionOf(noteName: String): String =
        query(
            "SELECT `note-description` FROM `notes-table` WHERE `note-name` = ?",
            arrayOf(noteName)
        ).use { cursor ->
            assertTrue("no row for $noteName", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.encryptedFlagOf(noteName: String): Int =
        query(
            "SELECT `encrypted` FROM `notes-table` WHERE `note-name` = ?",
            arrayOf(noteName)
        ).use { cursor ->
            assertTrue("no row for $noteName", cursor.moveToFirst())
            cursor.getInt(0)
        }
}

private const val TEST_DB = "migration-test.db"

private const val NOTES_TABLE = "notes-table"
private const val ATTACHMENTS_TABLE = "attachments"

private const val INSERT_NOTE =
    "INSERT INTO `notes-table` (`note-name`, `note-description`, `pinned`, `encrypted`, " +
        "`created_at`, `trashed`, `tags`) VALUES (?, ?, ?, ?, ?, ?, ?)"

private const val LEGACY_NOTE_NAME = "legacy note"
private const val VAULT_NOTE_NAME = "vault note"
private const val SEALED_NOTE_NAME = "sealed note"

private const val LEGACY_IMAGE_PATH = "/storage/emulated/0/Pictures/legacy.jpg"
private const val LEGACY_AUDIO_PATH =
    "/data/user/0/com.ezpnix.writeon/files/audios/20261018-2245.m4a"

/** One image token, one audio token: both must become `attachment://` links plus table rows. */
private const val LEGACY_BODY =
    "intro line\n!($LEGACY_IMAGE_PATH)\nrecording ?audio($LEGACY_AUDIO_PATH)\ntrailing line"

/** Vault payload (encrypted = 1): skipped before the cipher is ever asked to open it. */
private const val VAULT_BODY = "vault-body-7c1f9e"

/** A `w1:` value the Keystore cannot open: openOrNull returns null, the row must not move. */
private const val SEALED_BODY = "w1:bm90LWEtcmVhbC1jaXBoZXJ0ZXh0"

private const val SEEDED_AT = 1_700_000_000_000L

private val NOTE_INDICES = listOf(
    "index_notes-table_trashed",
    "index_notes-table_pinned",
    "index_notes-table_updated_at",
)

private val ATTACHMENT_INDICES = listOf(
    "index_attachments_trashed_at",
    "index_attachments_type",
)

private data class AttachmentRow(
    val id: String,
    val noteId: Int,
    val type: String,
    val path: String,
    val caption: String,
    val trashedAt: Long?,
)

/**
 * [MigrationNoteCrypto] where there is no Android Keystore (Robolectric).
 *
 * [openOrNull] mirrors the real cipher's Keystore-less behaviour: plain text passes through,
 * anything marked [MARKER] cannot be opened and returns null so the migration leaves the row
 * alone. [seal] cannot throw the way `NoteCrypto.seal` would, so it wraps the plaintext in the
 * same `w1:` shape instead; [open] reads it back so the test can verify what was stored.
 */
private class FakeNoteCrypto : MigrationNoteCrypto {

    override fun seal(plaintext: String): String =
        MARKER + Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))

    override fun openOrNull(stored: String): String? =
        if (stored.startsWith(MARKER)) null else stored

    companion object {
        const val MARKER = "w1:"

        fun open(stored: String): String {
            require(stored.startsWith(MARKER)) { "value is not sealed" }
            return String(Base64.getDecoder().decode(stored.removePrefix(MARKER)), Charsets.UTF_8)
        }
    }
}

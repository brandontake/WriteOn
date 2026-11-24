package com.ezpnix.writeon.data.local.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TST-02: the 1 -> 11 and 2 -> 11 upgrade chains over a database that really is in the v1/v2 shape.
 *
 * [NoteDatabaseMigrationTest] starts at 6 because that is the oldest exported schema. `app/schemas`
 * deliberately has no 1.json-5.json: those files were never committed, and their identity hashes -
 * the one part of a Room schema JSON that cannot be derived from the current source - are unknown.
 * Fabricating them would let `MigrationTestHelper.createDatabase(name, 1)` build a fixture that
 * looks authoritative while describing a database no shipped build ever created. The raw DDL below
 * therefore reproduces the v1/v2 `notes-table` exactly as the v1.0 entity created it, sets
 * `PRAGMA user_version`, seeds rows, and lets Room run the *production* migration objects
 * ([NoteMigrations]) over that file.
 *
 * Room validates the result against `NoteDatabase_Impl`'s generated schema - the same columns,
 * affinity, NOT NULL flags, defaults, primary keys and indices recorded in 11.json - and throws
 * `IllegalStateException("Migration didn't properly handle ...")` when a step diverges, so a green
 * run here is the 1/2 -> 11 half of the schema contract that `MigrationTestHelper` covers for
 * 6 -> 11.
 *
 * 7 -> 8 is the one Keystore-dependent step; Robolectric has no AndroidKeyStore, so it runs with
 * the same fake cipher seam the existing test uses. Everything else is the exact object
 * [NoteDatabaseProvider] registers, including the 2 -> 3 / 2 -> 4 pair.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyRawSchemaMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = mutableListOf<String>()

    @After
    fun tearDown() {
        databases.forEach { context.deleteDatabase(it) }
    }

    @Test
    fun `a version 1 database upgrades to 11 and keeps its rows`() {
        val name = newDatabaseName("legacy-v1")
        createRawDatabase(name, version = 1, ddl = V1_NOTES_TABLE) { db ->
            db.execSQL(INSERT_V1_NOTE, arrayOf("legacy note", LEGACY_V1_BODY))
            db.execSQL(INSERT_V1_NOTE, arrayOf("", ""))
        }

        migrateToEleven(name) { db ->
            assertEquals("the file must now be at the current schema version", 11, db.version)

            val notes = readNotes(db)
            assertEquals("both pre-upgrade rows must survive", 2, notes.size)

            // 1 -> 2 adds created_at with the fixed 0 sentinel; pre-v2 rows have no real timestamp.
            notes.forEach {
                assertEquals("created_at must use the 0 sentinel", 0L, it.createdAt)
                assertEquals("updated_at must use the 0 sentinel", 0L, it.updatedAt)
                assertEquals("migrated rows are not pinned", 0, it.pinned)
                assertEquals("migrated rows are not vault rows", 0, it.encrypted)
                assertEquals("migrated rows are not trashed", 0, it.trashed)
                assertEquals("tags default to an empty JSON array", "[]", it.tags)
            }

            val legacy = notes.single { it.name == "legacy note" }
            assertTrue("the 7 -> 8 rewrite must re-seal the body", legacy.body.startsWith(FakeLegacyCrypto.MARKER))
            val rewritten = FakeLegacyCrypto.open(legacy.body)
            assertFalse("the legacy !() token must be gone", rewritten.contains("!("))
            assertTrue("the body must point at the new attachment row", rewritten.contains("attachment://"))
            assertTrue(rewritten.startsWith("intro line"))
            assertTrue(rewritten.endsWith("trailing line"))

            // The empty note is the "nothing to rewrite" case: name and body stay empty, not sealed.
            val empty = notes.single { it.name == "" }
            assertEquals("", empty.body)

            // 7 -> 8 created the attachments table and recorded the legacy image there.
            val attachments = readAttachments(db)
            assertEquals("the one legacy token must become one row", 1, attachments.size)
            val attachment = attachments.single()
            assertEquals(legacy.id, attachment.noteId)
            assertEquals("image", attachment.type)
            assertEquals(LEGACY_IMAGE_PATH, attachment.path)
            assertNull("a row created by the rewrite is alive", attachment.trashedAt)
            assertTrue(
                "the body must reference the row that was created",
                rewritten.contains(attachment.id)
            )

            assertSchemaObjects(db)
        }
    }

    @Test
    fun `a version 2 database upgrades to 11 and preserves created_at`() {
        val name = newDatabaseName("legacy-v2")
        createRawDatabase(name, version = 2, ddl = V2_NOTES_TABLE) { db ->
            db.execSQL(INSERT_V2_NOTE, arrayOf<Any?>("kept timestamp", LEGACY_V2_BODY, 111L))
            db.execSQL(INSERT_V2_NOTE, arrayOf<Any?>("", "", 222L))
            db.execSQL(INSERT_V2_NOTE, arrayOf<Any?>("plain body", "no legacy tokens here", 333L))
        }

        migrateToEleven(name) { db ->
            assertEquals(11, db.version)

            val notes = readNotes(db)
            assertEquals("all seeded rows must survive", 3, notes.size)
            assertEquals(
                "2 -> 11 must not rewrite the timestamps v2 already stored",
                mapOf("kept timestamp" to 111L, "" to 222L, "plain body" to 333L),
                notes.associate { it.name to it.createdAt }
            )

            val legacy = notes.single { it.name == "kept timestamp" }
            assertTrue(legacy.body.startsWith(FakeLegacyCrypto.MARKER))
            assertTrue(FakeLegacyCrypto.open(legacy.body).contains("attachment://"))
            assertEquals(1, readAttachments(db).size)

            val plain = notes.single { it.name == "plain body" }
            assertEquals(
                "a body without legacy tokens must be left byte for byte untouched",
                "no legacy tokens here",
                plain.body
            )
            assertEquals("", notes.single { it.name == "" }.body)

            assertSchemaObjects(db)
        }
    }

    /**
     * Creates the file at [version] with exactly the columns the old entity had, seeds it through
     * [seed], then hands it to Room with the production migration chain.
     */
    private fun createRawDatabase(
        name: String,
        version: Int,
        ddl: String,
        seed: (android.database.sqlite.SQLiteDatabase) -> Unit,
    ) {
        context.deleteDatabase(name)
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            db.execSQL(ddl)
            seed(db)
            // This is the value Room reads on open to decide which migrations to run. Using the
            // SQLiteDatabase property keeps the PRAGMA and the handle's own state in sync.
            db.version = version
        } finally {
            db.close()
        }
    }

    /**
     * Opens the raw file with Room and the production migrations. Opening the helper is what runs
     * `onUpgrade`; Room then validates the resulting schema against the generated expected schema
     * (the contents of 11.json) and throws when a migration left a column or index behind.
     */
    private fun migrateToEleven(name: String, assertions: (SupportSQLiteDatabase) -> Unit) {
        val database = Room.databaseBuilder(context, NoteDatabase::class.java, name)
            .addMigrations(*productionMigrationChain())
            .allowMainThreadQueries()
            .build()
        try {
            val sqlite = database.openHelper.writableDatabase
            assertions(sqlite)
        } finally {
            database.close()
        }
    }

    /**
     * The production chain with only the Keystore-dependent 7 -> 8 cipher replaced by the same
     * stand-in [NoteDatabaseMigrationTest] uses. [MIGRATION_2_4] is included because production
     * registers it alongside 2 -> 3; whichever branch Room's path finder takes, the resulting
     * schema is 11.
     */
    private fun productionMigrationChain() = arrayOf(
        NoteMigrations.MIGRATION_1_2,
        NoteMigrations.MIGRATION_2_3,
        NoteMigrations.MIGRATION_3_4,
        NoteMigrations.MIGRATION_2_4,
        NoteMigrations.MIGRATION_4_5,
        NoteMigrations.MIGRATION_5_6,
        NoteMigrations.MIGRATION_6_7,
        LegacyBodyRewriteMigration { FakeLegacyCrypto() },
        NoteMigrations.MIGRATION_8_9,
        NoteMigrations.MIGRATION_9_10,
        NoteMigrations.MIGRATION_10_11,
    )

    private fun readNotes(db: SupportSQLiteDatabase): List<RawNote> =
        db.query(
            "SELECT `id`, `note-name`, `note-description`, `pinned`, `encrypted`, `created_at`, " +
                "`updated_at`, `trashed`, `tags` FROM `notes-table` ORDER BY `id`"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        RawNote(
                            id = cursor.getInt(0),
                            name = cursor.getString(1),
                            body = cursor.getString(2),
                            pinned = cursor.getInt(3),
                            encrypted = cursor.getInt(4),
                            createdAt = cursor.getLong(5),
                            updatedAt = cursor.getLong(6),
                            trashed = cursor.getInt(7),
                            tags = cursor.getString(8),
                        )
                    )
                }
            }
        }

    private fun readAttachments(db: SupportSQLiteDatabase): List<RawAttachment> =
        db.query(
            "SELECT `attachment-id`, `note-id`, `type`, `path`, `trashed_at` FROM `attachments`"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        RawAttachment(
                            id = cursor.getString(0),
                            noteId = cursor.getInt(1),
                            type = cursor.getString(2),
                            path = cursor.getString(3),
                            trashedAt = if (cursor.isNull(4)) null else cursor.getLong(4),
                        )
                    )
                }
            }
        }

    private fun assertSchemaObjects(db: SupportSQLiteDatabase) {
        val noteIndices = indexNames(db, NOTES_TABLE)
        assertTrue(
            "9 -> 10 must create the three notes-table indices, found $noteIndices",
            noteIndices.containsAll(NOTE_INDICES)
        )
        val attachmentIndices = indexNames(db, ATTACHMENTS_TABLE)
        assertTrue(
            "7 -> 8/10 -> 11 must create all attachment indices, found $attachmentIndices",
            attachmentIndices.containsAll(ATTACHMENT_INDICES)
        )
        assertNotNull(
            "the attachments table must exist after 7 -> 8",
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(ATTACHMENTS_TABLE))
                .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        )
    }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?",
            arrayOf(table)
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun newDatabaseName(prefix: String): String =
        "$prefix-${System.nanoTime()}.db".also { databases += it }

    private data class RawNote(
        val id: Int,
        val name: String,
        val body: String,
        val pinned: Int,
        val encrypted: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val trashed: Int,
        val tags: String,
    )

    private data class RawAttachment(
        val id: String,
        val noteId: Int,
        val type: String,
        val path: String,
        val trashedAt: Long?,
    )
}

private const val NOTES_TABLE = "notes-table"
private const val ATTACHMENTS_TABLE = "attachments"

/**
 * v1.0 `Note` had only these three columns; the table name and column names are exactly what the
 * entity mapped to before created_at/pinned/encrypted/trashed/tags/updated_at existed.
 */
private const val V1_NOTES_TABLE =
    "CREATE TABLE IF NOT EXISTS `notes-table` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`note-name` TEXT NOT NULL, " +
        "`note-description` TEXT NOT NULL)"

/** v2 added created_at; its migration (`ALTER TABLE ... DEFAULT 0`) is what gives it the default. */
private const val V2_NOTES_TABLE =
    "CREATE TABLE IF NOT EXISTS `notes-table` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`note-name` TEXT NOT NULL, " +
        "`note-description` TEXT NOT NULL, " +
        "`created_at` INTEGER NOT NULL DEFAULT 0)"

private const val INSERT_V1_NOTE =
    "INSERT INTO `notes-table` (`note-name`, `note-description`) VALUES (?, ?)"

private const val INSERT_V2_NOTE =
    "INSERT INTO `notes-table` (`note-name`, `note-description`, `created_at`) VALUES (?, ?, ?)"

private const val LEGACY_IMAGE_PATH = "/storage/emulated/0/Pictures/legacy.jpg"

private const val LEGACY_V1_BODY =
    "intro line\n!($LEGACY_IMAGE_PATH)\ntrailing line"

private const val LEGACY_V2_BODY = "before\n!($LEGACY_IMAGE_PATH)\nafter"

private val NOTE_INDICES = listOf(
    "index_notes-table_trashed",
    "index_notes-table_pinned",
    "index_notes-table_updated_at",
)

private val ATTACHMENT_INDICES = listOf(
    "index_attachments_note-id",
    "index_attachments_trashed_at",
    "index_attachments_type",
)

/**
 * The Keystore-free [MigrationNoteCrypto] for the 7 -> 8 rewrite, identical in contract to the one
 * in [NoteDatabaseMigrationTest]: plain text passes through, a `w1:` value cannot be opened (so the
 * row is skipped), and [seal] wraps the rewritten body so this test can read it back.
 */
private class FakeLegacyCrypto : MigrationNoteCrypto {

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

package com.ezpnix.writeon.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.TypeConverters
import androidx.room.Update
import com.ezpnix.writeon.core.database.Converters
import com.ezpnix.writeon.data.local.database.NoteDatabase
import com.ezpnix.writeon.domain.model.Note
import kotlinx.coroutines.flow.Flow

/**
 * SEC-5: one note row exactly as the database stores it, with no decryption on the way out.
 *
 * The sweep that seals rows written before `NoteCrypto` existed has to look at the *stored* bytes:
 * going through `NoteDao`'s entity mapping would hand it plain text for every legacy row (that is
 * what the read path does) and it could never tell a plain row from a sealed one. Only the three
 * columns the sweep can rewrite are projected, so the query stays a narrow read.
 */
data class LegacyNoteRow(
    val id: Int,
    val name: String,
    val description: String,
)

@Dao
@TypeConverters(Converters::class)
interface NoteDao {
    @Query("SELECT * FROM `notes-table` WHERE trashed = 0")
    fun getAllNotes(): Flow<List<Note>>

    @Query("SELECT * FROM `notes-table` WHERE trashed = 0")
    suspend fun getAllNotesSync(): List<Note>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addNote(note: Note)

    /** Like [addNote], but reports the id Room assigned; needed by the one-transaction save. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addNoteReturningId(note: Note): Long

    /**
     * CQ-08: reports the affected row count.
     *
     * Room supports an `Int` return for `@Update`, and the one-transaction save needs it: a note
     * removed between the caller's read and the transaction matches zero rows, and the attachment
     * upserts that follow must not run for a note that no longer exists.
     */
    @Update
    suspend fun updateNote(note: Note): Int

    /**
     * Applies a batch of updates in one transaction.
     *
     * Without `@Transaction`, Room runs one statement per note, so a tag rename touching thirty
     * notes meant thirty separate writes: observers of the notes table could see a half-applied
     * state in between, and an interruption left the tag list inconsistent across notes.
     */
    @Transaction
    @Update
    suspend fun batchUpdateNotes(notes: List<Note>)

    @Delete
    suspend fun deleteNote(note: Note)

    /**
     * Partial updates for fields that are edited outside the note editor.
     *
     * Pinning, tagging and trashing used to read a row, copy it and write the whole entity back.
     * A stale copy from a list screen could therefore replace a body the editor had just saved, or
     * a body the editor was still typing into. These statements touch one column and leave every
     * other column - including the sealed body the caller never saw - exactly as it was.
     */
    @Query("UPDATE `notes-table` SET pinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: Int, pinned: Boolean)

    @Query("UPDATE `notes-table` SET tags = :tags WHERE id = :id")
    suspend fun updateTags(id: Int, tags: List<String>)

    @Query("UPDATE `notes-table` SET trashed = :trashed WHERE id = :id")
    suspend fun updateTrashed(id: Int, trashed: Boolean)

    /**
     * Rewrites the body of a non-vault note only. The `encrypted = 0` guard keeps a partial update
     * from ever replacing a password-sealed body with Keystore ciphertext (or plaintext).
     */
    @Query("UPDATE `notes-table` SET `note-description` = :description WHERE id = :id AND encrypted = 0")
    suspend fun updatePlainDescription(id: Int, description: String)

    /**
     * A4: rewrites both sealed columns of a vault row, and only of a vault row.
     *
     * Used by the one-time upgrade of notes still written in the pre-PBKDF2 vault layout. The
     * `encrypted = 1` guard matters in both directions: a plain note must never receive vault
     * ciphertext (nothing would ever decrypt it), and this write bypasses [EncryptedNoteDao]'s
     * Keystore sealing on purpose - the values passed in are already sealed with the user's
     * password.
     *
     * @return the number of rows changed (0 when the row is gone or is not a vault row).
     */
    @Query(
        "UPDATE `notes-table` SET `note-name` = :name, `note-description` = :description " +
            "WHERE id = :id AND encrypted = 1"
    )
    suspend fun updateVaultFields(id: Int, name: String, description: String): Int

    /**
     * SEC-5: every non-vault row with its text still in the form it was written in.
     *
     * Vault rows are excluded by the query, not filtered afterwards: their columns are ciphertext
     * derived from the user's password, and no Keystore sweep may ever reinterpret them. This is a
     * single pass - the sweep that consumes it runs at most once per process - and it projects only
     * the columns it can rewrite, so it never pulls `tags` or the attachment metadata.
     */
    @Query(
        "SELECT id, `note-name` AS name, `note-description` AS description " +
            "FROM `notes-table` WHERE encrypted = 0"
    )
    suspend fun getLegacyPlaintextRows(): List<LegacyNoteRow>

    /**
     * SEC-5: the raw write-back for [getLegacyPlaintextRows], bypassing `EncryptedNoteDao`.
     *
     * The caller has already sealed whatever needed sealing and passes the other column back
     * untouched, so running the values through the encrypting wrapper again would double-seal the
     * fresh ciphertext. The `encrypted = 0` guard mirrors [updatePlainDescription]: a row that
     * turned into a vault row between the read and this write must not receive Keystore ciphertext.
     *
     * The two `previous*` predicates make this a compare-and-swap against the exact bytes the sweep
     * read. The sweep visits every row of the library, so a user can open a note and save it while
     * the sweep is still working; without this, the sweep's older snapshot would be written back
     * over text that was just typed. A row that changed since the read is simply left alone (the
     * save already sealed it), which the caller counts as "nothing to do".
     *
     * @return the number of rows changed (0 when the row is gone, is now a vault row, or no longer
     *   matches the bytes that were read).
     */
    @Query(
        "UPDATE `notes-table` SET `note-name` = :name, `note-description` = :description " +
            "WHERE id = :id AND encrypted = 0 " +
            "AND `note-name` = :previousName AND `note-description` = :previousDescription"
    )
    suspend fun updateLegacyPlaintextFields(
        id: Int,
        previousName: String,
        previousDescription: String,
        name: String,
        description: String,
    ): Int

    @Query("SELECT * FROM `notes-table` WHERE id = :id")
    fun getNoteById(id: Int): Flow<Note>

    /**
     * W12: one keyset page of the whole library, trashed notes included, ordered by id.
     *
     * The export used to read every row - and therefore every body - into memory before writing a
     * single one to the portable copy, so a library full of long notes needed as much heap as the
     * library is big. Paging by primary key keeps exactly one page alive and is a straight index
     * range scan. Callers start with [Int.MIN_VALUE] as `afterId` (ids are positive) and stop at
     * the first empty page.
     */
    @Query("SELECT * FROM `notes-table` WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getNotesPage(afterId: Int, limit: Int): List<Note>

    @Query("SELECT * FROM `notes-table` WHERE trashed = 1")
    fun getTrashedNotes(): Flow<List<Note>>

    /**
     * PERF-07: the recycle-bin badge count without decrypting a single body.
     *
     * The drawer only needs "how many", while [getTrashedNotes] maps every row through the
     * Keystore and hands the full list to a screen the user may never open. This query is answered
     * by SQLite and emits the same invalidation stream, so it tracks the bin exactly.
     */
    @Query("SELECT COUNT(*) FROM `notes-table` WHERE trashed = 1")
    fun getTrashedNoteCount(): Flow<Int>
}

/**
 * DAT-01: compare-and-swap write for the vault re-seal sweep, on the live connection.
 *
 * [NoteDao.updateVaultFields] only checks `id` and `encrypted = 1`, so a sweep that was still
 * working through an old snapshot could write its pre-save ciphertext back over a row the user had
 * just saved from the editor - and because the editor's `lastSavedContent` then matched the screen,
 * the edit looked saved while the database held the previous version. This statement carries the
 * same `previous*` predicates [NoteDao.updateLegacyPlaintextFields] uses: a row that changed since
 * the sweep read it matches zero rows and is left alone.
 *
 * It is a plain statement rather than another `NoteDao` method because `EncryptedNoteDao` wraps the
 * generated DAO and would have to grow a pass-through override. The repository issues it inside a
 * Room transaction, so Room's invalidation notification still runs when the transaction ends and
 * the open note list sees the resealed row instead of waiting for an unrelated write.
 *
 * @return the number of rows changed (0 when the row is gone, is no longer a vault row, or no
 *   longer holds the bytes the sweep read).
 */
internal fun NoteDatabase.compareAndSwapVaultFields(
    id: Int,
    previousName: String,
    previousDescription: String,
    name: String,
    description: String,
): Int {
    val statement = openHelper.writableDatabase.compileStatement(
        "UPDATE `notes-table` SET `note-name` = ?, `note-description` = ? " +
            "WHERE id = ? AND encrypted = 1 " +
            "AND `note-name` = ? AND `note-description` = ?"
    )
    return try {
        statement.bindString(1, name)
        statement.bindString(2, description)
        statement.bindLong(3, id.toLong())
        statement.bindString(4, previousName)
        statement.bindString(5, previousDescription)
        statement.executeUpdateDelete()
    } finally {
        statement.close()
    }
}
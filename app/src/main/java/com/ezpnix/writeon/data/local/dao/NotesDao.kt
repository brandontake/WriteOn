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
import com.ezpnix.writeon.domain.model.Note
import kotlinx.coroutines.flow.Flow

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

    @Update
    suspend fun updateNote(note: Note)

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

    @Query("SELECT * FROM `notes-table` WHERE id = :id")
    fun getNoteById(id: Int): Flow<Note>

    @Query("SELECT id FROM `notes-table` ORDER BY id DESC LIMIT 1")
    fun getLastNoteId(): Long?

    @Query("SELECT * FROM `notes-table` WHERE trashed = 1")
    fun getTrashedNotes(): Flow<List<Note>>
}
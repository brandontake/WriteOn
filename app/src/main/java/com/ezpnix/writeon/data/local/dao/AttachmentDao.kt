package com.ezpnix.writeon.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ezpnix.writeon.domain.model.Attachment
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments")
    suspend fun getAll(): List<Attachment>

    @Query("SELECT * FROM attachments WHERE `attachment-id` = :id LIMIT 1")
    suspend fun getById(id: String): Attachment?

    @Query("SELECT * FROM attachments WHERE `note-id` = :noteId")
    suspend fun getByNoteId(noteId: Int): List<Attachment>

    @Query("SELECT * FROM attachments WHERE `attachment-id` IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Attachment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<Attachment>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Attachment)

    /**
     * DAT-07: rewrites one caption only while the row still holds the bytes that were read.
     *
     * The caption sweep used to read every row and write each one back as a full-row `REPLACE`. A
     * user can trash or delete an attachment while that sweep runs: the stale entity then brought
     * the row back (or re-inserted a deleted one) with its old `trashed_at`. This statement touches
     * the caption alone and only when `caption` still equals [previousCaption], so a concurrent
     * trash/delete simply makes it match zero rows.
     *
     * @return the number of rows changed (0 means the row was deleted or changed - skip).
     */
    @Query(
        "UPDATE attachments SET caption = :newCaption " +
            "WHERE `attachment-id` = :id AND caption = :previousCaption"
    )
    suspend fun updateCaptionIfMatches(id: String, previousCaption: String, newCaption: String): Int

    /**
     * Gives unclaimed rows (note-id 0) to the note that just got its id.
     *
     * The `note-id = 0` guard matters: this runs again whenever the editor re-subscribes to a note,
     * and without it an id left in an older body would pull an attachment away from the note that
     * currently owns it.
     */
    @Query("UPDATE attachments SET `note-id` = :noteId WHERE `attachment-id` IN (:ids) AND `note-id` = 0")
    suspend fun assignNote(noteId: Int, ids: List<String>)

    /** Rows the library lists: everything not in the recycle bin, newest first. */
    @Query("SELECT * FROM attachments WHERE trashed_at IS NULL ORDER BY created_at DESC")
    fun observeActive(): Flow<List<Attachment>>

    @Query("SELECT * FROM attachments WHERE trashed_at IS NULL AND `type` = :type ORDER BY created_at DESC")
    fun observeActiveByType(type: String): Flow<List<Attachment>>

    /** Rows in the recycle bin, most recently deleted first. */
    @Query("SELECT * FROM attachments WHERE trashed_at IS NOT NULL ORDER BY trashed_at DESC")
    fun observeTrashed(): Flow<List<Attachment>>

    @Query("UPDATE attachments SET trashed_at = :trashedAt WHERE `attachment-id` IN (:ids)")
    suspend fun setTrashedAt(ids: List<String>, trashedAt: Long?)

    @Query("DELETE FROM attachments WHERE `note-id` = :noteId")
    suspend fun deleteByNoteId(noteId: Int)

    @Query("DELETE FROM attachments WHERE `attachment-id` IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

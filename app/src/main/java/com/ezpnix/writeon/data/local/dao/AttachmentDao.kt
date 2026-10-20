package com.ezpnix.writeon.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ezpnix.writeon.domain.model.Attachment

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments")
    suspend fun getAll(): List<Attachment>

    @Query("SELECT * FROM attachments WHERE `note-id` = :noteId")
    suspend fun getByNoteId(noteId: Int): List<Attachment>

    @Query("SELECT * FROM attachments WHERE `attachment-id` IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Attachment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<Attachment>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Attachment)

    /**
     * Gives unclaimed rows (note-id 0) to the note that just got its id.
     *
     * The `note-id = 0` guard matters: this runs again whenever the editor re-subscribes to a note,
     * and without it an id left in an older body would pull an attachment away from the note that
     * currently owns it.
     */
    @Query("UPDATE attachments SET `note-id` = :noteId WHERE `attachment-id` IN (:ids) AND `note-id` = 0")
    suspend fun assignNote(noteId: Int, ids: List<String>)

    @Query("DELETE FROM attachments WHERE `note-id` = :noteId")
    suspend fun deleteByNoteId(noteId: Int)

    @Query("DELETE FROM attachments WHERE `attachment-id` IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

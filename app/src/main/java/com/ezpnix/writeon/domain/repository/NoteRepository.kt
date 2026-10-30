package com.ezpnix.writeon.domain.repository
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note
import kotlinx.coroutines.flow.Flow
interface NoteRepository {
    fun getAllNotes(): Flow<List<Note>>
    suspend fun getAllNotesSync(): List<Note>
    suspend fun addNote(note: Note)
    suspend fun updateNote(note: Note)
    suspend fun deleteNote(note: Note)

    /**
     * Writes the note row and its attachment-row changes in one Room transaction.
     *
     * Saving used to be three independent writes (note row, attachment rows upsert, attachment
     * rows delete), so a failure in the middle left "new body, old attachment rows" or the reverse.
     * The transaction covers the database only - file deletion happens after it commits, because
     * an orphan file is recoverable while a row pointing at a deleted file is not.
     */
    suspend fun saveNoteWithAttachments(
        note: Note,
        upsertAttachments: List<Attachment>,
        reviveAttachmentIds: List<String>,
        deleteAttachmentIds: List<String>,
    ): Int

    /**
     * Deletes a note and its attachment rows in one transaction, returning the rows' file paths.
     *
     * Deleting the note row and its attachment rows together means an interruption can never leave
     * an attachment row pointing at a note that no longer exists. The files are *not* deleted here:
     * the caller removes them after this returns, because a leftover file is recoverable while a row
     * whose file was already gone is not.
     *
     * @return the paths (and types) of the files the caller should now delete from disk.
     */
    suspend fun deleteNoteWithAttachments(note: Note): List<Attachment>

    // Single-column updates for actions that must not rewrite a body they did not edit.
    suspend fun updatePinned(id: Int, pinned: Boolean)
    suspend fun updateTags(id: Int, tags: List<String>)
    suspend fun updateTrashed(id: Int, trashed: Boolean)
    suspend fun updatePlainDescription(id: Int, description: String)
    fun getNoteById(id: Int): Flow<Note>
    fun getLastNoteId(): Long?
    fun getTrashedNotes(): Flow<List<Note>>
    fun getNotesByTag(tag: String): Flow<List<Note>>
    suspend fun getAllTags(): List<String>
    suspend fun batchUpdateNotes(notes: List<Note>)

    // 2.0 attachments: note bodies only hold `attachment://<id>`, the path lives here.
    suspend fun getAllAttachments(): List<Attachment>
    suspend fun getAttachmentsForNote(noteId: Int): List<Attachment>
    suspend fun upsertAttachments(attachments: List<Attachment>)
    suspend fun assignAttachmentsToNote(noteId: Int, ids: List<String>)
    suspend fun deleteAttachments(ids: List<String>)
    suspend fun getAttachmentById(id: String): Attachment?

    // 2.0.2 recycle bin: a trashed attachment keeps its row and file until it is restored or
    // permanently deleted.
    fun observeAttachmentsByType(type: String): Flow<List<Attachment>>
    fun observeTrashedAttachments(): Flow<List<Attachment>>
    suspend fun setAttachmentTrashed(ids: List<String>, trashedAt: Long?)
}

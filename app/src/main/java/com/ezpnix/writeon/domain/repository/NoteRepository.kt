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

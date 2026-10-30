package com.ezpnix.writeon.data.repository
import androidx.room.withTransaction
import com.ezpnix.writeon.data.local.database.NoteDatabaseProvider
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
class NoteRepositoryImpl @Inject constructor(
    private val provider: NoteDatabaseProvider
) : NoteRepository {
    override fun getAllNotes(): Flow<List<Note>> {
        return provider.noteDao().getAllNotes()
    }
    override suspend fun getAllNotesSync(): List<Note> {
        return provider.noteDao().getAllNotesSync()
    }
    override suspend fun addNote(note: Note) {
        provider.noteDao().addNote(note)
    }
    override suspend fun updateNote(note: Note) {
        provider.noteDao().updateNote(note)
    }
    override suspend fun deleteNote(note: Note) {
        provider.noteDao().deleteNote(note)
    }
    override suspend fun updatePinned(id: Int, pinned: Boolean) {
        provider.noteDao().updatePinned(id, pinned)
    }
    override suspend fun updateTags(id: Int, tags: List<String>) {
        provider.noteDao().updateTags(id, tags)
    }
    override suspend fun updateTrashed(id: Int, trashed: Boolean) {
        provider.noteDao().updateTrashed(id, trashed)
    }
    override suspend fun updatePlainDescription(id: Int, description: String) {
        provider.noteDao().updatePlainDescription(id, description)
    }

    override suspend fun saveNoteWithAttachments(
        note: Note,
        upsertAttachments: List<Attachment>,
        reviveAttachmentIds: List<String>,
        deleteAttachmentIds: List<String>,
    ): Int {
        // One transaction over both tables: the note body and the rows it references either both
        // land or neither does. Room's withTransaction needs the database handle, which the
        // provider owns. `addNote` cannot report the generated id, so the insert goes through the
        // Room-generated DAO directly, whose @Insert returns it.
        return provider.instance().withTransaction {
            val noteDao = provider.noteDao()
            val insertedId = if (note.id == 0) {
                noteDao.addNoteReturningId(note).toInt()
            } else {
                noteDao.updateNote(note)
                note.id
            }
            if (upsertAttachments.isNotEmpty()) {
                provider.attachmentDao().upsert(upsertAttachments.map { it.copy(noteId = insertedId) })
            }
            if (reviveAttachmentIds.isNotEmpty()) {
                provider.attachmentDao().setTrashedAt(reviveAttachmentIds, null)
            }
            if (deleteAttachmentIds.isNotEmpty()) {
                provider.attachmentDao().deleteByIds(deleteAttachmentIds)
            }
            insertedId
        }
    }

    override suspend fun deleteNoteWithAttachments(note: Note): List<Attachment> {
        // Read the rows first (outside the transaction), then delete note + rows together so an
        // interruption cannot leave an orphan attachment row pointing at a deleted note. The files
        // are removed by the caller after this returns: a leftover file is recoverable, a row whose
        // file is already gone is not.
        val attachments = provider.attachmentDao().getByNoteId(note.id)
        provider.instance().withTransaction {
            if (attachments.isNotEmpty()) {
                provider.attachmentDao().deleteByIds(attachments.map { it.id })
            }
            provider.noteDao().deleteNote(note)
        }
        return attachments
    }

    override fun getNoteById(id: Int): Flow<Note> {
        return provider.noteDao().getNoteById(id)
    }
    override fun getLastNoteId(): Long? {
        return provider.noteDao().getLastNoteId()
    }
    override fun getTrashedNotes(): Flow<List<Note>> {
        return provider.noteDao().getTrashedNotes()
    }
    override fun getNotesByTag(tag: String): Flow<List<Note>> {
        // The old `WHERE :tag IN (tags)` SQL never matched (tags is one JSON text column), so the
        // tag screen always came up empty. Tags are decrypted here anyway, so filtering in memory
        // is both correct and no more expensive than the query it replaces.
        val wanted = tag.trim().lowercase()
        return provider.noteDao().getAllNotes()
            .map { notes -> notes.filter { note -> note.tags.any { it.lowercase() == wanted } } }
    }
    override suspend fun getAllTags(): List<String> {
        return provider.noteDao().getAllNotesSync().flatMap { it.tags }.distinct()
    }

    override suspend fun batchUpdateNotes(notes: List<Note>) {
        provider.noteDao().batchUpdateNotes(notes)
    }

    override suspend fun getAllAttachments() = provider.attachmentDao().getAll()

    override suspend fun getAttachmentsForNote(noteId: Int) =
        provider.attachmentDao().getByNoteId(noteId)

    override suspend fun upsertAttachments(attachments: List<Attachment>) {
        if (attachments.isNotEmpty()) provider.attachmentDao().upsert(attachments)
    }

    override suspend fun assignAttachmentsToNote(noteId: Int, ids: List<String>) {
        if (ids.isNotEmpty()) provider.attachmentDao().assignNote(noteId, ids)
    }

    override suspend fun deleteAttachments(ids: List<String>) {
        if (ids.isNotEmpty()) provider.attachmentDao().deleteByIds(ids)
    }

    override suspend fun getAttachmentById(id: String): Attachment? =
        provider.attachmentDao().getById(id)

    override fun observeAttachmentsByType(type: String): Flow<List<Attachment>> =
        provider.attachmentDao().observeActiveByType(type)

    override fun observeTrashedAttachments(): Flow<List<Attachment>> =
        provider.attachmentDao().observeTrashed()

    override suspend fun setAttachmentTrashed(ids: List<String>, trashedAt: Long?) {
        if (ids.isNotEmpty()) provider.attachmentDao().setTrashedAt(ids, trashedAt)
    }
}

package com.ezpnix.writeon.data.repository
import androidx.room.withTransaction
import com.ezpnix.writeon.data.local.dao.compareAndSwapVaultFields
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

    override suspend fun updateVaultFields(
        id: Int,
        previousName: String,
        previousDescription: String,
        name: String,
        description: String,
    ): Int {
        // DAT-01: the CAS lives on the live database rather than on the wrapped DAO so the
        // `previous*` predicates are part of the same statement as the write. The row is left
        // untouched when an editor save replaced it after the sweep read it.
        //
        // The statement is already atomic; the transaction wrapper is what tells Room's
        // invalidation tracker that the table changed. A raw write does not pass through the
        // generated DAO's transaction hooks, and without this the open list would keep showing
        // its pre-sweep emission until some other write happened to wake the tracker.
        val database = provider.instance()
        return database.withTransaction {
            database.compareAndSwapVaultFields(
                id = id,
                previousName = previousName,
                previousDescription = previousDescription,
                name = name,
                description = description,
            )
        }
    }

    override suspend fun saveNoteWithAttachments(
        note: Note,
        upsertAttachments: List<Attachment>,
        reviveAttachmentIds: List<String>,
        trashAttachmentIds: List<String>,
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
                // CQ-08: a note deleted between the editor's read and this transaction matches zero
                // rows. Continuing would insert its attachment rows for a note that no longer
                // exists - rows no screen can show and no delete path owns. Throwing rolls the
                // whole transaction (including those upserts) back; the editor reports the save as
                // failed, which is the truth.
                val changed = noteDao.updateNote(note)
                check(changed > 0) {
                    "Note ${note.id} no longer exists; refusing to save its attachments"
                }
                note.id
            }
            if (upsertAttachments.isNotEmpty()) {
                provider.attachmentDao().upsert(upsertAttachments.map { it.copy(noteId = insertedId) })
            }
            if (reviveAttachmentIds.isNotEmpty()) {
                provider.attachmentDao().setTrashedAt(reviveAttachmentIds, null)
            }
            if (trashAttachmentIds.isNotEmpty()) {
                // DAT-02: the save's pruning is recycle-bin semantics, not deletion. The row and
                // its file survive so the save-undo (which restores only the note row) can put the
                // body back without leaving dead attachment links or destroying a recording.
                provider.attachmentDao().setTrashedAt(trashAttachmentIds, System.currentTimeMillis())
            }
            insertedId
        }
    }

    override suspend fun deleteNoteWithAttachments(note: Note): List<Attachment> {
        // Read the rows first (outside the transaction) so the caller still receives the files it
        // has to remove; then delete note + rows together so an interruption cannot leave an orphan
        // attachment row pointing at a deleted note. CQ-08: the delete is by note id inside the
        // transaction, not by the ids read a moment earlier, so a row inserted between the read and
        // the transaction is deleted with its note instead of surviving it. The files are removed
        // by the caller after this returns: a leftover file is recoverable, a row whose file is
        // already gone is not.
        val attachments = provider.attachmentDao().getByNoteId(note.id)
        provider.instance().withTransaction {
            provider.attachmentDao().deleteByNoteId(note.id)
            provider.noteDao().deleteNote(note)
        }
        return attachments
    }

    override fun getNoteById(id: Int): Flow<Note> {
        return provider.noteDao().getNoteById(id)
    }
    override fun getTrashedNotes(): Flow<List<Note>> {
        return provider.noteDao().getTrashedNotes()
    }
    override fun getTrashedNoteCount(): Flow<Int> {
        return provider.noteDao().getTrashedNoteCount()
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

    /**
     * SEC-5: only the failure count crosses into the domain layer.
     *
     * The sealed/already-sealed split is diagnostic and stays in the data layer's log; what the
     * startup job needs to decide "mark done or retry next launch" is whether anything was left
     * unsealed.
     */
    override suspend fun sealLegacyPlaintextRows(): Int =
        provider.sealLegacyPlaintextRows().failedRows

    /** SEC-4: the repository only forwards the failure count; success is "nothing left plain". */
    override suspend fun sealLegacyCaptions(): Int = provider.sealLegacyCaptions()

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

    override suspend fun setAttachmentTrashedAndUpdateNote(
        attachmentId: String,
        trashedAt: Long?,
        note: Note,
    ) {
        provider.instance().withTransaction {
            provider.attachmentDao().setTrashedAt(listOf(attachmentId), trashedAt)
            val changed = provider.noteDao().updateNote(note)
            check(changed > 0) {
                "Note ${note.id} no longer exists; refusing to move its attachment"
            }
        }
    }

    override suspend fun setAttachmentTrashedAndUpdatePlainDescription(
        attachmentId: String,
        trashedAt: Long?,
        noteId: Int,
        description: String,
    ) {
        provider.instance().withTransaction {
            provider.attachmentDao().setTrashedAt(listOf(attachmentId), trashedAt)
            provider.noteDao().updatePlainDescription(noteId, description)
        }
    }
}

package com.ezpnix.writeon.domain.repository
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
     *
     * DAT-02: rows the new body stopped referencing are **trashed**, not deleted. A save can be
     * undone from its snackbar, and that undo only restores the note row; permanently deleting the
     * attachment row and file here left the restored body with dead links and destroyed the only
     * copy of a recording. The recycle-bin row and its file are what make that undo complete.
     */
    suspend fun saveNoteWithAttachments(
        note: Note,
        upsertAttachments: List<Attachment>,
        reviveAttachmentIds: List<String>,
        trashAttachmentIds: List<String>,
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

    /**
     * A4/DAT-01: replaces both already-sealed columns of a vault row, but only while it still
     * holds the bytes the sweep read.
     *
     * Only the vault upgrade path uses this; the values are sealed with the user's password
     * before they arrive, so no Keystore wrapping may be applied on top. The `previous*` values
     * make the write a compare-and-swap: the sweep may run for tens of seconds on the first unlock
     * of an old library, and a note saved from the editor in that window must keep the ciphertext
     * that save wrote instead of receiving the sweep's older snapshot back.
     *
     * @return the number of rows changed (0 when the row is gone, is not a vault row, or no
     *   longer holds [previousName]/[previousDescription]).
     */
    suspend fun updateVaultFields(
        id: Int,
        previousName: String,
        previousDescription: String,
        name: String,
        description: String,
    ): Int

    fun getNoteById(id: Int): Flow<Note>
    fun getTrashedNotes(): Flow<List<Note>>

    /**
     * PERF-07: row count for the drawer badge.
     *
     * The default keeps every test fake and alternate implementation working; the production
     * repository overrides it with a `SELECT COUNT(*)` so the badge never decrypts the bin.
     */
    fun getTrashedNoteCount(): Flow<Int> = getTrashedNotes().map { it.size }

    fun getNotesByTag(tag: String): Flow<List<Note>>
    suspend fun getAllTags(): List<String>
    suspend fun batchUpdateNotes(notes: List<Note>)

    /**
     * SEC-5: seals every non-vault row still stored as plain text.
     *
     * A row written before note encryption existed (an upgraded install, an old restored database)
     * keeps its text in the clear until that note is next saved. This sweep is idempotent: already
     * sealed columns are left untouched, so it is cheap to call on startup.
     *
     * @return how many rows could not be sealed (a Keystore failure); 0 means the library is fully
     *   sealed and the caller may record the backfill as done.
     */
    suspend fun sealLegacyPlaintextRows(): Int

    /**
     * SEC-4: seals every attachment caption still stored as plain text.
     *
     * A portable archive carries plaintext captions by design (its own password protects the
     * archive; a local Keystore seal would not survive the trip), so restored rows stay readable
     * until each is next saved. This is the proactive sweep for that, and the lazy counterpart of
     * the caption sealing every ordinary write already performs.
     *
     * @return how many rows could not be sealed and remain plain; 0 means the library is fully
     *   sealed and the caller may record the backfill as done.
     */
    suspend fun sealLegacyCaptions(): Int

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

    /**
     * R6-04: moves the attachment row and writes the owning note in one transaction.
     *
     * The vault trash/restore paths rewrite the note body, so a failure between the row update and
     * the body write used to leave the recycle bin and the note disagreeing. The default keeps
     * test fakes working; production overrides it with a Room transaction.
     */
    suspend fun setAttachmentTrashedAndUpdateNote(
        attachmentId: String,
        trashedAt: Long?,
        note: Note,
    ) {
        setAttachmentTrashed(listOf(attachmentId), trashedAt)
        updateNote(note)
    }

    /** R6-04: plain-note variant of [setAttachmentTrashedAndUpdateNote]. */
    suspend fun setAttachmentTrashedAndUpdatePlainDescription(
        attachmentId: String,
        trashedAt: Long?,
        noteId: Int,
        description: String,
    ) {
        setAttachmentTrashed(listOf(attachmentId), trashedAt)
        updatePlainDescription(noteId, description)
    }
}

package com.ezpnix.writeon.domain.usecase

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ezpnix.writeon.core.attachment.AttachmentFiles
import com.ezpnix.writeon.core.attachment.AttachmentIndex
import com.ezpnix.writeon.core.attachment.AttachmentSyntax
import com.ezpnix.writeon.core.attachment.LegacyAudioSyntax
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.repository.NoteRepository
import com.ezpnix.writeon.core.security.DecryptFailureRegistry
import com.ezpnix.writeon.core.security.DecryptionResult
import com.ezpnix.writeon.core.security.EncryptionHelper
import com.ezpnix.writeon.core.widget.WidgetRefresher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class NoteUseCase @Inject constructor(
    // The domain layer talks to the repository *interface*: a fake implementation is what makes
    // "attachment reconciliation", "undo save" and the deletion order testable at all, and the
    // concrete class was the only thing standing in the way.
    private val noteRepository: NoteRepository,
    private val coroutineScope: CoroutineScope,
    private val encryptionHelper: EncryptionHelper,
    // The id -> row map and the widget redraw are interfaces for the same reason: the registry is
    // Compose state and the refresher is Glance, and neither belongs in a domain unit test.
    private val attachmentIndex: AttachmentIndex,
    private val widgetRefresher: WidgetRefresher,
    @ApplicationContext private val context: Context
) {
    var notes: List<Note> by mutableStateOf(emptyList())
        private set
    var tags: List<String> by mutableStateOf(emptyList())
        private set
    var decryptionResult: DecryptionResult by mutableStateOf(DecryptionResult.LOADING)
    /**
     * How many vault rows are present in the database but could not be decrypted into [notes].
     *
     * Without this number the home screen had no way to say "some notes are locked" - a vault row
     * that fails to decrypt was simply absent, which read as data loss rather than a locked vault.
     */
    var hiddenVaultCount: Int by mutableStateOf(0)

    /**
     * Ids of the notes whose stored text this process could not decrypt.
     *
     * Keystore failures are degraded per row by `EncryptedNoteDao` (the row survives with a
     * placeholder body), and this flow is how the home screen learns how many rows are affected
     * while the editor and every write path learn which rows must never be written back.
     */
    val decryptFailedIds: StateFlow<Set<Int>> get() = DecryptFailureRegistry.failedIds

    /** [decryptFailedIds] as a count, for the "N notes could not be decrypted" banner. */
    val decryptFailedCount: StateFlow<Int> get() = DecryptFailureRegistry.failedCount

    private var observeKeysJob: Job? = null
    private var observeTagsJob: Job? = null

    /**
     * P-5: one table stream shared by the note list and the tag index.
     *
     * Both observers used to call `getAllNotes()` independently, so every save made Room read the
     * whole notes table twice - and [EncryptedNoteDao] decrypted every row on each copy. `shareIn`
     * keeps one upstream query and one decryption pass; the tag observer only derives a small list
     * from the same emission. `WhileSubscribed` lets the query stop when no screen is listening.
     */
    private val notesSource: SharedFlow<List<Note>> by lazy {
        noteRepository.getAllNotes().shareIn(
            scope = coroutineScope,
            started = SharingStarted.WhileSubscribed(5_000),
            replay = 1,
        )
    }

    fun observe() {
        observeNotes()
        observeTags()
        coroutineScope.launch { refreshAttachments() }
    }

    /**
     * Fills [AttachmentRegistry] with every id -> path pair.
     *
     * Note bodies only store ids, so without this a freshly started process renders every image and
     * recording as "unavailable" until that note happens to load its own rows. The registry is a
     * Compose state map, so screens already on screen pick the values up on the next frame.
     */
    suspend fun refreshAttachments() {
        val attachments = withContext(Dispatchers.IO) { noteRepository.getAllAttachments() }
        attachmentIndex.put(attachments)
    }

    suspend fun saveAttachments(items: List<Attachment>) {
        if (items.isEmpty()) return
        withContext(Dispatchers.IO) { noteRepository.upsertAttachments(items) }
        attachmentIndex.put(items)
    }

    suspend fun attachmentsForNote(noteId: Int): List<Attachment> =
        withContext(Dispatchers.IO) { noteRepository.getAttachmentsForNote(noteId) }

    /**
     * Hands rows written under the placeholder note id 0 to the note Room has just created.
     *
     * The DAO only rewrites unclaimed rows, so replaying this for a note that already owns its
     * attachments cannot take them away from another note.
     */
    suspend fun assignAttachments(noteId: Int, ids: List<String>) {
        if (noteId == 0 || ids.isEmpty()) return
        withContext(Dispatchers.IO) { noteRepository.assignAttachmentsToNote(noteId, ids) }
    }

    suspend fun deleteAttachments(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) { noteRepository.deleteAttachments(ids) }
    }

    /**
     * Loads the notes once and stops.
     *
     * Widgets need this: [observe] starts collectors that refresh every widget on every emission,
     * so a collector started from inside a widget's own composition refreshed the widget, which
     * recomposed and started the collector again. Reading once outside the composition ends that
     * loop.
     */
    suspend fun loadNotesOnce() {
        val sourceNotes = withContext(Dispatchers.IO) { noteRepository.getAllNotes().first() }
        notes = withContext(Dispatchers.Default) { processNotes(sourceNotes) }
    }

    private fun processNotes(sourceNotes: List<Note>): List<Note> {
        // A genuinely empty library is loaded, not loading: the home screen uses LOADING below to
        // decide whether to show its first-frame spinner, and this state used to survive forever.
        if (sourceNotes.isEmpty()) {
            this.decryptionResult = DecryptionResult.EMPTY
            this.hiddenVaultCount = 0
            return emptyList()
        }
        val hasUnencryptedNotes = sourceNotes.any { !it.encrypted }
        if (!hasUnencryptedNotes) {
            this.decryptionResult = DecryptionResult.EMPTY
        }
        var hiddenVault = 0
        var sawPlainNote = false
        val processed = sourceNotes.mapNotNull { note ->
            if (note.encrypted) {
                val (decryptedNote, status) = decryptNote(note)
                this.decryptionResult = status
                if (status == DecryptionResult.SUCCESS) decryptedNote else {
                    hiddenVault++
                    null
                }
            } else {
                // Only the encrypted branch used to assign a result, so a library of plain notes
                // stayed on LOADING forever - which a first-frame progress indicator would turn
                // into a permanent spinner.
                sawPlainNote = true
                note
            }
        }
        // Keep a vault failure (BAD_PASSWORD / LOADING) visible even when plain notes also loaded;
        // only promote the state to SUCCESS when every row that had work to do succeeded.
        if (sawPlainNote &&
            this.decryptionResult == DecryptionResult.LOADING &&
            hiddenVault == 0
        ) {
            this.decryptionResult = DecryptionResult.SUCCESS
        }
        this.hiddenVaultCount = hiddenVault
        return processed
    }

    private fun observeNotes() {
        observeKeysJob?.cancel()
        observeKeysJob = coroutineScope.launch {
            notesSource.collectLatest { sourceNotes ->
                // Decrypting every note touches the Keystore, so it must stay off the main thread.
                val processedNotes = withContext(Dispatchers.Default) { processNotes(sourceNotes) }
                val previousIds = notes.map { it.id }
                this@NoteUseCase.notes = processedNotes
                // Refreshing on an unchanged list made the widget recompose for nothing.
                if (previousIds != processedNotes.map { it.id }) {
                    widgetRefresher.refreshAll()
                }
            }
        }
    }

    private fun observeTags() {
        observeTagsJob?.cancel()
        observeTagsJob = coroutineScope.launch {
            notesSource.collectLatest { notes ->
                // P-5: the injected scope runs on Dispatchers.Main, so this collectLatest body -
                // a flatten + trim + lowercase + distinct + sort over every tag in the library -
                // used to run on the UI thread on *every* database emission. `observeNotes` above
                // already hops to Default for the same reason; the assignment stays on Main so the
                // Compose state write is not observed from a background thread.
                val newTags = withContext(Dispatchers.Default) {
                    notes.flatMap { it.tags }
                        .map { it.trim().lowercase() }
                        .distinct()
                        .sorted()
                        .filter { it.isNotBlank() }
                }
                tags = newTags
            }
        }
    }

    private fun encryptNote(note: Note): Note {
        if (!note.encrypted) return note
        // Without a loaded vault passphrase, sealing the text would write ciphertext under an empty
        // key, and EncryptionHelper.decrypt short-circuits to LOADING while the vault is empty, so
        // that note could never be read again. Silently downgrading it to a plain note was worse:
        // a password-protected note would be written to disk in the clear without the user asking.
        // The editor refuses to save in this state (see EditViewModel.saveNote); reaching here means
        // some other caller tried to rewrite a vault note while it was locked, which must fail
        // loudly instead of destroying its confidentiality.
        check(!encryptionHelper.isPasswordEmpty()) {
            "Refusing to save a vault note while the vault is locked"
        }
        // This is also the vault's lazy upgrade point (A4): decrypt() reads the pre-PBKDF2 layout
        // without rewriting it, so a legacy note keeps the weak single-SHA-256 format until the
        // user saves it. encrypt() always seals with the current v2/PBKDF2 layout, which upgrades
        // the row here - on a real save, never on a list read.
        return note.copy(
            name = encryptionHelper.encrypt(note.name),
            description = encryptionHelper.encrypt(note.description),
            encrypted = true
        )
    }

    /**
     * A4: re-seals every vault row still written in the pre-PBKDF2 layout.
     *
     * The legacy layout derived its key with a single SHA-256 pass over the passphrase, which is
     * the one part of the vault a stolen database file could brute-force. It used to be upgraded
     * only when the user happened to open *and save* that exact note, so a library the user
     * restored or never edited again could keep the weak format indefinitely. This sweep runs once
     * per unlock - the only moment the passphrase is in memory - and rewrites the rows it can
     * positively authenticate.
     *
     * A row where a legacy field does not authenticate (wrong passphrase, or a row that was never
     * really vault-sealed) is left byte-for-byte untouched, and an unsealed field is never
     * rewritten either: the Keystore-sealed layout has nothing to do with the vault's.
     *
     * @return how many rows were rewritten; 0 when the vault is locked or everything is current.
     */
    suspend fun upgradeLegacyVaultRows(): Int = withContext(Dispatchers.IO) {
        if (encryptionHelper.isPasswordEmpty()) return@withContext 0
        // Raw rows: `EncryptedNoteDao` deliberately passes vault rows through verbatim, so these
        // are exactly the sealed columns this method has to inspect.
        val vaultRows = noteRepository.getAllNotes().first().filter { it.encrypted }
        var upgraded = 0
        vaultRows.forEach { row ->
            val nameIsLegacy = encryptionHelper.isLegacyFormat(row.name)
            val bodyIsLegacy = encryptionHelper.isLegacyFormat(row.description)
            if (!nameIsLegacy && !bodyIsLegacy) return@forEach

            val sealedName = if (nameIsLegacy) resealVaultValueOrNull(row.name, row.id) else row.name
            val sealedBody =
                if (bodyIsLegacy) resealVaultValueOrNull(row.description, row.id) else row.description
            // Nothing is written unless every legacy field authenticated.
            if (sealedName == null || sealedBody == null) return@forEach

            if (noteRepository.updateVaultFields(row.id, sealedName, sealedBody) > 0) upgraded++
        }
        upgraded
    }

    /**
     * Reads one legacy sealed value and re-seals it with the current layout.
     *
     * @return the new ciphertext, or null when the value does not authenticate - in which case the
     *   caller must leave the row exactly as it is rather than overwrite a readable note.
     */
    private fun resealVaultValueOrNull(stored: String, noteId: Int): String? {
        val (plain, result) = encryptionHelper.decrypt(stored)
        if (result != DecryptionResult.SUCCESS || plain == null) {
            Log.w("NoteUseCase", "Vault row $noteId did not authenticate; leaving it untouched")
            return null
        }
        return encryptionHelper.encrypt(plain)
    }

    private fun decryptNote(note: Note): Pair<Note, DecryptionResult> {
        val (decryptedName, nameResult) = encryptionHelper.decrypt(note.name)
        val (decryptedDescription, descriptionResult) = encryptionHelper.decrypt(note.description)
        return if (note.encrypted) {
            Pair(
                note.copy(
                    name = decryptedName ?: "",
                    description = decryptedDescription ?: "",
                ), descriptionResult
            )
        } else {
            Pair(note, DecryptionResult.SUCCESS)
        }
    }

    fun updateNotes(newNotes: List<Note>) {
        notes = processNotes(newNotes)
    }

    fun updateTags(newTags: List<String>) {
        tags = newTags
    }

    fun getAllNotesFlow(): Flow<List<Note>> = getAllNotes()

    private fun getAllNotes(): Flow<List<Note>> {
        return noteRepository.getAllNotes()
    }

    fun getNotesByTagFlow(tag: String): Flow<List<Note>> {
        return noteRepository.getNotesByTag(tag.trim().lowercase())
    }

    /**
     * True when [id] belongs to a row whose stored text could not be decrypted.
     *
     * The list shows such a row as a placeholder, so writing it back - a full row, a restored
     * undo snapshot, an appended attachment line - would replace the only ciphertext that exists
     * with display text: permanent, silent data loss. Every full-row write path checks this
     * first and skips the row with a log line instead; single-column updates (pin, trash, tags)
     * never touch the body and stay allowed.
     */
    private fun refusesUndecryptable(id: Int, action: String): Boolean {
        if (id == 0 || !DecryptFailureRegistry.isFailed(id)) return false
        Log.w("NoteUseCase", "Skipping $action for note $id: its stored text could not be decrypted")
        return true
    }

    /**
     * Writes [note] back exactly as it was stored.
     *
     * [addNote] is the ordinary write path: it seals a vault note again and stamps nothing, but it is
     * still the wrong tool for an undo, because the row being restored is already in the form the
     * database must keep. Going through the repository writes those bytes untouched, which is what
     * makes a vault note, a plain note with tags, and a trashed flag all come back unchanged.
     */
    suspend fun restoreNote(note: Note) {
        if (refusesUndecryptable(note.id, "restore")) return
        noteRepository.updateNote(note)
    }

    suspend fun addNote(note: Note) {
        if (refusesUndecryptable(note.id, "save")) return
        val noteToSave = if (note.encrypted) {
            encryptNote(note.copy(tags = note.tags.map { it.trim().lowercase() }.distinct()))
        } else {
            note.copy(tags = note.tags.map { it.trim().lowercase() }.distinct())
        }
        if (noteToSave.id == 0) {
            noteRepository.addNote(noteToSave)
        } else {
            noteRepository.updateNote(noteToSave)
        }
    }

    /**
     * The editor's save path: the note row and its attachment-row changes in one transaction.
     *
     * [addNote] plus three separate attachment writes could stop halfway (a cancelled coroutine, a
     * database error) and leave the body pointing at rows that were never written, or rows deleted
     * for a body that never landed. The transaction makes "saved" mean all of it or none of it.
     * File deletion stays outside, in the caller: a leftover file is recoverable, a row whose file
     * was already deleted is not.
     *
     * @return the id Room assigned when the note was new, or the note's own id.
     */
    suspend fun saveNoteWithAttachments(
        note: Note,
        upsertAttachments: List<Attachment>,
        reviveAttachmentIds: List<String> = emptyList(),
        deleteAttachmentIds: List<String> = emptyList(),
    ): Int {
        // No row write for an undecryptable note - the placeholder the editor is showing must
        // never become the stored body. The editor refuses even earlier (EditViewModel.saveNote)
        // so the user is told why nothing happened; this is the last line of defence.
        if (refusesUndecryptable(note.id, "save")) return note.id
        val noteToSave = if (note.encrypted) {
            encryptNote(note.copy(tags = note.tags.map { it.trim().lowercase() }.distinct()))
        } else {
            note.copy(tags = note.tags.map { it.trim().lowercase() }.distinct())
        }
        return noteRepository.saveNoteWithAttachments(
            note = noteToSave,
            upsertAttachments = upsertAttachments,
            reviveAttachmentIds = reviveAttachmentIds,
            deleteAttachmentIds = deleteAttachmentIds,
        )
    }

    suspend fun batchUpdateNotes(notes: List<Note>) {
        // Partition instead of a blanket skip: one unreadable row must not silently cancel the
        // tag/pin changes the caller asked for on every other note.
        val writable = notes.filterNot { refusesUndecryptable(it.id, "batch update") }
        if (writable.isEmpty()) return
        val notesToSave = writable.map { note ->
            if (note.encrypted) encryptNote(note.copy(tags = note.tags.map { it.trim().lowercase() }.distinct())) else note
        }
        noteRepository.batchUpdateNotes(notesToSave)
    }

    /** Replaces one note's tag column without rewriting its body. */
    suspend fun setTags(noteId: Int, tags: List<String>) {
        if (noteId == 0) return
        val normalized = tags.map { it.trim().lowercase() }.distinct().filter { it.isNotBlank() }
        withContext(Dispatchers.IO) { noteRepository.updateTags(noteId, normalized) }
    }

    /** Moves one note in or out of the recycle bin without rewriting its body. */
    suspend fun setTrashed(noteId: Int, trashed: Boolean) {
        if (noteId == 0) return
        withContext(Dispatchers.IO) { noteRepository.updateTrashed(noteId, trashed) }
    }

    suspend fun addTagToNote(noteId: Int, tag: String) {
        val trimmedTag = tag.trim().lowercase()
        if (trimmedTag.isNotBlank()) {
            val note = noteRepository.getNoteById(noteId).firstOrNull() ?: return
            if (trimmedTag !in note.tags.map { it.lowercase() }) {
                // Tags are a separate column, so this does not rewrite the body the editor owns.
                noteRepository.updateTags(noteId, note.tags + trimmedTag)
            }
        }
    }

    suspend fun deleteTagFromNotes(tag: String) {
        val trimmedTag = tag.trim().lowercase()
        val allNotes = noteRepository.getAllNotes().first()
        val updatedNotes = allNotes.mapNotNull { note ->
            if (trimmedTag in note.tags.map { it.lowercase() }) {
                note to note.tags.filterNot { it.lowercase() == trimmedTag }
            } else {
                null
            }
        }

        // One column per note: deleting a tag from the tag manager must not overwrite a note body
        // from the stale snapshot this function just read.
        updatedNotes.forEach { (note, newTags) ->
            noteRepository.updateTags(note.id, newTags)
        }

        // Recompute from what the database actually holds rather than from the pre-update list, so
        // the tag list is right whether or not there was anything to update.
        val freshNotes = noteRepository.getAllNotes().first()
        val computedTags = freshNotes.flatMap { it.tags }
            .map { it.trim().lowercase() }
            .distinct()
            .sorted()
            .filter { it.isNotBlank() }
        updateTags(computedTags)
    }

    fun pinNote(note: Note) {
        coroutineScope.launch(NonCancellable + Dispatchers.IO) {
            addNote(note)
        }
    }

    fun deleteNoteById(id: Int) {
        coroutineScope.launch(NonCancellable + Dispatchers.IO) {
            val noteToDelete = noteRepository.getNoteById(id).firstOrNull() ?: return@launch
            performDelete(noteToDelete)
        }
    }

    fun getNoteById(id: Int): Flow<Note> {
        return noteRepository.getNoteById(id)
    }

    suspend fun updatePinStatus(noteId: Int, pinned: Boolean) {
        // Only the flag is written. Reading the row and writing a full Note back would race the
        // editor: the row read here predates whatever the user has typed since, and the emission
        // that followed overwrote the text field in the middle of a sentence (P0-2). A missing id
        // (a brand-new note still at 0) simply updates nothing, which is also what the old
        // firstOrNull() early return did - without the NoSuchElementException risk.
        noteRepository.updatePinned(noteId, pinned)
    }

    fun moveToTrash(note: Note) {
        coroutineScope.launch(NonCancellable + Dispatchers.IO) {
            noteRepository.updateTrashed(note.id, true)
        }
    }

    fun restoreFromTrash(note: Note) {
        coroutineScope.launch(NonCancellable + Dispatchers.IO) {
            noteRepository.updateTrashed(note.id, false)
        }
    }

    fun permanentlyDeleteNoteById(id: Int) {
        coroutineScope.launch(NonCancellable + Dispatchers.IO) {
            val noteToDelete = noteRepository.getNoteById(id).firstOrNull() ?: return@launch
            performDelete(noteToDelete)
        }
    }

    /**
     * Deletes a note, its attachment rows and (after the commit) their files.
     *
     * Order matters. The note row and the attachment rows go first, inside one transaction, so an
     * interruption can never leave an attachment row pointing at a note that is already gone. Only
     * once that commits are the files removed - a leftover file is recoverable and a later sweep
     * can find it, while a row whose file was deleted out from under it is an orphan that no screen
     * can ever render or clean up. File deletion stays best-effort and logged.
     */
    private suspend fun performDelete(note: Note) {
        val attachments = noteRepository.deleteNoteWithAttachments(note)

        try {
            if (attachments.isNotEmpty()) {
                AttachmentFiles.deleteAll(context, attachments)
                attachmentIndex.remove(attachments.map { it.id })
            }
            // A vault note is only rewritten when it is opened, so a password-protected note
            // deleted from the trash can still hold `?audio(...)` paths in its decrypted body.
            val text = "${note.name} ${note.description}"
            val legacyPaths = LegacyAudioSyntax.extractAudioPaths(text)
            if (legacyPaths.isNotEmpty()) {
                LegacyAudioSyntax.deleteAudioFiles(legacyPaths, context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("NoteUseCase", "Could not clean up every file of note ${note.id}", e)
        }
    }

    fun getTrashedNotesFlow(): Flow<List<Note>> = noteRepository.getTrashedNotes()

    // ---------------------------------------------------------------------------------------------
    // 2.0.2 attachment management: the library lists every live attachment of a type across the
    // notes, and deleting one moves the row to the recycle bin instead of removing it on the spot.
    // ---------------------------------------------------------------------------------------------

    /** Live attachments of [type], newest first, minus the notes the manager must keep private. */
    fun getLibraryAttachmentsFlow(type: String): Flow<List<Attachment>> =
        combine(
            noteRepository.observeAttachmentsByType(type),
            libraryNoteIds(includeTrashedNotes = false)
        ) { attachments, noteIds -> attachments.filter { it.noteId in noteIds } }

    /** Attachments in the recycle bin, most recently deleted first. */
    fun getTrashedAttachmentsFlow(): Flow<List<Attachment>> =
        combine(
            noteRepository.observeTrashedAttachments(),
            libraryNoteIds(includeTrashedNotes = true)
        ) { attachments, noteIds -> attachments.filter { it.noteId in noteIds } }

    /**
     * Ids of the notes whose attachments a management screen may show.
     *
     * Vault rows are sealed with the user's password, so this process can neither rewrite their
     * bodies nor safely show their files; they stay out of the library and its trash. The library
     * additionally skips notes that already sit in the recycle bin - their attachments travel with
     * the note.
     */
    private fun libraryNoteIds(includeTrashedNotes: Boolean): Flow<Set<Int>> =
        noteRepository.getAllNotes().map { notes ->
            notes.filter { !it.encrypted && (includeTrashedNotes || !it.trashed) }
                .map { it.id }
                .toSet()
        }

    /**
     * Moves one attachment to the recycle bin.
     *
     * The row is stamped and, for a plain note, its line leaves the body in the same pass, so the
     * note keeps rendering cleanly even if it is never opened again. The editor passes
     * `rewriteBody = false` because it already edited its own copy of the text and saves that body
     * itself. A vault note is left untouched: only the editor's save path may rewrite its body.
     */
    suspend fun trashAttachment(id: String, rewriteBody: Boolean = true) {
        withContext(Dispatchers.IO) {
            val attachment = noteRepository.getAttachmentById(id) ?: return@withContext
            if (attachment.trashedAt != null) return@withContext
            val note = noteRepository.getNoteById(attachment.noteId).firstOrNull()
            if (note?.encrypted == true) return@withContext
            noteRepository.setAttachmentTrashed(listOf(id), System.currentTimeMillis())
            if (rewriteBody && note != null &&
                !refusesUndecryptable(note.id, "body rewrite for a trashed attachment")
            ) {
                val updated = AttachmentSyntax.removeId(note.description, id)
                if (updated != note.description) {
                    // Body-only update: the note may have gained new text since this row was read,
                    // and a full-row write would put the old text back.
                    noteRepository.updatePlainDescription(note.id, updated)
                }
            }
        }
    }

    /** Used by the editor's save path to revive a row whose line came back through undo. */
    suspend fun setAttachmentsTrashed(ids: List<String>, trashedAt: Long?) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) { noteRepository.setAttachmentTrashed(ids, trashedAt) }
    }

    /**
     * Puts a trashed attachment back into its note.
     *
     * The line is appended at the end of the body: the position it originally occupied was never
     * stored, and appending is the one placement that can never split an existing line.
     */
    suspend fun restoreAttachment(id: String) {
        withContext(Dispatchers.IO) {
            val attachment = noteRepository.getAttachmentById(id) ?: return@withContext
            if (attachment.trashedAt == null) return@withContext
            val note = noteRepository.getNoteById(attachment.noteId).firstOrNull()
            if (note?.encrypted == true) return@withContext
            noteRepository.setAttachmentTrashed(listOf(id), null)
            if (note == null) return@withContext
            if (id in AttachmentSyntax.idsIn(note.description)) return@withContext
            // The description read above is the placeholder for an undecryptable row: appending
            // the line would store that placeholder plus one attachment line as the new body.
            if (refusesUndecryptable(note.id, "body rewrite for a restored attachment")) return@withContext
            val line = AttachmentSyntax.lineFor(attachment.id, attachment.type, attachment.caption)
            val separator = if (note.description.isEmpty() || note.description.endsWith("\n")) "" else "\n"
            noteRepository.updatePlainDescription(note.id, note.description + separator + line)
        }
    }

    /**
     * Empties one attachment from the recycle bin: row, file and registry entry all go.
     *
     * The file's fate decides the row's: if the file is gone (or was never there) the row can go
     * too, but a file that still exists and refuses to be deleted - a path outside the guard
     * roots, a permission problem - keeps its row. Dropping the row anyway orphaned the file
     * forever: the registry entry went with it, so nothing in the app could ever find it again.
     */
    suspend fun permanentlyDeleteAttachment(id: String) {
        withContext(Dispatchers.IO) {
            val attachment = noteRepository.getAttachmentById(id) ?: return@withContext
            val file = java.io.File(attachment.path)
            val fileStillThere = file.exists() && !AttachmentFiles.delete(context, attachment)
            if (fileStillThere) {
                Log.w(
                    "NoteUseCase",
                    "Attachment file ${file.name} could not be deleted; keeping its row so it can be retried"
                )
                return@withContext
            }
            noteRepository.deleteAttachments(listOf(id))
            attachmentIndex.remove(listOf(id))
        }
    }
}
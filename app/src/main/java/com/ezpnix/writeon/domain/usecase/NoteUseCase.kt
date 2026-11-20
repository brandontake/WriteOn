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
import com.ezpnix.writeon.core.util.RestoreCoordinator
import com.ezpnix.writeon.core.widget.WidgetRefresher
import com.ezpnix.writeon.data.local.database.LegacyCaptionBackfillFlag
import com.ezpnix.writeon.data.local.database.LegacyPlaintextBackfillFlag
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The process-wide note list, shared by every screen and widget.
 *
 * PERF-3: this used to be unscoped, so Hilt built one instance per injection point (home, editor,
 * settings, the widget configuration screen, every widget edit). Each of those owned its own
 * [notes] state and its own collectors - and because [observe] launches into the application-wide
 * scope, those collectors were never cancelled. A singleton (with an idempotent [observe]) is what
 * makes "the note list" mean one list, one query and one decryption pass per emission.
 */
@Singleton
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
     * V-5: generation of the live list, advanced by [clearSensitiveState].
     *
     * Cancelling a collector is not enough on its own. The CPU-heavy part of an emission runs in a
     * `withContext(Dispatchers.Default)` block that has no suspension point, so a lock arriving
     * mid-decrypt cancels the coroutine only after it finishes - and the `notes = ...` that follows
     * would put the plaintext list straight back into memory behind the lock screen. Both
     * collectors capture this value before the hop and assign only if it is still current.
     */
    private val loadEpoch = NoteLoadEpoch()

    /**
     * SEC-5/SEC-4: claims the one legacy-backfill pass this process gets.
     *
     * [observe] is called on every unlock, so without this the sweeps would run again each time.
     * It is a process-lifetime latch, not the persisted flags: those say "the job is finished for
     * good", this says "this process already tried". One latch (and one coroutine) is deliberate -
     * the caption reset depends on reading the note marker before the note sweep re-marks it.
     */
    private val legacyBackfillAttempted = AtomicBoolean(false)

    /**
     * P-5: one table stream shared by the note list and the tag index.
     *
     * Both observers used to call `getAllNotes()` independently, so every save made Room read the
     * whole notes table twice - and [EncryptedNoteDao] decrypted every row on each copy. Sharing
     * keeps one upstream query and one decryption pass; the tag observer only derives a small list
     * from the same emission.
     *
     * PERF-3/SEC-6: the shared flow is nullable and dropped by [clearSensitiveState]. Its replay
     * slot holds the last *decrypted* list, so keeping the flow alive behind the lock screen would
     * leave exactly the plaintext the lock is supposed to evict. `WhileSubscribed(0)` (rather than a
     * grace timeout) means the only moment both collectors stop - a lock - also stops the upstream
     * query immediately instead of five seconds later.
     */
    private var notesSource: SharedFlow<List<Note>>? = null

    private fun sharedNotes(): SharedFlow<List<Note>> =
        notesSource ?: noteRepository.getAllNotes().shareIn(
            scope = coroutineScope,
            started = SharingStarted.WhileSubscribed(0),
            replay = 1,
        ).also { notesSource = it }

    /**
     * DAT-7: this singleton is the process-wide collector pause for a database restore.
     *
     * A restore closes the shared Room connection before swapping the file, and these collectors
     * are the process's largest Flow subscriber - left running they query a closed connection and
     * crash inside the restart window. Registering here (rather than making the restore paths know
     * about [NoteUseCase]) keeps the dependency one-way: the coordinator is a plain object and this
     * class only ever pushes listeners into it, so Hilt still constructs `NoteUseCase` exactly as
     * before.
     *
     * [clearSensitiveState] also drops the decrypted list and the shared flow's replay slot. That
     * is deliberate: while the database is being replaced there is no version of the list that is
     * not about to describe a file that no longer exists, and a failed restore reloads through
     * [observe] rather than publishing stale plaintext.
     */
    init {
        RestoreCoordinator.onBegin { clearSensitiveState() }
        // `observe()` reads and replaces the collector job references, and every other caller runs
        // it on the main dispatcher. A worker's failure path calls end() from IO, so hop to the
        // injected scope rather than racing those reads from a background thread.
        RestoreCoordinator.onEnd { coroutineScope.launch { observe() } }
    }

    /**
     * Starts the collectors once and leaves them running.
     *
     * PERF-3: this used to cancel and restart both collectors on every call, and Home called it
     * after every pin/tag/delete. Starting an already-running observer is now a no-op; use
     * [refresh] when the data really has to be reprocessed (the vault password changed). Attachment
     * ids are filled once per start - they are not part of the locked note body and survive a lock.
     */
    fun observe() {
        // DAT-7: a reload queued by a failed restore must not start collectors into a retry that
        // has already begun (the close-and-swap would then be racing live queries again). The
        // retry's own end() - or the next unlock - is what starts them.
        if (RestoreCoordinator.restoring.value) return
        // SEC-5/SEC-4: independent of the collectors. The sweeps need only the database and the
        // Keystore, and the guard inside makes the repeated calls (every unlock) free.
        sealLegacyBackfillsOnce()
        val alreadyObserving = observeKeysJob?.isActive == true && observeTagsJob?.isActive == true
        if (alreadyObserving) return
        if (observeKeysJob?.isActive != true) observeNotes()
        if (observeTagsJob?.isActive != true) observeTags()
        coroutineScope.launch { refreshAttachments() }
    }

    /**
     * SEC-5/SEC-4: seals legacy plaintext note rows and attachment captions, once per process.
     *
     * Both jobs protect data a restore can bring back readable - an upgraded install, or a database
     * restored from a portable archive - and neither can wait for each row's next save, which for
     * an unedited note or recording is never. They run in one coroutine so the note marker can be
     * read before the note sweep re-marks it: `RestoreSnapshots` clears only the note marker, and
     * seeing it absent here is what re-arms the caption sweep for the database it restored.
     *
     * They run off the main thread and publish nothing: [observe] must return immediately so the
     * list, the tags and the widget render on the normal path, and the sealed rows come back to the
     * UI through Room's own emission like any other write.
     *
     * Each persisted flag is only written when its sweep had no failures. A Keystore that is
     * temporarily unavailable must not turn those rows permanently plain: no flag means the next
     * launch tries again, and a successful retry is the only thing that ends the loop.
     */
    private fun sealLegacyBackfillsOnce() {
        // Claimed before any I/O: two observe() calls racing at startup must launch one pass, and
        // the winner is the only one allowed to write the flags.
        if (!legacyBackfillAttempted.compareAndSet(false, true)) return
        coroutineScope.launch(Dispatchers.IO) {
            // Read on IO: even the flag check is a disk access, and observe() runs on the UI thread.
            // It has to happen before the note sweep below, which is the only writer of this flag.
            val noteAlreadyDone = LegacyPlaintextBackfillFlag.isDone(context)
            if (!noteAlreadyDone) {
                // SEC-4: a restore that only knows the note flag (RestoreSnapshots' `.bak` undo)
                // cannot clear this one. An absent note marker therefore means the caption marker
                // may describe the database that was just replaced, so re-arm it here.
                LegacyCaptionBackfillFlag.reset(context)
            }

            sealLegacyPlaintextRows(noteAlreadyDone)
            sealLegacyCaptions()
        }
    }

    /** SEC-5 half of [sealLegacyBackfillsOnce]; [alreadyDone] was read before the caption reset. */
    private suspend fun sealLegacyPlaintextRows(alreadyDone: Boolean) {
        if (alreadyDone) return

        val failures = try {
            noteRepository.sealLegacyPlaintextRows()
        } catch (e: CancellationException) {
            // A real cancellation (the process going away) leaves the flag unwritten, so the
            // next launch retries - exactly what should happen for work that did not finish.
            throw e
        } catch (e: Exception) {
            // A closed database (a restore swapping the file) or an unreachable Keystore.
            Log.w(TAG, "Legacy plaintext sweep failed; retrying on the next launch", e)
            return
        }

        if (failures == 0) {
            LegacyPlaintextBackfillFlag.markDone(context)
        } else {
            Log.w(
                TAG,
                "$failures note rows are still stored as plain text; retrying on the next launch"
            )
        }
    }

    /** SEC-4 half of [sealLegacyBackfillsOnce]. */
    private suspend fun sealLegacyCaptions() {
        if (LegacyCaptionBackfillFlag.isDone(context)) return

        val failures = try {
            noteRepository.sealLegacyCaptions()
        } catch (e: CancellationException) {
            // Leaving the flag unwritten is exactly right for work that did not finish.
            throw e
        } catch (e: Exception) {
            // A closed database (a restore swapping the file) or an unreachable Keystore.
            Log.w(TAG, "Legacy caption sweep failed; retrying on the next launch", e)
            return
        }

        if (failures == 0) {
            LegacyCaptionBackfillFlag.markDone(context)
        } else {
            Log.w(
                TAG,
                "$failures attachment captions are still stored as plain text; retrying on the next launch"
            )
        }
    }

    /**
     * Re-reads the list through the current vault password.
     *
     * Replacing the collectors (rather than a plain [observe]) is deliberate: a fresh collector
     * receives the shared flow's replay - the last raw rows - and [processNotes] therefore runs
     * again with the password that has just been loaded. That is how the rows that were hidden
     * while the vault was locked come back without a database write.
     */
    fun refresh() {
        observeNotes()
        observeTags()
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
     * Kept for callers that cannot start collectors at all. Widgets must use [loadNoteOnce] now:
     * PERF-6 showed that a full library read per widget refresh decrypted every row, and the
     * launcher decides when that refresh happens. This method also replaces the shared [notes]
     * list, so a widget calling it would publish plaintext into whatever Home is rendering.
     */
    suspend fun loadNotesOnce() {
        // Same epoch guard as the collectors: this is a load a widget or screen waited on, and a
        // lock that landed while it decrypted must win over the load that was already in flight.
        val epoch = loadEpoch.current()
        val sourceNotes = withContext(Dispatchers.IO) { noteRepository.getAllNotes().first() }
        val processed = withContext(Dispatchers.Default) { processNotes(sourceNotes) }
        if (!loadEpoch.isCurrent(epoch)) return
        applyProcessed(processed)
    }

    /**
     * Reads exactly one row, for a widget that is configured to show it.
     *
     * PERF-6: the widget used to run [loadNotesOnce], which queries and decrypts the whole
     * library on every launcher refresh. `getNoteById` touches one row. Nothing global is written -
     * [notes], [tags], [hiddenVaultCount] and [decryptionResult] belong to the app's list, and a
     * widget filling them from one row would overwrite the state Home is rendering.
     *
     * @return the decrypted row, or null when it does not exist or is still sealed because the
     *   vault password is not loaded (the widget then renders its zero state, never ciphertext).
     */
    suspend fun loadNoteOnce(noteId: Int): Note? {
        if (noteId <= 0) return null
        val stored = withContext(Dispatchers.IO) {
            noteRepository.getNoteById(noteId).firstOrNull()
        } ?: return null
        // `getNoteById` has no `trashed = 0` filter (the editor and the recycle bin need it).
        // The widget used to read the live-list query, so a note moved to the bin disappeared
        // from it; keep that behaviour instead of showing deleted content on the launcher.
        if (stored.trashed) return null
        return withContext(Dispatchers.Default) {
            if (!stored.encrypted) {
                stored
            } else {
                val (decrypted, status) = decryptNote(stored)
                decrypted.takeIf { status == DecryptionResult.SUCCESS }
            }
        }
    }

    /**
     * Drops every decrypted copy of the note list (SEC-6).
     *
     * An app lock used to be a screen overlay: `LockManager` flipped a flag, the UI was hidden, and
     * every plaintext body stayed in the heap. This is the other half of the lock - it cancels the
     * collectors (otherwise the next Room emission repopulates [notes] behind the lock screen),
     * clears the list and drops the shared flow whose replay slot holds the last decrypted rows.
     *
     * The vault password and `NoteCrypto`'s cache are cleared by [LockManager] next to this call;
     * this class deliberately does not know about either. The unlock path calls [observe] again,
     * which is what brings the list back.
     *
     * V-5: cancelling is not enough. A decrypt that is already inside `Dispatchers.Default` runs to
     * completion (there is no suspension point to cancel), so [loadEpoch] is advanced here and the
     * collectors skip their assignment if the generation moved under them.
     */
    fun clearSensitiveState() {
        // Advance first, before anything else: a decrypt already running on Dispatchers.Default
        // checks this value when it finishes, and it must already see the new generation.
        loadEpoch.advance()
        observeKeysJob?.cancel()
        observeTagsJob?.cancel()
        observeKeysJob = null
        observeTagsJob = null
        // Dropping the reference releases the replay slot. The sharing coroutine stops on its own
        // because WhileSubscribed(0) has no subscribers left.
        notesSource = null
        notes = emptyList()
        // Derived from [notes]; a hidden drawer must not keep tag names either.
        tags = emptyList()
        hiddenVaultCount = 0
        // Not EMPTY: nothing has been loaded since the lock, and EMPTY would read as "no notes".
        decryptionResult = DecryptionResult.LOADING
    }

    /**
     * Decrypts one database emission into the state [NoteUseCase] publishes.
     *
     * V-5: pure with respect to the shared state. It used to assign [decryptionResult] and
     * [hiddenVaultCount] while it mapped, which meant a decrypt that finished after a lock had
     * already written those back (they are part of what the lock hides). The caller applies the
     * result only when [loadEpoch] still matches the generation it started in.
     */
    private fun processNotes(sourceNotes: List<Note>): ProcessedNotes {
        // A genuinely empty library is loaded, not loading: the home screen uses LOADING below to
        // decide whether to show its first-frame spinner, and this state used to survive forever.
        if (sourceNotes.isEmpty()) {
            return ProcessedNotes(emptyList(), DecryptionResult.EMPTY, 0)
        }
        var result = DecryptionResult.LOADING
        val hasUnencryptedNotes = sourceNotes.any { !it.encrypted }
        if (!hasUnencryptedNotes) {
            result = DecryptionResult.EMPTY
        }
        var hiddenVault = 0
        var sawPlainNote = false
        val processed = sourceNotes.mapNotNull { note ->
            if (note.encrypted) {
                val (decryptedNote, status) = decryptNote(note)
                result = status
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
            result == DecryptionResult.LOADING &&
            hiddenVault == 0
        ) {
            result = DecryptionResult.SUCCESS
        }
        return ProcessedNotes(processed, result, hiddenVault)
    }

    /** Publishes one [processNotes] result; always on the collector's thread (Main). */
    private fun applyProcessed(processed: ProcessedNotes) {
        decryptionResult = processed.decryptionResult
        hiddenVaultCount = processed.hiddenVaultCount
        notes = processed.notes
    }

    private fun observeNotes() {
        observeKeysJob?.cancel()
        observeKeysJob = coroutineScope.launch {
            sharedNotes().collectLatest { sourceNotes ->
                // Captured before the hop: a lock that lands while the block below runs advances
                // the generation, and the assignment must then not happen.
                val epoch = loadEpoch.current()
                // Decrypting every note touches the Keystore, so it must stay off the main thread.
                val processed = withContext(Dispatchers.Default) { processNotes(sourceNotes) }
                if (!loadEpoch.isCurrent(epoch)) return@collectLatest
                val previousIds = notes.map { it.id }
                applyProcessed(processed)
                // Refreshing on an unchanged list made the widget recompose for nothing.
                if (previousIds != processed.notes.map { it.id }) {
                    widgetRefresher.refreshAll()
                }
            }
        }
    }

    private fun observeTags() {
        observeTagsJob?.cancel()
        observeTagsJob = coroutineScope.launch {
            sharedNotes().collectLatest { notes ->
                // Same generation guard as observeNotes: a tag list derived after the lock is
                // still data from the decrypted library and must not be published either.
                val epoch = loadEpoch.current()
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
                if (!loadEpoch.isCurrent(epoch)) return@collectLatest
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
        // This is also the vault's lazy upgrade point (A4/PERF-2): decrypt() reads the v2 and
        // pre-PBKDF2 layouts without rewriting them, so an old note keeps its per-row key until the
        // user saves it. encrypt() always seals with the current v3 envelope, which upgrades the
        // row here - on a real save, never on a list read.
        return note.copy(
            name = encryptionHelper.encrypt(note.name),
            description = encryptionHelper.encrypt(note.description),
            encrypted = true
        )
    }

    /**
     * A4/PERF-2: re-seals every vault row that is not yet in the envelope (`v3:`) layout.
     *
     * Two formats predate the envelope: `v2:` rows derived a key with PBKDF2 from a salt embedded
     * in each row, and the legacy rows derived it with a single SHA-256 pass. Unlocking a library
     * used to pay one 120k-iteration derivation per `v2` row, which is what made a few hundred vault
     * notes take tens of seconds; the envelope derives one library KEK instead. This sweep is how an
     * existing library reaches that state: it runs once per unlock - the only moment the passphrase
     * is in memory - and rewrites every row it can positively authenticate.
     *
     * The first unlock of a library written by an older build still pays the old per-row PBKDF2
     * once, because those rows carry their own salts and the old password is the only thing that
     * opens them; there is no cheaper path without it. That is a one-time cost. After the sweep
     * every row is `v3:` and later unlocks derive nothing per row -
     * `EncryptionHelper.needsEnvelopeMigration` is a string-prefix check, so the sweep becomes a
     * single list read that writes nothing.
     *
     * A row where a field does not authenticate (wrong passphrase, or a row that was never really
     * vault-sealed) is left byte-for-byte untouched, and a blank field is never rewritten either:
     * there is no ciphertext to migrate, and inventing one would replace whatever the row holds.
     * The `encrypted = 1` column is untouched by `updateVaultFields`, and the query only matches such
     * rows, so a plain note can never receive vault ciphertext.
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
            val nameNeedsMigration = encryptionHelper.needsEnvelopeMigration(row.name)
            val bodyNeedsMigration = encryptionHelper.needsEnvelopeMigration(row.description)
            if (!nameNeedsMigration && !bodyNeedsMigration) return@forEach

            val sealedName =
                if (nameNeedsMigration) resealVaultValueOrNull(row.name, row.id) else row.name
            val sealedBody =
                if (bodyNeedsMigration) resealVaultValueOrNull(row.description, row.id) else row.description
            // Nothing is written unless every field that had to authenticate did.
            if (sealedName == null || sealedBody == null) return@forEach

            if (noteRepository.updateVaultFields(row.id, sealedName, sealedBody) > 0) upgraded++
        }
        upgraded
    }

    /**
     * Reads one sealed value and re-seals it into the current envelope.
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
        applyProcessed(processNotes(newNotes))
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

    private companion object {
        const val TAG = "NoteUseCase"
    }
}

/**
 * One decrypt pass' output: the list plus the two scalars that are part of the same emission.
 *
 * They travel together because the caller may no longer be allowed to publish any of them (V-5);
 * applying the list but not the counts, or the other way round, would leave the home screen
 * claiming "all notes failed" over an empty list.
 */
private data class ProcessedNotes(
    val notes: List<Note>,
    val decryptionResult: DecryptionResult,
    val hiddenVaultCount: Int,
)

/**
 * Generation counter that keeps a slow load from republishing what a lock just evicted (V-5).
 *
 * The lock path ([NoteUseCase.clearSensitiveState]) calls [advance]; every load captures [current]
 * before it starts and publishes only when [isCurrent] still holds. A cancelled collector stops at
 * its next suspension point, but the CPU-bound decrypt inside `Dispatchers.Default` does not
 * suspend, so cancellation alone can complete and assign afterwards. The epoch is the check that
 * makes "cleared" mean cleared. It is a separate object so the ordering rule can be tested without
 * a database, a Keystore or a running collector.
 */
internal class NoteLoadEpoch {
    private val generation = AtomicLong(0)

    /** The generation a loader captures before it starts working. */
    fun current(): Long = generation.get()

    /** Moves the list to a new generation; every earlier capture is stale from here on. */
    fun advance(): Long = generation.incrementAndGet()

    /** True while [epoch] is still the generation a load may publish into. */
    fun isCurrent(epoch: Long): Boolean = generation.get() == epoch
}
package com.ezpnix.writeon.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide record of the note rows whose stored text this process could not decrypt.
 *
 * The Android Keystore key can disappear (restoring a database backup onto another device, a
 * credential change, a corrupted entry), and then every sealed row fails to open.
 * `NoteCrypto.open` refuses to return an empty string for that case, so the failure surfaces as
 * an exception that used to travel straight through the list flow and crash the home screen.
 *
 * `EncryptedNoteDao` now degrades such a row instead: the row stays in the list with a
 * placeholder body, and its id lands here so that
 *  - the home screen can show "N notes could not be decrypted", and
 *  - every write path can refuse to write that placeholder back over the ciphertext.
 *
 * Design notes:
 *  - **Recompute, never accumulate.** Every full list refresh ([replaceScope]) replaces that
 *    source's contribution wholesale, so a row that starts decrypting again - or is deleted -
 *    stops being counted on the next emission instead of being remembered forever.
 *  - **Per-source scopes.** The active list and the recycle bin are separate Room flows that
 *    emit independently; letting either one replace a single shared set would make the banner
 *    count flicker between the two. The observed value is the union of every scope plus
 *    single-row reads (the editor's `getNoteById`, which runs when no list is on screen).
 *  - **Thread safety.** List flows are mapped on `Dispatchers.IO` and single-row reads happen
 *    wherever their collector runs, so every mutation happens under one lock and the StateFlows
 *    only ever receive fully computed sets (StateFlow reads are already thread-safe).
 */
object DecryptFailureRegistry {

    /** Scope of the non-trashed list (`getAllNotes` / `getAllNotesSync`). */
    const val SCOPE_ACTIVE = "active"

    /** Scope of the recycle bin (`getTrashedNotes`). */
    const val SCOPE_TRASHED = "trashed"

    /** What one refresh source saw: every row id it emitted, and the subset it could not open. */
    private class Scope(val rowIds: Set<Int>, val failedIds: Set<Int>)

    private val lock = Any()
    private val scopes = HashMap<String, Scope>()

    /**
     * Ids that failed on a single-row read.
     *
     * Kept separately from [scopes] because a single-row read has no idea whether the row is
     * active or trashed, and because it must survive until a full refresh confirms the row's
     * fate (still there and readable -> cleared, gone -> dropped).
     */
    private val observedFailures = HashSet<Int>()

    private val _failedIds = MutableStateFlow<Set<Int>>(emptySet())

    /** Every id this process could not decrypt, across all scopes and single-row reads. */
    val failedIds: StateFlow<Set<Int>> = _failedIds.asStateFlow()

    private val _failedCount = MutableStateFlow(0)

    /** [failedIds] as a plain count, for the home-screen banner. */
    val failedCount: StateFlow<Int> = _failedCount.asStateFlow()

    /** True when [id]'s stored text could not be decrypted; write paths must skip such rows. */
    fun isFailed(id: Int): Boolean = id in _failedIds.value

    /**
     * Recomputes one source's contribution from a full refresh.
     *
     * @param rowIds every row the refresh emitted, whether or not it decrypted.
     * @param failedIds the subset of [rowIds] whose stored text could not be opened.
     */
    fun replaceScope(scope: String, rowIds: Set<Int>, failedIds: Set<Int>) {
        synchronized(lock) {
            scopes[scope] = Scope(rowIds, failedIds)
            // Rows this refresh could read clear an earlier single-row failure; ids of rows that
            // no longer exist are dropped altogether. The count must never grow on its own.
            val live = HashSet<Int>()
            scopes.values.forEach { live.addAll(it.rowIds) }
            observedFailures.removeAll(rowIds - failedIds)
            observedFailures.retainAll(live)
            publishLocked()
        }
    }

    /** Records a failure seen on a single-row read (the editor's `getNoteById`). */
    fun markFailed(id: Int) {
        synchronized(lock) {
            observedFailures.add(id)
            publishLocked()
        }
    }

    /**
     * Clears [id] after a read that could open it, undoing an older transient failure (the
     * Keystore can fail briefly while the device is locked and succeed afterwards).
     */
    fun markReadable(id: Int) {
        synchronized(lock) {
            observedFailures.remove(id)
            // Copy first: replacing a value while iterating the map is legal for HashMap, but
            // iterating a snapshot costs nothing at this size and cannot surprise a reader.
            for ((name, scope) in scopes.toMap()) {
                if (id in scope.failedIds) scopes[name] = Scope(scope.rowIds, scope.failedIds - id)
            }
            publishLocked()
        }
    }

    /** Recomputes both public flows from the current per-source state. Caller holds [lock]. */
    private fun publishLocked() {
        val all = HashSet<Int>()
        scopes.values.forEach { all.addAll(it.failedIds) }
        all.addAll(observedFailures)
        _failedIds.value = all
        _failedCount.value = all.size
    }
}

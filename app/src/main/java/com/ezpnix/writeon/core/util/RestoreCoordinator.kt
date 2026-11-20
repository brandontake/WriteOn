package com.ezpnix.writeon.core.util

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DAT-7: the one process-wide "a restore has closed the Room connection" signal.
 *
 * A restore (`ImportExportRepository`, `CloudRestoreWorker`, `RestoreSnapshots`) closes the
 * process-wide Room connection and swaps the database file. Every screen and widget that is still
 * collecting a Flow is then talking to a closed connection, which is the crash window the audit
 * describes: `attempt to re-open an already-closed object` before the process restarts.
 *
 * Two things have to happen at that moment, and they have to happen *before* `close()`:
 * [begin] is published so the UI can cover the app, and every registered listener runs so the
 * shared collectors stop querying. A restore that fails or rolls back calls [end], which is what
 * brings the note list back without a process restart.
 *
 * This is deliberately a plain `object`, like [BackupRestoreLock]: `WebDAVRestoreWorker` is built
 * by WorkManager rather than Hilt, and `NoteUseCase` registers itself directly in its constructor.
 * Neither route needs an injected instance, so the coordinator cannot introduce a Hilt cycle
 * (`NoteUseCase` -> coordinator -> `NoteUseCase`).
 *
 * Only transitions are published: a second [begin] while already restoring is a no-op, so a
 * nested/duplicate restore call cannot fire the pause twice, and a successful restore is allowed
 * to leave the flag set - the process is about to restart and a fresh process starts at false.
 */
object RestoreCoordinator {

    private val _restoring = MutableStateFlow(false)

    /** True from the moment a restore is about to close Room until it fails or the process dies. */
    val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

    /**
     * Listeners are `CopyOnWriteArrayList`s because [onBegin]/[onEnd] run during construction of a
     * singleton while a worker may already be iterating the list from another thread. Copy-on-write
     * makes registration safe without holding [transitionLock] across listener code.
     */
    private val beginListeners = CopyOnWriteArrayList<() -> Unit>()
    private val endListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Guards [currentlyRestoring] plus the publish/snapshot of the listener list. */
    private val transitionLock = Any()
    private var currentlyRestoring = false

    fun onBegin(listener: () -> Unit) {
        beginListeners.add(listener)
    }

    fun onEnd(listener: () -> Unit) {
        endListeners.add(listener)
    }

    /**
     * Enters the restoring state and pauses every registered collector.
     *
     * Idempotent: while [currentlyRestoring] is true this only returns, so a duplicated call (for
     * example a worker retry while a previous attempt is still rolling back) does not emit a second
     * transition or a second `clearSensitiveState()`.
     */
    fun begin() {
        val listeners = synchronized(transitionLock) {
            if (currentlyRestoring) return
            currentlyRestoring = true
            _restoring.value = true
            beginListeners.toList()
        }
        notifyListeners(listeners)
    }

    /**
     * Leaves the restoring state and reopens what [begin] paused.
     *
     * Used by every failure/rollback path only. Success leaves the flag set because the database
     * that is now live is not the one Room opened, and only a fresh process may query it.
     */
    fun end() {
        val listeners = synchronized(transitionLock) {
            if (!currentlyRestoring) return
            currentlyRestoring = false
            _restoring.value = false
            endListeners.toList()
        }
        notifyListeners(listeners)
    }

    /**
     * Runs listeners outside [transitionLock].
     *
     * A listener that throws is swallowed on purpose: it runs from inside a restore's critical
     * section, and one broken observer must not turn a recoverable database swap into a reported
     * failure or leave the restore half-way through with the connection already closed.
     */
    private fun notifyListeners(listeners: List<() -> Unit>) {
        listeners.forEach { listener ->
            try {
                listener()
            } catch (_: Exception) {
                // See the doc comment: a restore must not be aborted by its own observer.
            }
        }
    }

    /** Test hook: a fresh, not-restoring coordinator with no listeners. */
    internal fun resetForTest() {
        synchronized(transitionLock) {
            currentlyRestoring = false
            _restoring.value = false
            beginListeners.clear()
            endListeners.clear()
        }
    }
}

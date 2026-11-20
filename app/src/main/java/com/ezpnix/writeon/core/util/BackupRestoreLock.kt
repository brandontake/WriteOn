package com.ezpnix.writeon.core.util

import kotlinx.coroutines.sync.Mutex

/**
 * DAT-8: the one process-wide lock every restore/backup path must share.
 *
 * Being a Kotlin `object` rather than a Hilt-provided instance is the point: Workers are built by
 * WorkManager, not Hilt, so an entry point can reach a provider singleton but a plain producer
 * cannot. A single top-level instance is reachable from both sides without changing construction,
 * and `DataModule.provideMutex` returns this same object so injected repositories and entry points
 * wait on the identical lock.
 *
 * Producers (`BackupWorker`, `TxtBackupWorker`, `CloudBackupWorker`) still need to wrap their work
 * in [mutex] - that wiring lives in files this change does not own.
 */
object BackupRestoreLock {

    /** Held across "close Room, replace the database, install attachments". */
    val mutex = Mutex()
}

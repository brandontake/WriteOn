package com.ezpnix.writeon.presentation.screens.settings

import android.content.Context
import androidx.work.WorkerParameters

/**
 * Compatibility shim for work enqueued by 2.0.4 and earlier.
 *
 * WorkManager persists the worker's fully qualified class name in its database. The real worker
 * moved to `com.ezpnix.writeon.data.backup` in 2.0.4; without this subclass an already-scheduled
 * automatic backup (or a pending WebDAV transfer) would fail with `ClassNotFoundException` after
 * the update. New code always enqueues the data-layer class.
 */
@Deprecated("Use com.ezpnix.writeon.data.backup.BackupWorker")
class BackupWorker(
    context: Context,
    params: WorkerParameters,
) : com.ezpnix.writeon.data.backup.BackupWorker(context, params)

package com.ezpnix.writeon.presentation.screens.settings

import android.content.Context
import androidx.work.WorkerParameters

/** Compatibility shim for a WebDAV backup enqueued before 2.0.4; see [BackupWorker]. */
@Deprecated("Use com.ezpnix.writeon.data.backup.WebDAVBackupWorker")
class WebDAVBackupWorker(
    context: Context,
    params: WorkerParameters,
) : com.ezpnix.writeon.data.backup.WebDAVBackupWorker(context, params)

package com.ezpnix.writeon

import android.app.Application
import android.util.Log
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.presentation.components.markdown.AttachmentRegistry
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class App : Application() {

    /**
     * Process-scoped on purpose (V-8): a restore killed between the database swap and the last
     * attachment move leaves a journal behind, and this is where it is replayed. The job is
     * independent of any screen, so it also survives the activity that started a restore.
     */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val context = applicationContext
        // SEC-4: the renderers resolve `attachment://` targets through this registry, and an
        // encrypted attachment can only be shown through a decrypted cache copy. The context is
        // the application scope, not an Activity: resolving happens from players, widgets and
        // background share jobs long after any Activity is gone.
        AttachmentRegistry.initialize(context)
        startupScope.launch {
            // Cheap `isFile` check first; only an interrupted install touches files. The journal
            // lives in noBackupFilesDir (V-8), so this also survives a cache eviction between the
            // database swap and the last attachment move. The result decides whether the full D7
            // reconciliation is needed - a normal launch has no journal, and scanning every
            // attachment row on every start would be pure cost.
            val pendingInstall = AttachmentArchive.hasPendingInstall(context)
            runCatching {
                AttachmentArchive.recoverPendingInstall(context)
            }.onFailure { error ->
                Log.w(TAG, "Startup attachment install recovery failed", error)
            }
            if (pendingInstall) {
                runCatching {
                    AttachmentArchive.reconcileAfterRestore(
                        context,
                        context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Startup attachment reconciliation failed", error)
                }
            }
        }
    }

    private companion object {
        const val TAG = "App"
    }
}

package com.ezpnix.writeon

import android.app.Application
import android.os.Build
import android.util.Log
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.attachment.LegacyMediaMigration
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.util.BackupRestoreLock
import com.ezpnix.writeon.core.util.RestoreSnapshots
import com.ezpnix.writeon.data.backup.RestoreJournal
import com.ezpnix.writeon.data.backup.StartupCleanup
import com.ezpnix.writeon.data.local.database.LegacyCaptionBackfillFlag
import com.ezpnix.writeon.data.local.database.LegacyPlaintextBackfillFlag
import com.ezpnix.writeon.presentation.components.markdown.AttachmentRegistry
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

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
        // N-1: a restore killed between the database move and the salt/deck apply leaves a journal.
        // The check is one `isFile` on a normal launch; when it is present the recovery must finish
        // (or discard) the pending prefs transition before any screen or Room query can observe the
        // database. It is therefore synchronous, unlike the attachment replay below.
        val journalResult = runCatching { RestoreJournal.recover(context) }
            .onFailure { error ->
                Log.w(TAG, "Startup restore-journal recovery failed", error)
                // REG-01: an unexpected throw may have happened after the database move; clearing
                // both flags costs one sweep on a normal launch and prevents an unswept restore
                // from being trusted as "already sealed" forever.
                runCatching { LegacyPlaintextBackfillFlag.reset(context) }
                runCatching { LegacyCaptionBackfillFlag.reset(context) }
            }
            .getOrNull()
        // REG-01: a failed apply keeps the journal for the salt/deck/attachment retry, but the
        // restored portable database is plaintext and live either way. Clear both backfill flags
        // defensively here as well, so the normal startup sweep always runs even if the journal
        // recorded a state an older build left behind. The failure is logged, never swallowed.
        if (journalResult is RestoreJournal.RecoverResult.Failed) {
            Log.w(
                TAG,
                "Restore journal did not finish (${journalResult.stage}); keeping it for retry " +
                    "and running the plaintext backfill"
            )
            runCatching { LegacyPlaintextBackfillFlag.reset(context) }
            runCatching { LegacyCaptionBackfillFlag.reset(context) }
        }
        // R2-09/P3: the restored database is live but the staging tree its attachments were
        // supposed to come from is gone. The journal is discarded (nothing can be retried); the
        // reconciliation below reports the rows whose file is missing instead of calling this a
        // clean completion.
        val stagingMissingAfterRestore =
            journalResult is RestoreJournal.RecoverResult.MissingAttachments ||
                (journalResult is RestoreJournal.RecoverResult.Failed &&
                    journalResult.missingStaging != null)
        if (stagingMissingAfterRestore) {
            Log.e(
                TAG,
                "Restore journal referenced a missing attachment staging tree; reconciling the " +
                    "restored database"
            )
        }
        startupScope.launch {
            // N-2: only the attachment tree named by the current `.bak` sidecar is still reachable
            // by the undo; anything else is a candidate from a restore that never published.
            runCatching { RestoreSnapshots.pruneAttachmentSnapshots(context) }
                .onFailure { error -> Log.w(TAG, "Startup attachment-snapshot cleanup failed", error) }
            // Cheap `isFile` check first; only an interrupted install touches files. The journal
            // lives in noBackupFilesDir (V-8), so this also survives a cache eviction between the
            // database swap and the last attachment move. The result decides whether the full D7
            // reconciliation is needed - a normal launch has no journal, and scanning every
            // attachment row on every start would be pure cost.
            //
            // R4-03: the replay runs under the shared restore lock. A restore/import worker can be
            // running in this process; interpreting its journal while it is between the database
            // swap and the install would replay (or roll back) a plan that is still in flight.
            runCatching {
                BackupRestoreLock.mutex.withLock {
                    val pendingInstall = AttachmentArchive.hasPendingInstall(context)
                    runCatching {
                        AttachmentArchive.recoverPendingInstall(context)
                    }.onFailure { error ->
                        Log.w(TAG, "Startup attachment install recovery failed", error)
                    }
                    if (pendingInstall || stagingMissingAfterRestore ||
                        AttachmentArchive.hasPendingInstall(context)
                    ) {
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
            }.onFailure { error ->
                Log.w(TAG, "Startup attachment recovery could not take the restore lock", error)
            }
            // DAT-05/SEC-11: a killed restore or export leaves plaintext archives in `cacheDir`
            // and extracted attachment trees in the per-operation staging directories. Neither is
            // live state any more (a journal that still needs a staging tree was replayed above,
            // and cleanup skips exactly those directories), so remove them before any screen can
            // run. Both helpers are conservative: only this app's temp names, only files/dirs
            // older than a few minutes, and the durable work tree is never touched.
            runCatching {
                // R2-09: a restore that failed after the database move keeps its journal, which
                // names the staging tree the next attempt installs from. Do not let the age gate
                // delete that tree on a later launch.
                AttachmentArchive.cleanupAbandonedStaging(
                    context,
                    protectedDirectories = RestoreJournal.pendingStagingDirectories(context),
                )
            }.onFailure { error ->
                Log.w(TAG, "Startup restore-staging cleanup failed", error)
            }
            runCatching {
                StartupCleanup.cleanupCacheTemps(context)
            }.onFailure { error ->
                Log.w(TAG, "Startup plaintext temp cleanup failed", error)
            }
            // SEC-08: media written before SEC-4 was never passed through MediaVault and an old
            // picture that is never edited again would stay plaintext forever. The pass is one
            // non-recursive scan of the two private media roots (plus the 2.0.x external root) on a
            // background dispatcher; it is skipped entirely once its dedicated marker is set, and a
            // failure leaves the marker absent so the next launch retries.
            //
            // Robolectric runs this real Application inside the JVM tests' shared data dir. A
            // background pass there would encrypt fixtures while a test is still using them and
            // race every test's own prefs marker, so it only runs on a device/emulator.
            if (Build.FINGERPRINT != "robolectric") {
                runCatching {
                    LegacyMediaMigration.migrateIfNeeded(context)
                }.onSuccess { result ->
                    // R2-02: the pass seals files in place while the process may already have
                    // rendered them as plaintext. Even an incomplete pass can have encrypted some,
                    // so any positive count invalidates the registry's sniffed classification.
                    if (result.encrypted > 0) {
                        AttachmentRegistry.clearEncryptionClassification()
                    }
                    if (result.failures > 0 || result.deferred > 0) {
                        Log.w(
                            TAG,
                            "Legacy media migration incomplete (${result.failures} failed, " +
                                "${result.deferred} still being written); retrying next launch"
                        )
                    }
                }.onFailure { error ->
                    // A throw can still have encrypted earlier files before it stopped.
                    AttachmentRegistry.clearEncryptionClassification()
                    Log.w(TAG, "Startup legacy media migration failed", error)
                }
            }
        }
    }

    private companion object {
        const val TAG = "App"
    }
}

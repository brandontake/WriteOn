package com.ezpnix.writeon.data.backup

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.attachment.AttachmentSnapshots
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.security.SharedPreferencesVaultKeyStore
import com.ezpnix.writeon.core.util.BackupArchiveCrypto
import com.ezpnix.writeon.core.util.DatabaseRestore
import com.ezpnix.writeon.core.util.RestoreCoordinator
import com.ezpnix.writeon.data.local.database.BackupDatabase
import com.ezpnix.writeon.data.local.database.LegacyCaptionBackfillFlag
import com.ezpnix.writeon.data.local.database.LegacyPlaintextBackfillFlag
import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.jakewharton.processphoenix.ProcessPhoenix
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.ZipInputStream

@Suppress("DEPRECATION")
internal val Context.webDavDataStore: DataStore<Preferences> by preferencesDataStore(name = WebDavPrefs.STORE_NAME)

/**
 * Names of the WebDAV settings shared between the settings screen and the background workers.
 *
 * The backup worker used to read `webdav_url` and friends from a plain SharedPreferences file while
 * this screen wrote them to a DataStore of the same name, so the worker never saw anything the user
 * had typed and every backup failed with an empty URL. Both sides now go through one DataStore
 * under one set of constants.
 */
object WebDavPrefs {
    const val STORE_NAME = "webdav_prefs"
    const val URL = "webdav_url"
    const val USERNAME = "webdav_username"

    /** Encrypted with Tink; never stored in the clear. See [WebDavPrefsCipher]. */
    const val PASSWORD = "webdav_password"

    /**
     * Passphrase protecting the uploaded archive, also stored through Tink.
     *
     * The restore worker previously looked for this under a SharedPreferences key that nothing ever
     * wrote, so cloud restore could not succeed. It is written by the same dialog that asks for the
     * upload passphrase, and read back here.
     */
    const val BACKUP_PASSPHRASE = "encryption_password"

    /**
     * Validator of the archive currently on the server, as reported by the last successful upload.
     * It is how an upload notices that another device replaced the file in the meantime.
     */
    const val ETAG = "webdav_etag"

    /**
     * Last-Modified of the archive currently on the server, as reported by the last download or
     * upload. On a server that offers no ETag it is the weaker validator the backup worker falls
     * back to (W5); both sides treat "either validator is available" as enough to stay
     * conditional.
     */
    const val LAST_MODIFIED = "webdav_last_modified"

    /**
     * True once this install has written the remote archive (or restored from it). It lets an upload
     * distinguish "the server has a file but provides no ETag, and we know we wrote it" from "the
     * server has a file we have never seen", which must not be overwritten.
     */
    const val REMOTE_WRITTEN = "webdav_remote_written"

    /**
     * R2-01: validators of a canonical file this install published but could not verify.
     *
     * An unverified publication is not "someone else's file": the bytes on the server are the
     * truncated or misunderstood result of this run's own MOVE/PUT. Forgetting every validator
     * made the next run classify that file as a stranger, park yet another conflict copy and never
     * repair the canonical archive. These two keys record what the failed run actually saw, so the
     * next run may overwrite *that exact file* conditionally. The value is never treated as proof
     * of ownership for any other remote state.
     */
    const val REPAIR_ETAG = "webdav_repair_etag"
    const val REPAIR_LAST_MODIFIED = "webdav_repair_last_modified"

    /**
     * R3-02: the canonical URL the lease was created for, and how many automatic repair runs it
     * has driven.
     *
     * WebDAV validators are only meaningful for one URL. Saving a new server must not let an old
     * lease match a same-named file there, and a lease that keeps failing must stop after
     * [WebDavRepairLease.MAX_REPAIR_ATTEMPTS] instead of retrying forever.
     */
    const val REPAIR_URL = "webdav_repair_url"
    const val REPAIR_ATTEMPTS = "webdav_repair_attempts"
}

/** Tink keyset for the WebDAV credentials, shared by the settings screen and the workers. */
internal object WebDavPrefsCipher {
    private const val KEYSET_NAME = "webdav_keyset"
    private const val MASTER_KEY_URI = "android-keystore://webdav_master_key"

    fun aead(context: Context): Aead {
        com.google.crypto.tink.aead.AeadConfig.register()
        return AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, WebDavPrefs.STORE_NAME)
            .withKeyTemplate(com.google.crypto.tink.aead.AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    fun encrypt(aead: Aead, value: String): String =
        Base64.getEncoder().encodeToString(aead.encrypt(value.toByteArray(), ByteArray(0)))

    /**
     * Returns the plaintext, or null when the value cannot be read.
     *
     * Values written by older builds used `android.util.Base64.DEFAULT`, which wraps long output
     * across lines and appends a newline. Those line breaks are stripped before decoding so an
     * upgrade does not silently discard credentials the user already saved; the strict decoder
     * rejects them outright.
     */
    fun decryptOrNull(aead: Aead, value: String): String? = try {
        val encoded = value.filterNot { it.isWhitespace() }
        String(aead.decrypt(Base64.getDecoder().decode(encoded), ByteArray(0)))
    } catch (e: Exception) {
        null
    }
}

/**
 * W15: reach the shared restore lock from a worker WorkManager constructs directly.
 *
 * This worker is not built by Hilt (the app has no `HiltWorkerFactory`), so it cannot be
 * injected with the `@Singleton Mutex` that `DataModule.provideMutex` creates and
 * `ImportExportRepository` holds around every local import/export. An entry point hands it the
 * *same* instance: mutual exclusion between "restore from WebDAV" and "import/export on this
 * device" is that one object, not a look-alike lock nobody else waits on.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RestoreLockEntryPoint {
    /** The singleton restore/export mutex from `DataModule.provideMutex`. */
    fun restoreMutex(): Mutex
}

open class WebDAVRestoreWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    /**
     * W10/W11: downloads use the shared client's timeout/redirect policy via
     * [WebDavHttp.clientForTransfer], which adds the SYN-7 transfer budget (or removes the
     * whole-call ceiling when the size is not known yet) without following a redirect.
     */

    /** Main-looper handler used to post the post-restore restart after the result is recorded. */
    private val restartHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Terminal failure with a user-visible outcome (W3): restore results used to exist only in
     * logcat, and the "restore started" toast made a failure indistinguishable from success.
     *
     * [messageRes] lets a refusal with a known cause (D8: incompatible archive) say what is
     * actually wrong instead of the generic "restore failed".
     */
    private fun failWithToast(messageRes: Int = R.string.cloud_webdav_restore_failed): Result {
        restartHandler.post {
            Toast.makeText(
                applicationContext,
                applicationContext.getString(messageRes),
                Toast.LENGTH_LONG
            ).show()
        }
        return Result.failure()
    }

    /**
     * W9: retries only outcomes that can clear on their own, and only while attempts remain.
     *
     * The download branch used to return [Result.retry] for *every* non-2xx response, so a wrong
     * URL (404), wrong credentials (401/403) or a folder instead of a file spun forever; because
     * the unique work chain is shared, an unbackable restore also blocked every later backup.
     */
    private fun retryOrFail(statusCode: Int, detail: String): Result {
        if (WebDavRetry.shouldRetry(statusCode, runAttemptCount)) {
            Log.w(TAG, "$detail (HTTP $statusCode); attempt ${runAttemptCount + 1} of ${WebDavRetry.MAX_RUN_ATTEMPTS + 1}")
            return Result.retry()
        }
        Log.e(TAG, "$detail (HTTP $statusCode); giving up after ${runAttemptCount + 1} attempts")
        return failWithToast()
    }

    /**
     * W11: a redirect is terminal.
     *
     * Following it is what turned a failed download into a `200 OK` carrying a login page, which
     * the worker then installed as a "backup". Repeating the request cannot change the answer.
     */
    private fun failWithRedirect(): Result {
        Log.e(TAG, "Refusing a WebDAV redirect instead of following it")
        return failWithToast(R.string.cloud_webdav_redirect_refused)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val tempEncryptedFile = File(applicationContext.cacheDir, BACKUP_FILENAME)
        try {
            val prefs = applicationContext.webDavDataStore.data.first()
            val configuredUrl = prefs[stringPreferencesKey(WebDavPrefs.URL)] ?: return@withContext failWithToast()
            // An explicit URL (the conflict-copy restore) overrides the configured one. It is a
            // different file from the canonical archive, so its validator must never be stored as
            // though it described the canonical one.
            val restoreUrl = inputData.getString(DATA_RESTORE_URL)?.takeIf { it.isNotBlank() } ?: configuredUrl
            // Cleartext http is accepted on purpose (see the note in Cloud.kt and the
            // `usesCleartextTraffic` attribute in the manifest). Do not add a rejection here.
            val restoringOtherFile = restoreUrl != configuredUrl
            val username = prefs[stringPreferencesKey(WebDavPrefs.USERNAME)] ?: return@withContext failWithToast()
            val encryptedPassword = prefs[stringPreferencesKey(WebDavPrefs.PASSWORD)]
                ?: return@withContext failWithToast()
            val aead = WebDavPrefsCipher.aead(applicationContext)
            val password = WebDavPrefsCipher.decryptOrNull(aead, encryptedPassword)
                ?: return@withContext failWithToast(R.string.cloud_credentials_unreadable)

            // The archive passphrase lives in the same encrypted store, so it survives a reboot the
            // same way the WebDAV password does.
            val encryptedPassphrase = prefs[stringPreferencesKey(WebDavPrefs.BACKUP_PASSPHRASE)]
                ?: return@withContext failWithToast()
            val userPassword = WebDavPrefsCipher.decryptOrNull(aead, encryptedPassphrase)
                ?: return@withContext failWithToast(R.string.cloud_credentials_unreadable)

            val request = Request.Builder()
                .url(restoreUrl)
                .header("Authorization", Credentials.basic(username, password))
                .build()

            var downloadedEtag: String? = null
            var downloadedLastModified: String? = null
            // SYN-7: an archive with recordings can be hundreds of megabytes, and its size is not
            // known before the response headers arrive. The download therefore has no whole-call
            // ceiling; the read timeout still ends a stalled connection, and copyWithLimit ends an
            // oversized body.
            WebDavHttp.clientForTransfer(expectedBytes = null).newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // W11 first: a redirect is never worth another attempt, and 401/403/404 are
                    // terminal too (W9) - the same request cannot start working on its own.
                    if (WebDavRetry.isRedirect(response.code)) return@withContext failWithRedirect()
                    return@withContext retryOrFail(
                        response.code,
                        "WebDAV restore download was rejected"
                    )
                }
                downloadedEtag = normalizeEtag(response.header("ETag"))
                // W5: the validator to remember on servers that offer no ETag at all.
                downloadedLastModified = response.header("Last-Modified")
                val body = response.body ?: return@withContext failWithToast()

                // W10: the body used to be copied with no ceiling and no room check, so a URL that
                // answered with something enormous (a captive portal, a misconfigured folder) could
                // fill the device before the integrity check ever ran.
                val declaredLength = response.header("Content-Length")?.toLongOrNull()
                if (declaredLength != null && declaredLength > WebDavHttp.MAX_ARCHIVE_BYTES) {
                    Log.e(TAG, "Refusing an oversized restore download: $declaredLength bytes")
                    return@withContext failWithToast(R.string.cloud_restore_too_large)
                }
                try {
                    WebDavHttp.ensureFreeSpace(
                        tempEncryptedFile.parentFile ?: applicationContext.cacheDir,
                        declaredLength ?: 0L
                    )
                } catch (e: IOException) {
                    Log.e(TAG, "Not enough free space for the downloaded archive", e)
                    return@withContext failWithToast(R.string.cloud_restore_no_space)
                }
                try {
                    body.byteStream().use { input ->
                        FileOutputStream(tempEncryptedFile).use { output ->
                            WebDavHttp.copyWithLimit(input, output)
                        }
                    }
                } catch (e: WebDavHttp.ArchiveTooLargeException) {
                    // The server lied about (or omitted) Content-Length and the body crossed the
                    // ceiling mid-stream. Terminal: the next run would download the same bytes.
                    Log.e(TAG, "Restore download exceeded the archive size limit", e)
                    return@withContext failWithToast(R.string.cloud_restore_too_large)
                }
            }

            // W6: record what the archive claims about itself *before* anything is replaced. A
            // legacy archive without a manifest is perfectly legal - it simply has no known age.
            val manifest = readArchiveManifest(tempEncryptedFile, userPassword)
            Log.i(
                TAG,
                if (manifest == null) {
                    "Restoring an archive without a manifest; backup age unknown (legacy archive)"
                } else {
                    "Restoring archive backed up at ${manifest.createdAtMillis} " +
                        "with ${manifest.noteCount} notes and ${manifest.attachmentCount} attachments"
                }
            )

            unzipAndReplaceDatabase(tempEncryptedFile, userPassword)

            // SYN-8: the restore has already happened, so a failure to *store* the download's
            // validators must not retry the whole download and restore. This edit used to be
            // unguarded: a DataStore I/O error (disk full, for instance) returned `retry()` after
            // the database had been replaced, causing a second full restore and a restore screen
            // that never cleared. Losing the validators is benign - the next upload then treats
            // the remote as someone else's and makes a conflict copy instead of overwriting it.
            try {
                applicationContext.webDavDataStore.edit { stored ->
                    val etagKey = stringPreferencesKey(WebDavPrefs.ETAG)
                    val lastModifiedKey = stringPreferencesKey(WebDavPrefs.LAST_MODIFIED)
                    // R2-01: a restore replaces what this install knows about the remote, so a
                    // repair lease from an earlier failed upload no longer describes anything.
                    stored.remove(stringPreferencesKey(WebDavPrefs.REPAIR_ETAG))
                    stored.remove(stringPreferencesKey(WebDavPrefs.REPAIR_LAST_MODIFIED))
                    stored.remove(stringPreferencesKey(WebDavPrefs.REPAIR_URL))
                    stored.remove(intPreferencesKey(WebDavPrefs.REPAIR_ATTEMPTS))
                    if (restoringOtherFile) {
                        // This was a conflict copy, not the canonical archive: its validator says
                        // nothing about the file the next upload will target. Clear both markers so
                        // the upload path treats the canonical file as "someone else's" and makes a
                        // fresh conflict copy instead of overwriting it.
                        stored.remove(etagKey)
                        stored.remove(lastModifiedKey)
                        stored[booleanPreferencesKey(WebDavPrefs.REMOTE_WRITTEN)] = false
                    } else {
                        // The remote archive is now the database we just installed. Remember the
                        // validators that belong to it (instead of deleting them) so the next
                        // upload does not treat the file as a stranger and cannot silently
                        // overwrite another device's copy. Either validator keeps that upload
                        // conditional (W5).
                        if (downloadedEtag.isNullOrBlank()) stored.remove(etagKey) else stored[etagKey] = downloadedEtag!!
                        if (downloadedLastModified.isNullOrBlank()) {
                            stored.remove(lastModifiedKey)
                        } else {
                            stored[lastModifiedKey] = downloadedLastModified!!
                        }
                        stored[booleanPreferencesKey(WebDavPrefs.REMOTE_WRITTEN)] = true
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Restore succeeded but the WebDAV validators could not be stored", e)
            }

            // SYN-04: restoring a parked conflict copy is the merge that copy's notice was asking
            // for, so resolve exactly that notice once the restore succeeded. Only the copy's own
            // URL is dismissed (other parked copies keep their notices), and a canonical restore
            // leaves the list alone because it proves nothing about any copy.
            if (restoringOtherFile) {
                WebDavBackupState.dismissConflict(applicationContext, restoreUrl)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    applicationContext,
                    applicationContext.getString(R.string.cloud_webdav_restore_completed),
                    Toast.LENGTH_LONG
                ).show()
                // The restart is posted *after* doWork returns: killing the process from inside the
                // worker (the old `delay(1000); triggerRebirth()`) meant WorkManager never got to
                // record `Result.success()`, so the unique work stayed RUNNING forever and every
                // later backup or restore was silently dropped by ExistingWorkPolicy.KEEP. The two
                // seconds give WorkManager time to persist the result before the process dies.
                restartHandler.postDelayed({ ProcessPhoenix.triggerRebirth(applicationContext) }, RESTART_DELAY_MS)
            }

            Result.success()
        } catch (e: DatabaseRestore.IncompatibleArchiveException) {
            // D8: the archive is not something this build may install (a newer schema, or a
            // database this app's Room schema does not recognise). Retrying would download the
            // same archive and fail the same way, so this is terminal - and the user is told
            // which of the two it was instead of a generic "restore failed".
            Log.e(TAG, "Refusing to restore an incompatible archive: ${e.message}", e)
            failWithToast(
                when (e.incompatibility) {
                    DatabaseRestore.Incompatibility.NEWER_VERSION -> R.string.cloud_restore_newer_version
                    else -> R.string.cloud_restore_incompatible_db
                }
            )
        } catch (e: IOException) {
            // A wrong passphrase surfaces as an IOException wrapping the crypto failure (the
            // streaming cipher stream rethrows it); retrying would spin on every run, so it is
            // terminal. A real network/IO problem still deserves a retry - but a bounded one (W9).
            val cause = e.cause
            // SYN-9: a malformed archive is deterministic - an HTML login page saved under the
            // archive name, a truncated zip, an illegal entry. The same bytes fail every time, so
            // retrying only re-downloads them (up to six times, each capped at 1 GiB) and keeps
            // the sync chain busy. Only transport/5xx/429/space problems are worth another run.
            val malformedArchive = e is java.util.zip.ZipException ||
                cause is java.util.zip.ZipException
            if (malformedArchive) {
                Log.e(TAG, "WebDAV restore archive is not a valid archive; not retrying", e)
                failWithToast()
            } else if (cause is javax.crypto.BadPaddingException || cause is javax.crypto.IllegalBlockSizeException) {
                Log.e(TAG, "WebDAV restore archive failed authentication", e)
                failWithToast()
            } else if (runAttemptCount < WebDavRetry.MAX_RUN_ATTEMPTS) {
                Log.w(TAG, "WebDAV restore hit a network or IO error, retrying", e)
                Result.retry()
            } else {
                Log.e(TAG, "WebDAV restore kept failing; giving up after ${runAttemptCount + 1} attempts", e)
                failWithToast()
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV restore failed", e)
            failWithToast()
        } finally {
            tempEncryptedFile.delete()
        }
    }

    private fun normalizeEtag(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }

    /**
     * W6: reads the freshness manifest from the downloaded archive (a separate probe pass over
     * the file - the extraction pass must keep working unchanged for legacy archives, whose
     * first entry is the database itself).
     *
     * @return the manifest, or null for a legacy archive without one ("backup age unknown") or
     *   when the probe itself fails. A missing manifest must never block a restore: the
     *   extraction pass re-reads the file and performs the real authentication.
     */
    private fun readArchiveManifest(archive: File, passphrase: String): BackupArchiveCrypto.ArchiveManifest? =
        try {
            archive.inputStream().use { encrypted ->
                BackupArchiveCrypto.decryptStream(encrypted, passphrase).use { plain ->
                    java.util.zip.ZipInputStream(plain).use { zip ->
                        BackupArchiveCrypto.readManifest(zip)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the archive manifest", e)
            null
        }

    /**
     * Unpacks the notes database and its attachments from the downloaded archive and swaps the
     * database in.
     *
     * The archive is decrypted as a stream straight into the extractor: the old version read the
     * whole encrypted file, decrypted it into a second ByteArray and wrote a third copy to disk,
     * which is three full copies of every recording alive at once. Entry names come straight from
     * the archive and used to be handed to `File(tempDir, name)` unchecked, so a crafted entry could
     * write outside the cache directory. Names are now resolved against the canonical staging
     * directory and refused if they escape it, and the replacement goes through
     * [DatabaseRestore.replaceDatabaseAtomically] so an interrupted restore leaves the existing
     * database intact.
     *
     * Order matters and is deliberate (W13/D8/DAT-4): extraction, `quick_check`, the D8 schema gate,
     * the attachment *path rewrite* and the close-of-Room all run before the first file in
     * `filesDir` is touched. Only after the database swap succeeds are the bytes installed, and a
     * failed install rolls the database back from `.bak`. The whole section runs inside the shared
     * restore [Mutex] (W15) so it cannot interleave with a local import or export. After that, D7
     * reconciles rows against files.
     */
    private suspend fun unzipAndReplaceDatabase(encryptedArchive: File, passphrase: String) {
        val dbFile = applicationContext.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)
        // REG-04: staging is per operation. The old fixed directory was shared with the local
        // import/export path, and this worker deleted it *before* taking the restore mutex (and
        // again in its finally, after releasing it), so a superseded or cancelled restore could
        // delete the bytes of a newer operation. The unique child makes the directory private to
        // this run; creation and removal still happen only inside the mutex below.
        val tempDir = AttachmentArchive.newStagingDirectory(applicationContext)

        // W15: the exact @Singleton Mutex the local import/export paths hold (Hilt gives this
        // entry point the same instance DataModule provides to ImportExportRepository).
        // Download and decryption stay outside the lock; everything that touches the database
        // file or the attachment directories runs inside it, so a cloud restore can no longer
        // close() and rename() over a running local import - or vice versa.
        val restoreMutex = EntryPointAccessors
            .fromApplication(applicationContext, RestoreLockEntryPoint::class.java)
            .restoreMutex()

        restoreMutex.withLock {
            tempDir.deleteRecursively()
            tempDir.mkdirs()
            try {
                val stagedDatabase = encryptedArchive.inputStream().use { encryptedInput ->
                    val decrypted = BackupArchiveCrypto.decryptStream(encryptedInput, passphrase)
                    ZipInputStream(decrypted).use { zipStream ->
                        DatabaseRestore.extractAll(zipStream, tempDir, dbFile.name)
                    }
                }

                // A truncated or corrupt download can still carry a valid 100-byte header;
                // quick_check walks the real pages so a database that would open but lose rows is
                // rejected here, while the live file is still untouched.
                if (!DatabaseRestore.quickCheckPasses(stagedDatabase)) {
                    throw IOException("downloaded backup database failed integrity check")
                }
                // D8: refuse a newer or unrecognised schema before a single file is touched.
                DatabaseRestore.assertArchiveCompatible(stagedDatabase)

                // DAT-4: prepare rewrites the staged database's attachment paths and proves the
                // plan is complete, but touches nothing in filesDir. The old order moved files
                // before the swap, so a failed replacement left the still-live database
                // pointing at bytes the archive had already overwritten.
                val preparedAttachments =
                    AttachmentArchive.prepare(applicationContext, tempDir, stagedDatabase)

                // PERF-2: capture the live salt before Room closes. A failed attachment install
                // rolls the database back, and the salt has to follow it or the old `v3:` rows
                // lose the KEK they were sealed with.
                val oldSalt = SharedPreferencesVaultKeyStore(applicationContext).encodedSalt()
                // N-2: the deck is outside the database too; the archived deck may overwrite it
                // below, and the `.bak` snapshot needs the one that belonged to the old rows.
                val oldDeck = FlashcardArchive.readRaw(applicationContext)

                // DAT-7: the archive has been extracted, integrity-checked, schema-checked and its
                // attachment plan prepared. The next statement closes the process-wide Room
                // connection, so publish the restore now: the UI covers the app and the note
                // collectors stop before they can query a closed connection. Decryption and
                // validation deliberately ran before this point, so a rejected archive never blanks
                // the UI.
                var attachmentSnapshotId: String? = null
                var snapshotPaired = false
                // REG-04: this transition's token; a superseded worker's failure path must not
                // clear the overlay of the restore that replaced it.
                val restoreToken = RestoreCoordinator.begin()
                try {
                    // The connection has to be closed before its file is replaced. Room keeps the
                    // old file mapped, so a restore that only renamed the new file into place left
                    // the running app reading and writing the previous database - the notes looked
                    // restored until the next write went to the discarded file.
                    BackupDatabase.provider(applicationContext).close()
                    attachmentSnapshotId = AttachmentSnapshots.capture(applicationContext)
                        ?: throw IOException("could not snapshot attachments")
                    val newSalt = VaultSaltArchive.readStaged(tempDir)
                    if (newSalt != null && !VaultSaltArchive.isValidEncoded(newSalt)) {
                        throw IOException("downloaded backup vault salt is invalid")
                    }
                    // DAT-04: a deck member that exists but cannot be read/re-sealed is not "no
                    // deck to apply". Abort before the swap instead of writing a journal that
                    // would later leave the restored database with the old deck (or clear it).
                    val stagedDeck = File(tempDir, FlashcardArchive.ENTRY_NAME)
                    val newDeck = FlashcardArchive.readStagedPlainJson(tempDir)
                        ?.let(FlashcardArchive::sealForRecovery)
                    if (stagedDeck.isFile && newDeck == null) {
                        throw IOException("downloaded backup flashcard deck could not be prepared")
                    }
                    if (!RestoreJournal.write(
                            applicationContext,
                            stagedDatabase,
                            newSalt,
                            newDeck,
                            attachmentStaging = tempDir.absolutePath,
                            // R5-01: stamp before the hash; AttachmentArchive.install reuses it.
                            stampGeneration = true,
                        )
                    ) {
                        throw IOException("could not write the restore journal")
                    }
                    val previousBackup = DatabaseRestore.replaceDatabaseAtomically(
                        dbFile,
                        stagedDatabase
                    ) { backup ->
                        if (!SnapshotSidecar.write(
                                backup,
                                oldSalt,
                                oldDeck,
                                attachmentSnapshotId
                            )
                        ) {
                            throw IOException("could not record snapshot metadata")
                        }
                        snapshotPaired = true
                    }
                    if (previousBackup == null) {
                        AttachmentSnapshots.delete(applicationContext, attachmentSnapshotId)
                        attachmentSnapshotId = null
                    } else {
                        AttachmentSnapshots.pruneExcept(applicationContext, attachmentSnapshotId)
                    }

                    // The archive's salt must be live before any failure-prone attachment work. A
                    // present-but-invalid member is refused like a corrupt database instead of
                    // leaving every `v3:` row unreadable on this install.
                    val saltAccepted =
                        VaultSaltArchive.restoreFromStaging(applicationContext, tempDir)
                    if (File(tempDir, VaultSaltArchive.ENTRY_NAME).isFile && !saltAccepted) {
                        val rolledBack = DatabaseRestore.rollbackFromBackup(dbFile)
                        if (rolledBack) VaultSaltArchive.restoreEncoded(applicationContext, oldSalt)
                        throw IOException("downloaded backup vault salt is invalid")
                    }

                    // Now that the restored database is live, the bytes may follow it. install()
                    // moves same-name files aside first and puts them back itself if it fails.
                    try {
                        AttachmentArchive.install(
                            applicationContext,
                            preparedAttachments,
                        )
                    } catch (e: IOException) {
                        // The file on disk and the attachment bytes must agree again.
                        // rollbackFromBackup keeps `.bak` intact, so a retry or a manual "restore
                        // previous" still works.
                        val rolledBack = DatabaseRestore.rollbackFromBackup(dbFile)
                        // The salt belongs to the database that is live again; keeping the
                        // archive's would make the rolled-back rows unreadable even though their
                        // file was restored.
                        if (rolledBack) VaultSaltArchive.restoreEncoded(applicationContext, oldSalt)
                        throw IOException(
                            if (rolledBack) {
                                "attachments could not be installed; previous database restored"
                            } else {
                                "attachments could not be installed and the previous database could not be restored"
                            },
                            e
                        )
                    }

                    // SEC-5: the restored database may carry plaintext rows again; clear the
                    // install-wide marker so the next launch re-runs the sealing sweep.
                    LegacyPlaintextBackfillFlag.reset(applicationContext)
                    // SEC-4: captions are plaintext inside the archive, so the caption sweep needs
                    // the same reset; a flag from the replaced database would leave restored
                    // captions readable on disk.
                    LegacyCaptionBackfillFlag.reset(applicationContext)
                    // DAT-11: the deck was extracted with the database but lives in
                    // SharedPreferences; it is reapplied after the swap so a rejected archive
                    // cannot have rewritten it. Archives written before DAT-11 have no member and
                    // keep the current deck.
                    FlashcardArchive.restoreFromStaging(applicationContext, tempDir)
                    // D7: the restored database is live now - reconcile its attachment rows
                    // against the files on disk and quarantine what no row references any more.
                    AttachmentArchive.reconcileAfterRestore(applicationContext, dbFile)
                    RestoreJournal.clear(applicationContext)
                } catch (e: Throwable) {
                    // Leave the journal for the startup byte check: rollback is a mismatch and is
                    // discarded, a failed rollback is a completed move and gets its new prefs.
                    // Only a candidate that never reached the `.bak` sidecar is discarded; a
                    // stamped one is the attachment pairing for the current `.bak`.
                    if (!snapshotPaired) {
                        AttachmentSnapshots.delete(applicationContext, attachmentSnapshotId)
                    }
                    // Every failure/rollback path reopens the app. Success leaves the coordinator
                    // set: this worker restarts the process a moment later, and a fresh process
                    // starts from the default (not restoring). REG-04: the token makes this a
                    // no-op when a newer restore already owns the overlay.
                    RestoreCoordinator.end(restoreToken)
                    throw e
                }
            } finally {
                // REG-04: only this operation's own directory, and only while its restore lock is
                // still held. Deleting after releasing the lock was how a cancelled worker could
                // erase a newer operation's staging tree. DAT-10: an incomplete rollback keeps the
                // install journal, so the sources it still needs must survive too.
                AttachmentArchive.deleteStagingUnlessReferenced(applicationContext, tempDir)
            }
        }
    }

    private companion object {
        const val TAG = "WebDAVRestoreWorker"
        const val BACKUP_FILENAME = "notes-backup-encrypted.zip"

        /** Grace period so WorkManager can persist the worker result before the process restarts. */
        const val RESTART_DELAY_MS = 2_000L
    }
}

/**
 * Optional worker input: download this URL instead of the configured archive.
 *
 * The conflict-copy restore uses it to pull the copy that was kept beside the canonical file.
 * Public because the cloud screen enqueues the work with it.
 */
const val DATA_RESTORE_URL = "restore_url"

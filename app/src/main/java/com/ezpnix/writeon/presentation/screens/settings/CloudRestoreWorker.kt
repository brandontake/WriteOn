package com.ezpnix.writeon.presentation.screens.settings

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.util.BackupArchiveCrypto
import com.ezpnix.writeon.core.util.DatabaseRestore
import com.ezpnix.writeon.data.local.database.NoteDatabaseProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.jakewharton.processphoenix.ProcessPhoenix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
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
     * True once this install has written the remote archive (or restored from it). It lets an upload
     * distinguish "the server has a file but provides no ETag, and we know we wrote it" from "the
     * server has a file we have never seen", which must not be overwritten.
     */
    const val REMOTE_WRITTEN = "webdav_remote_written"
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

class WebDAVRestoreWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val client = OkHttpClient()

    /** Main-looper handler used to post the post-restore restart after the result is recorded. */
    private val restartHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val tempEncryptedFile = File(applicationContext.cacheDir, BACKUP_FILENAME)
        try {
            val prefs = applicationContext.webDavDataStore.data.first()
            val configuredUrl = prefs[stringPreferencesKey(WebDavPrefs.URL)] ?: return@withContext Result.failure()
            // An explicit URL (the conflict-copy restore) overrides the configured one. It is a
            // different file from the canonical archive, so its validator must never be stored as
            // though it described the canonical one.
            val restoreUrl = inputData.getString(DATA_RESTORE_URL)?.takeIf { it.isNotBlank() } ?: configuredUrl
            val restoringOtherFile = restoreUrl != configuredUrl
            val username = prefs[stringPreferencesKey(WebDavPrefs.USERNAME)] ?: return@withContext Result.failure()
            val encryptedPassword = prefs[stringPreferencesKey(WebDavPrefs.PASSWORD)]
                ?: return@withContext Result.failure()
            val aead = WebDavPrefsCipher.aead(applicationContext)
            val password = WebDavPrefsCipher.decryptOrNull(aead, encryptedPassword)
                ?: return@withContext Result.failure()

            // The archive passphrase lives in the same encrypted store, so it survives a reboot the
            // same way the WebDAV password does.
            val encryptedPassphrase = prefs[stringPreferencesKey(WebDavPrefs.BACKUP_PASSPHRASE)]
                ?: return@withContext Result.failure()
            val userPassword = WebDavPrefsCipher.decryptOrNull(aead, encryptedPassphrase)
                ?: return@withContext Result.failure()

            val request = Request.Builder()
                .url(restoreUrl)
                .header("Authorization", Credentials.basic(username, password))
                .build()

            var downloadedEtag: String? = null
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.retry()
                downloadedEtag = normalizeEtag(response.header("ETag"))
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(tempEncryptedFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext Result.failure()
            }

            unzipAndReplaceDatabase(tempEncryptedFile, userPassword)
            applicationContext.webDavDataStore.edit { stored ->
                val etagKey = stringPreferencesKey(WebDavPrefs.ETAG)
                if (restoringOtherFile) {
                    // This was a conflict copy, not the canonical archive: its validator says
                    // nothing about the file the next upload will target. Clear both markers so the
                    // upload path treats the canonical file as "someone else's" and makes a fresh
                    // conflict copy instead of overwriting it.
                    stored.remove(etagKey)
                    stored[booleanPreferencesKey(WebDavPrefs.REMOTE_WRITTEN)] = false
                } else {
                    // The remote archive is now the database we just installed. Remember the
                    // validator that belongs to it (instead of deleting it) so the next upload does
                    // not treat the file as a stranger and cannot silently overwrite another
                    // device's copy.
                    if (downloadedEtag.isNullOrBlank()) stored.remove(etagKey) else stored[etagKey] = downloadedEtag!!
                    stored[booleanPreferencesKey(WebDavPrefs.REMOTE_WRITTEN)] = true
                }
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
        } catch (e: IOException) {
            // A wrong passphrase surfaces as an IOException wrapping the crypto failure (the
            // streaming cipher stream rethrows it); retrying would spin on every run, so it is
            // terminal. A real network/IO problem still deserves a retry.
            val cause = e.cause
            if (cause is javax.crypto.BadPaddingException || cause is javax.crypto.IllegalBlockSizeException) {
                Log.e(TAG, "WebDAV restore archive failed authentication", e)
                Result.failure()
            } else {
                Log.w(TAG, "WebDAV restore hit a network or IO error, retrying", e)
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV restore failed", e)
            Result.failure()
        } finally {
            tempEncryptedFile.delete()
        }
    }

    private fun normalizeEtag(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }

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
     */
    private fun unzipAndReplaceDatabase(encryptedArchive: File, passphrase: String) {
        val dbFile = applicationContext.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)
        val tempDir = File(applicationContext.cacheDir, "unzipped_restore")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        try {
            val stagedDatabase = encryptedArchive.inputStream().use { encryptedInput ->
                val decrypted = BackupArchiveCrypto.decryptStream(encryptedInput, passphrase)
                ZipInputStream(decrypted).use { zipStream ->
                    DatabaseRestore.extractAll(zipStream, tempDir, dbFile.name)
                }
            }
            // Attachments are written next to the database but only become visible after their paths
            // are rewritten inside the staged copy.
            AttachmentArchive.restore(applicationContext, tempDir, stagedDatabase)

            // A truncated or corrupt download can still carry a valid 100-byte header; quick_check
            // walks the real pages so a database that would open but lose rows is rejected here,
            // while the live file is still untouched.
            if (!DatabaseRestore.quickCheckPasses(stagedDatabase)) {
                throw IOException("downloaded backup database failed integrity check")
            }
            // The connection has to be closed before its file is replaced. Room keeps the old file
            // mapped, so a restore that only renamed the new file into place left the running app
            // reading and writing the previous database - the notes looked restored until the next
            // write went to the discarded file.
            NoteDatabaseProvider(applicationContext as Application).close()
            DatabaseRestore.replaceDatabaseAtomically(dbFile, stagedDatabase)
        } finally {
            tempDir.deleteRecursively()
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

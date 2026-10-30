package com.ezpnix.writeon.presentation.screens.settings

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.util.BackupArchiveCrypto
import com.ezpnix.writeon.data.local.database.NoteDatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Uploads the notes database to the configured WebDAV server.
 *
 * The worker used to expect a `notes-backup-encrypted.zip` that was already sitting in the cache
 * directory. Nothing in the app ever wrote that file, so the worker returned failure on its first
 * check and cloud backup had never actually uploaded anything. The archive is now built here on
 * demand, from the live database, using the same layout the local encrypted export produces - so a
 * file downloaded from the server can be restored through either path.
 *
 * Credentials come from the same DataStore the settings screen writes to (see [WebDavPrefs]).
 * Before that fix the worker read a plain SharedPreferences file with the same name while the
 * settings screen wrote a DataStore, so the URL always came back empty.
 */
class WebDAVBackupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val client = OkHttpClient()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val archive = File(applicationContext.cacheDir, BACKUP_FILENAME)
        try {
            val prefs = applicationContext.webDavDataStore.data.first()
            val webdavUrl = prefs[stringPreferencesKey(WebDavPrefs.URL)]
            val username = prefs[stringPreferencesKey(WebDavPrefs.USERNAME)]
            val encryptedPassword = prefs[stringPreferencesKey(WebDavPrefs.PASSWORD)]
            val encryptedPassphrase = prefs[stringPreferencesKey(WebDavPrefs.BACKUP_PASSPHRASE)]

            if (webdavUrl.isNullOrBlank() || username.isNullOrBlank() ||
                encryptedPassword.isNullOrBlank() || encryptedPassphrase.isNullOrBlank()
            ) {
                // Nothing to upload until the WebDAV settings and the archive passphrase are filled
                // in. Retrying would only spin, so this is terminal.
                return@withContext Result.failure()
            }

            val aead = WebDavPrefsCipher.aead(applicationContext)
            val password = WebDavPrefsCipher.decryptOrNull(aead, encryptedPassword)
                ?: return@withContext Result.failure()
            val passphrase = WebDavPrefsCipher.decryptOrNull(aead, encryptedPassphrase)
                ?: return@withContext Result.failure()

            writeEncryptedArchive(archive, passphrase)

            val authorization = Credentials.basic(username, password)
            val remote = fetchRemote(webdavUrl, authorization)
            val knownEtag = prefs[stringPreferencesKey(WebDavPrefs.ETAG)]
            val remoteWritten = prefs[booleanPreferencesKey(WebDavPrefs.REMOTE_WRITTEN)] ?: false

            // "Take over the remote" (the conflict card's escape hatch). Without it a device that
            // restored a conflict copy is stuck forever: it cleared its validator, so every later
            // upload sees a stranger's file and keeps making another conflict copy. The user is
            // explicitly choosing this device's notes as the truth, so skip the conflict branch but
            // still send If-Match with whatever is on the server *now* - a change made between our
            // HEAD and our PUT must not be clobbered either.
            val forceOverwrite = inputData.getBoolean(DATA_FORCE_OVERWRITE, false)

            // Never let a fresh install (or a post-restore device with no remembered validator)
            // replace a backup it did not write. An ETag we have never seen, or a file that exists
            // while we have no ETag at all, means "someone else owns this file": keep both copies.
            val remoteChanged = remote.etag != null && knownEtag != null && remote.etag != knownEtag
            val unknownRemote = remote.exists == true && knownEtag == null && !remoteWritten
            if ((remoteChanged || unknownRemote) && !forceOverwrite) {
                keepConflictCopy(webdavUrl, authorization, archive)
                return@withContext Result.failure()
            }

            // Under force, the precondition is the *current* remote validator (create if absent);
            // otherwise it is our remembered one, or a create-only probe when we have none.
            val ifNoneMatch = !forceOverwrite && knownEtag == null && !remoteWritten
            val upload = putArchive(
                url = webdavUrl,
                authorization = authorization,
                archive = archive,
                ifMatch = when {
                    forceOverwrite -> remote.etag
                    ifNoneMatch -> null
                    else -> knownEtag
                },
                ifNoneMatch = ifNoneMatch
            )
            when {
                upload.code in 200..299 -> {
                    // A PUT response often omits ETag. Re-HEAD instead of storing the value read
                    // before the upload: storing a stale ETag made every later run look like a
                    // conflict, so the remote file never advanced again.
                    val stored = upload.etag ?: fetchRemote(webdavUrl, authorization).etag
                    storeEtag(stored)
                    storeRemoteWritten(true)
                    Result.success()
                }
                upload.code == PRECONDITION_FAILED -> {
                    // Another device won the race between our HEAD and our PUT.
                    keepConflictCopy(webdavUrl, authorization, archive)
                    Result.failure()
                }
                upload.code == 0 || upload.code >= 500 -> Result.retry()
                else -> {
                    Log.e(TAG, "WebDAV backup was rejected with HTTP ${upload.code}")
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV backup failed", e)
            Result.failure()
        } finally {
            // The archive holds the whole note database; do not leave it sitting in the cache.
            archive.delete()
        }
    }

    private data class RemoteState(val exists: Boolean?, val etag: String?)

    /**
     * One HEAD tells us whether the file on the server is still the one we uploaded last time and
     * whether it exists at all. `exists = null` means the server would not tell us.
     */
    private fun fetchRemote(url: String, authorization: String): RemoteState = try {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("Authorization", authorization)
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> RemoteState(exists = false, etag = null)
                response.code == 405 || response.code == 501 -> RemoteState(exists = null, etag = null)
                response.code in 200..299 -> RemoteState(
                    exists = true,
                    etag = normalizeEtag(response.header("ETag"))
                )
                else -> RemoteState(exists = null, etag = null)
            }
        }
    } catch (e: IOException) {
        // An unreachable server fails the PUT too; "unknown" simply means "no precondition".
        Log.w(TAG, "Could not read the remote ETag", e)
        RemoteState(exists = null, etag = null)
    }

    private fun putArchive(
        url: String,
        authorization: String,
        archive: File,
        ifMatch: String?,
        ifNoneMatch: Boolean
    ): UploadResult {
        val builder = Request.Builder()
            .url(url)
            .put(archive.asRequestBody())
            .header("Authorization", authorization)
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch)
        }
        if (ifNoneMatch) {
            builder.header("If-None-Match", "*")
        }
        return try {
            client.newCall(builder.build()).execute().use { response ->
                UploadResult(
                    code = response.code,
                    etag = if (response.isSuccessful) normalizeEtag(response.header("ETag")) else null
                )
            }
        } catch (e: IOException) {
            Log.w(TAG, "WebDAV upload failed", e)
            UploadResult(code = 0, etag = null)
        }
    }

    /**
     * The remote changed, so this device's archive must not replace it. It goes next to the
     * canonical file under a date-stamped name instead, which leaves both copies *visible* in the
     * user's WebDAV folder: a silent overwrite cannot be undone, a file they can see can.
     */
    private fun keepConflictCopy(url: String, authorization: String, archive: File) {
        val target = conflictUrl(url)
        val upload = putArchive(target, authorization, archive, ifMatch = null, ifNoneMatch = false)
        if (upload.code in 200..299) {
            Log.w(TAG, "Remote backup had changed; this device's copy was kept at $target")
            WebDavBackupState.recordConflict(applicationContext, target)
        } else {
            Log.e(TAG, "Remote backup had changed and the conflict copy failed with HTTP " + upload.code)
        }
    }

    private suspend fun storeEtag(etag: String?) {
        applicationContext.webDavDataStore.edit { prefs ->
            val key = stringPreferencesKey(WebDavPrefs.ETAG)
            if (etag.isNullOrBlank()) prefs.remove(key) else prefs[key] = etag
        }
    }

    private suspend fun storeRemoteWritten(written: Boolean) {
        applicationContext.webDavDataStore.edit { prefs ->
            prefs[booleanPreferencesKey(WebDavPrefs.REMOTE_WRITTEN)] = written
        }
    }

    /** Weak validators are not valid in If-Match, so they are treated as "no validator". */
    private fun normalizeEtag(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }

    /**
     * `notes-backup-encrypted.zip` becomes `notes-backup-encrypted.conflict-20261020-231500-123.zip`
     * so the copy sits next to the original rather than replacing it. Milliseconds are included
     * because two devices in the same second used to produce the same name.
     */
    private fun conflictUrl(url: String): String {
        val queryIndex = url.indexOf('?')
        val base = if (queryIndex >= 0) url.substring(0, queryIndex) else url
        val query = if (queryIndex >= 0) url.substring(queryIndex) else ""
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val dot = base.lastIndexOf('.')
        val slash = base.lastIndexOf('/')
        return if (dot > slash) {
            base.substring(0, dot) + ".conflict-" + stamp + base.substring(dot) + query
        } else {
            base + ".conflict-" + stamp + query
        }
    }

    private data class UploadResult(val code: Int, val etag: String?)

    private suspend fun writeEncryptedArchive(target: File, passphrase: String) {
        val portable = File.createTempFile("webdav-portable", ".db", applicationContext.cacheDir)
        try {
            val provider = NoteDatabaseProvider(applicationContext as Application)
            provider.writePortableDatabaseCopy(portable)
            val attachments = provider.attachmentDao().getAll()

            val zipFile = File(target.absolutePath + ZIP_TEMP_SUFFIX)
            try {
                ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                    zip.putNextEntry(ZipEntry(DatabaseConst.NOTES_DATABASE_FILE_NAME))
                    FileInputStream(portable).use { db -> db.copyTo(zip) }
                    zip.closeEntry()
                    AttachmentArchive.writeTo(zip, attachments)
                }

                // Stream the archive through the cipher into the target file. The old
                // readBytes() -> encrypt -> write held the whole archive (database + every
                // recording) as up to three byte arrays at once.
                FileOutputStream(target).use { output ->
                    FileInputStream(zipFile).use { zipInput ->
                        BackupArchiveCrypto.encryptStream(zipInput, output, passphrase)
                    }
                }
            } finally {
                zipFile.delete()
            }
        } finally {
            portable.delete()
        }
    }

    private companion object {
        const val TAG = "WebDAVBackupWorker"
        const val BACKUP_FILENAME = "notes-backup-encrypted.zip"
        const val ZIP_TEMP_SUFFIX = ".ziptmp"

        /** WebDAV: the precondition failed, i.e. the remote file changed under us. */
        const val PRECONDITION_FAILED = 412
    }
}

/**
 * Worker input: overwrite the canonical remote file regardless of the stored validator.
 *
 * The conflict card's "take over the remote" action sets this. Public because the cloud screen
 * enqueues the work with it.
 */
const val DATA_FORCE_OVERWRITE = "force_overwrite"

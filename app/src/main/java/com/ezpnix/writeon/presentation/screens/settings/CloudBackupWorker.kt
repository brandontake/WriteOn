package com.ezpnix.writeon.presentation.screens.settings

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.data.local.database.NoteDatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Uploads the notes database to the configured WebDAV server.
 *
 * The worker used to expect a `notes-backup-encrypted.zip` that was already sitting in the cache
 * directory. Nothing in the app ever wrote that file, so the worker returned failure on its first
 * check and cloud backup had never actually uploaded anything. The archive is now built here on
 * demand, from the live database, using the same layout the local encrypted export produces — so a
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
            // One HEAD tells us whether the file on the server is still the one we uploaded last
            // time. Without it every PUT replaced whatever another device had stored, quietly
            // destroying the newer backup - the "last write wins" that made cloud sync unsafe.
            val remoteEtag = fetchRemoteEtag(webdavUrl, authorization)
            val knownEtag = prefs[stringPreferencesKey(WebDavPrefs.ETAG)]
            if (remoteEtag != null && knownEtag != null && remoteEtag != knownEtag) {
                keepConflictCopy(webdavUrl, authorization, archive)
                return@withContext Result.failure()
            }

            val upload = putArchive(webdavUrl, authorization, archive, remoteEtag)
            if (upload.code in 200..299) {
                // Remember what the server reported, so the next run can tell whether someone else
                // wrote the file in the meantime.
                storeEtag(upload.etag ?: remoteEtag)
                Result.success()
            } else if (upload.code == PRECONDITION_FAILED) {
                // Another device won the race between our HEAD and our PUT.
                keepConflictCopy(webdavUrl, authorization, archive)
                Result.failure()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV backup failed", e)
            Result.failure()
        } finally {
            // The archive holds the whole note database; do not leave it sitting in the cache.
            archive.delete()
        }
    }

    /**
     * Zips the current notes database and encrypts it with [passphrase].
     *
     * The output layout matches the local encrypted export: a PBKDF2-derived key, then
     * `salt || iv || AES-CBC ciphertext`, with `salt` and `iv` each 16 random bytes.
     */
    /** Reads the remote ETag, or null when the server has none or refuses HEAD (405/501). */
    private fun fetchRemoteEtag(url: String, authorization: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("Authorization", authorization)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 405 || response.code == 501) null
            else normalizeEtag(response.header("ETag"))
        }
    } catch (e: IOException) {
        // An unreachable server fails the PUT too; "unknown" simply means "no precondition".
        Log.w(TAG, "Could not read the remote ETag", e)
        null
    }

    private fun putArchive(
        url: String,
        authorization: String,
        archive: File,
        ifMatch: String?
    ): UploadResult {
        val builder = Request.Builder()
            .url(url)
            .put(archive.asRequestBody())
            .header("Authorization", authorization)
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch)
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
        val upload = putArchive(target, authorization, archive, null)
        if (upload.code in 200..299) {
            Log.w(TAG, "Remote backup had changed; this device's copy was kept at " + target)
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

    /** Weak validators are not valid in If-Match, so the `W/` prefix is dropped. */
    private fun normalizeEtag(raw: String?): String? =
        raw?.trim()?.removePrefix("W/")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * `notes-backup-encrypted.zip` becomes `notes-backup-encrypted.conflict-20261020-231500.zip`
     * so the copy sits next to the original rather than replacing it.
     */
    private fun conflictUrl(url: String): String {
        val queryIndex = url.indexOf('?')
        val base = if (queryIndex >= 0) url.substring(0, queryIndex) else url
        val query = if (queryIndex >= 0) url.substring(queryIndex) else ""
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
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
            NoteDatabaseProvider(applicationContext as Application)
                .writePortableDatabaseCopy(portable)

            val zipFile = File(target.absolutePath + ZIP_TEMP_SUFFIX)
            val zipBytes = try {
                ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                    zip.putNextEntry(ZipEntry(DatabaseConst.NOTES_DATABASE_FILE_NAME))
                    zip.write(portable.readBytes())
                    zip.closeEntry()
                }
                zipFile.readBytes()
            } finally {
                zipFile.delete()
            }

            val salt = ByteArray(SALT_BYTES).apply { SecureRandom().nextBytes(this) }
            val iv = ByteArray(IV_BYTES).apply { SecureRandom().nextBytes(this) }

            val secretKey = SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS))
                    .encoded,
                "AES"
            )

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))

            FileOutputStream(target).use { output ->
                output.write(salt)
                output.write(iv)
                output.write(cipher.doFinal(zipBytes))
            }
        } finally {
            portable.delete()
        }
    }

    private companion object {
        const val TAG = "WebDAVBackupWorker"
        const val BACKUP_FILENAME = "notes-backup-encrypted.zip"
        const val ZIP_TEMP_SUFFIX = ".ziptmp"
        const val SALT_BYTES = 16
        const val IV_BYTES = 16
        const val KEY_SIZE_BITS = 256

        /** Kept in step with ImportExportRepository so both paths read each other's archives. */
        const val PBKDF2_ITERATIONS = 65_536

        /** WebDAV: the precondition failed, i.e. the remote file changed under us. */
        const val PRECONDITION_FAILED = 412
    }
}
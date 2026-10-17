package com.ezpnix.writeon.presentation.screens.settings

import android.app.Application
import android.content.Context
import android.util.Log
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

            val request = Request.Builder()
                .url(webdavUrl)
                .put(archive.asRequestBody())
                .header("Authorization", Credentials.basic(username, password))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) Result.success() else Result.retry()
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
    }
}
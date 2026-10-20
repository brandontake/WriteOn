package com.ezpnix.writeon.presentation.screens.settings

import android.app.Application
import androidx.datastore.preferences.core.edit
import com.ezpnix.writeon.data.local.database.NoteDatabaseProvider
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.util.DatabaseRestore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.jakewharton.processphoenix.ProcessPhoenix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.spec.KeySpec
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val tempEncryptedFile = File(applicationContext.cacheDir, BACKUP_FILENAME)
        try {
            val prefs = applicationContext.webDavDataStore.data.first()
            val webdavUrl = prefs[stringPreferencesKey(WebDavPrefs.URL)] ?: return@withContext Result.failure()
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
                .url(webdavUrl)
                .header("Authorization", Credentials.basic(username, password))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Result.retry()

            response.body?.byteStream()?.use { input ->
                FileOutputStream(tempEncryptedFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure()

            val encryptedBytes = tempEncryptedFile.readBytes()
            val decryptedBytes = decrypt(encryptedBytes, userPassword) ?: return@withContext Result.failure()

            unzipAndReplaceDatabase(decryptedBytes)
            // The remote archive is now the database we just installed, so the ETag remembered for
            // the previous one would read as "someone else changed the file" on the next upload.
            applicationContext.webDavDataStore.edit { prefs ->
                prefs.remove(stringPreferencesKey(WebDavPrefs.ETAG))
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    applicationContext,
                    applicationContext.getString(R.string.cloud_webdav_restore_completed),
                    Toast.LENGTH_LONG
                ).show()
                delay(1000)
                ProcessPhoenix.triggerRebirth(applicationContext)
            }

            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "WebDAV restore hit a network or IO error, retrying", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV restore failed", e)
            Result.failure()
        } finally {
            tempEncryptedFile.delete()
        }
    }

    private fun decrypt(encrypted: ByteArray, password: String): ByteArray? {
        return try {
            val salt = encrypted.copyOfRange(0, SALT_BYTES)
            val iv = encrypted.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
            val data = encrypted.copyOfRange(SALT_BYTES + IV_BYTES, encrypted.size)

            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
            val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            cipher.doFinal(data)
        } catch (e: Exception) {
            // A wrong passphrase and a corrupt archive are indistinguishable here — CBC only
            // detects a bad key when the padding check fails. Log the type, not the password.
            Log.w(TAG, "Could not decrypt the downloaded archive (wrong passphrase or corrupt file)", e)
            null
        }
    }

    /**
     * Unpacks the notes database from [zipData] and swaps it in.
     *
     * Entry names come straight from the archive and used to be handed to `File(tempDir, name)`
     * unchecked, so a crafted entry could write outside the cache directory. Names are now resolved
     * against the canonical staging directory and refused if they escape it, and the replacement
     * goes through [DatabaseRestore.replaceDatabaseAtomically] so an interrupted restore leaves the
     * existing database intact.
     */
    private fun unzipAndReplaceDatabase(zipData: ByteArray) {
        // The connection has to be closed before its file is replaced. Room keeps the old file
        // mapped, so a restore that only renamed the new file into place left the running app
        // reading and writing the previous database - the notes looked restored until the next
        // write went to the discarded file.
        NoteDatabaseProvider(applicationContext as Application).close()
        val dbFile = applicationContext.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)
        val tempDir = File(applicationContext.cacheDir, "unzipped_restore")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val tempZipFile = File(applicationContext.cacheDir, "decrypted_backup.zip")
        try {
            tempZipFile.writeBytes(zipData)

            var restored: File? = null
            ZipInputStream(tempZipFile.inputStream()).use { zipStream ->
                var entry = zipStream.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = DatabaseRestore.resolveEntry(tempDir, entry.name)
                        DatabaseRestore.extractEntry(zipStream, outFile)
                        if (entry.name == dbFile.name) restored = outFile
                    }
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
            }

            DatabaseRestore.replaceDatabaseAtomically(dbFile, requireNotNull(restored) {
                "notes database missing from the downloaded backup"
            })
        } finally {
            tempZipFile.delete()
            tempDir.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "WebDAVRestoreWorker"
        const val BACKUP_FILENAME = "notes-backup-encrypted.zip"
        const val SALT_BYTES = 16
        const val IV_BYTES = 16
        const val KEY_SIZE_BITS = 256
        const val PBKDF2_ITERATIONS = 65_536
    }
}
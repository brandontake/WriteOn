package com.ezpnix.writeon.data.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.database.getStringOrNull
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.util.DatabaseRestore
import com.ezpnix.writeon.data.local.database.NoteDatabaseProvider
import com.ezpnix.writeon.domain.model.Note
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.InvalidKeyException
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import com.ezpnix.writeon.R

sealed class BackupResult {
    data class Success(val message: String) : BackupResult()
    data class Error(val message: String) : BackupResult()
    object BadPassword : BackupResult()
}

/** Carries a non-null result out of the `withLock` block; null means "carry on and report success". */
private class ImportFailure(val result: BackupResult?)

class ImportExportRepository(
    private val provider: NoteDatabaseProvider,
    private val context: Context,
    private val mutex: Mutex,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    /**
     * Fresh salt per export rather than one for the process lifetime.
     *
     * A shared salt let anyone holding two of a user's backups see that the same password produced
     * both, and made the whole repository's exports correlate with each other.
     */
    private fun newSalt(): ByteArray = ByteArray(SALT_BYTES).apply { SecureRandom().nextBytes(this) }

    private fun generateSecretKey(password: String, salt: ByteArray): SecretKey {
        val factory: SecretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun encrypt(data: ByteArray, secretKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        val encryptedData = cipher.doFinal(data)
        return iv + encryptedData
    }

    private fun decrypt(data: ByteArray, secretKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = data.copyOfRange(0, IV_BYTES)
        val encryptedData = data.copyOfRange(IV_BYTES, data.size)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        return cipher.doFinal(encryptedData)
    }

    /**
     * Writes an export to [uri]. The old version returned success even when
     * `openOutputStream` handed back null, which told users their backup was saved while nothing
     * had been written anywhere; the null case is now surfaced as an error.
     */
    suspend fun exportBackup(uri: Uri, password: String?): BackupResult {
        return try {
            withContext(dispatcher + scope.coroutineContext) {
                mutex.withLock {
                    provider.close()

                    val outputStream = context.contentResolver.openOutputStream(uri)
                        ?: return@withLock ImportFailure(
                            BackupResult.Error(context.getString(R.string.export_failed, ""))
                        )

                    outputStream.use { stream ->
                        val portableFile = File.createTempFile("portable", ".db", context.cacheDir)
                        val tempZipFile = File.createTempFile("backup", ".zip", context.cacheDir)
                        try {
                            provider.writePortableDatabaseCopy(portableFile)

                            ZipOutputStream(FileOutputStream(tempZipFile)).use { zipOutputStream ->
                                FileInputStream(portableFile).use { inputStream ->
                                    zipOutputStream.putNextEntry(ZipEntry(DatabaseConst.NOTES_DATABASE_FILE_NAME))
                                    inputStream.copyTo(zipOutputStream)
                                    zipOutputStream.closeEntry()
                                }
                            }

                            val zipData = tempZipFile.readBytes()
                            if (password != null) {
                                val salt = newSalt()
                                val encryptedData = encrypt(zipData, generateSecretKey(password, salt))
                                stream.write(salt + encryptedData)
                            } else {
                                stream.write(zipData)
                            }
                            stream.flush()
                        } finally {
                            portableFile.delete()
                            tempZipFile.delete()
                        }
                    }
                    ImportFailure(null)
                }
            }.let { failure ->
                failure.result ?: BackupResult.Success(context.getString(R.string.export_successful))
            }
        } catch (e: Exception) {
            BackupResult.Error(context.getString(R.string.export_failed, e.message ?: ""))
        }
    }

    suspend fun importBackup(uri: Uri, password: String?): BackupResult {
        return try {
            withContext(dispatcher + scope.coroutineContext) {
                mutex.withLock {
                    provider.close()

                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: return@withLock ImportFailure(BackupResult.Error(context.getString(R.string.import_stream_error)))

                    val data: ByteArray = inputStream.use { stream ->
                        val inputData = stream.readBytes()
                        if (password != null) {
                            val salt = inputData.copyOfRange(0, 16)
                            val encryptedData = inputData.copyOfRange(16, inputData.size)
                            decrypt(encryptedData, generateSecretKey(password, salt))
                        } else {
                            inputData
                        }
                    }

                    // Unpack into the cache first and only touch the live database once the archive
                    // has yielded a file that actually looks like a notes database.
                    val tempDir = File(context.cacheDir, "restore-staging").apply { mkdirs() }
                    tempDir.deleteRecursively()
                    tempDir.mkdirs()

                    val restored = try {
                        extractDatabaseEntry(tempDir, data)
                    } finally {
                        tempDir.deleteRecursively()
                    }

                    val dbFile = context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)
                    DatabaseRestore.replaceDatabaseAtomically(dbFile, restored)
                }
                ImportFailure(null)
            }.let { failure ->
                failure.result ?: BackupResult.Success(context.getString(R.string.import_successful))
            }
        } catch (e: BadPaddingException) {
            // Padding only fails when the key is wrong or the ciphertext was tampered with.
            BackupResult.BadPassword
        } catch (e: IllegalBlockSizeException) {
            // The length is wrong before any padding check, so this is a damaged file, not a typo.
            BackupResult.Error(context.getString(R.string.import_failed, e.message ?: ""))
        } catch (e: InvalidKeyException) {
            BackupResult.BadPassword
        } catch (e: Exception) {
            BackupResult.Error(context.getString(R.string.import_failed, e.message ?: ""))
        }
    }

    /**
     * Pulls the notes database out of a backup archive into [stagingDir].
     *
     * Only the database entry is of interest — an archive that carries anything else is not a
     * restore this app produced, and silently ignoring it is safer than writing stray files.
     */
    private fun extractDatabaseEntry(stagingDir: File, zipData: ByteArray): File {
        val tempZipFile = File.createTempFile("backup", ".zip", context.cacheDir)
        try {
            tempZipFile.writeBytes(zipData)
            ZipInputStream(tempZipFile.inputStream()).use { zipInputStream ->
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == DatabaseConst.NOTES_DATABASE_FILE_NAME) {
                        val target = DatabaseRestore.resolveEntry(stagingDir, entry.name)
                        DatabaseRestore.extractEntry(zipInputStream, target)
                        return target
                    }
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
            }
            throw IOException(context.getString(R.string.import_failed, "notes database missing from backup"))
        } finally {
            tempZipFile.delete()
        }
    }

    fun importFile(uri: Uri): Note {
        val content = context.contentResolver.openInputStream(uri)?.use { fileInputStream ->
            fileInputStream.reader().buffered().readText()
        } ?: throw IOException(context.getString(R.string.import_stream_error))

        val name = context.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME), null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getStringOrNull(0) else ""
        } ?: ""

        return Note(
            name = name,
            description = content,
        )
    }

    private companion object {
        const val SALT_BYTES = 16
        const val IV_BYTES = 16
        const val KEY_SIZE_BITS = 256

        /**
         * Iterations for the backup passphrase. These backups only ever move between installs of
         * this app rather than defending a remote server, so this stays well below the count used
         * for the on-device screen lock; raising it would make every export noticeably slower on
         * low-end phones.
         */
        const val PBKDF2_ITERATIONS = 65_536
    }
}

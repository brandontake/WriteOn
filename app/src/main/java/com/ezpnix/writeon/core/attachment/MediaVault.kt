package com.ezpnix.writeon.core.attachment

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Android-facing surface of attachment encryption (SEC-4).
 *
 * Wraps [MediaFileCipher] around the Keystore key `writeon_media_v1` and owns the plaintext
 * materialisation cache. There is no global context and no `initialize` step: every entry point
 * takes the [Context] it needs, so this can be called from a worker, a repository or a preview
 * loader without a lifecycle.
 *
 * ## What is encrypted and when
 *
 * Media files are encrypted **lazily on write** ([encryptInPlace]) and on the next save of a file
 * that is still plaintext. Existing files stay valid plaintext until then - [MediaFileCipher]
 * identifies its own files by magic, so nothing needs a bulk migration that could lose a picture.
 * This is a roll-out mechanism, not a permanent promise: the wiring lane decides which write paths
 * call [encryptInPlace].
 *
 * ## The materialised cache
 *
 * Players, image loaders and share sheets need a real path. [materialize] decrypts an encrypted
 * file into `cacheDir/media/` and returns that path. The cache is **app-private**, but it is
 * plaintext and therefore:
 * - evictable by the OS at any time (never treat a returned path as durable across processes or
 *   reboots);
 * - reachable by anyone who can read the app's cache directory;
 * - **must be cleared when the app locks** ([clearCache]) and when an attachment is deleted
 *   ([deleteCached]).
 *
 * Cache names are `sha256(path)` prefix + `sha256(path,size,mtime)` + extension. The size and
 * modification time are part of the name, so a replaced file can never be served from the previous
 * decryption; the path prefix exists so [deleteCached] can still find every generation for a path
 * after the source file is already gone.
 *
 * ## Threading
 *
 * Every function does blocking IO, and [materialize] may decrypt a multi-megabyte recording.
 * Callers must use `Dispatchers.IO`; nothing here is main-thread safe or suspending.
 */
object MediaVault {

    private const val TAG = "MediaVault"

    /** Keystore alias. Deliberately separate from the note key so media can be rotated alone. */
    private const val KEY_ALIAS = "writeon_media_v1"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_SIZE_BITS = 256

    /** `cacheDir/media`, holding plaintext only; cleared on lock. */
    const val CACHE_DIRECTORY = "media"

    /** Suffix of the sibling file used before the atomic rename in [encryptInPlace]. */
    const val PART_SUFFIX = ".media-part"

    private const val CACHE_PART_SUFFIX = ".part"
    private const val COPY_BUFFER_BYTES = 64 * 1024

    /**
     * The Keystore key, cached after the first load. This is a reference to the entry, not the key
     * material: it never leaves the Keystore. Volatile because previews and backup workers call in
     * from different threads.
     */
    @Volatile
    private var cachedKey: SecretKey? = null

    private val keyLock = Any()

    /** Header sniff only; no key is loaded (and none can be created) on this path. */
    fun isEncrypted(@Suppress("UNUSED_PARAMETER") context: Context, file: File): Boolean =
        MediaFileCipher.hasEncryptedHeader(file)

    /**
     * Encrypts [file] in place through a `.media-part` sibling, fsync and atomic rename.
     *
     * A no-op when the file is already `WMV1`. A plaintext file is encrypted; if anything fails
     * (no key, no space, a hostile file system) the original is left exactly as it was and false is
     * returned. Only this write path may create the Keystore key when the alias is missing - read
     * paths deliberately fail instead of minting a key that cannot open existing media.
     *
     * @return true when the file is encrypted afterwards.
     */
    fun encryptInPlace(@Suppress("UNUSED_PARAMETER") context: Context, file: File): Boolean {
        if (!file.isFile) return false
        if (MediaFileCipher.hasEncryptedHeader(file)) return true

        val parent = file.absoluteFile.parentFile ?: return false
        val temp = File(parent, file.name + PART_SUFFIX)

        val cipher = try {
            mediaCipher(createIfMissing = true)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open the media key; leaving ${file.name} unencrypted", e)
            return false
        }

        val encrypted = cipher.encryptInPlace(file, temp)
        if (!encrypted) {
            Log.e(TAG, "Could not encrypt ${file.name}; original left untouched")
        }
        return encrypted
    }

    /**
     * Copies [source] into a backup archive, decrypting it when it is `WMV1`.
     *
     * Plaintext is written as-is on purpose: the archive must restore on another device, and the
     * media key cannot leave this one. The archive's own password encryption is what protects
     * these bytes; this method must never be used to produce an unencrypted export.
     *
     * [output] is not closed - a zip entry owns closing. A false result means the copy failed
     * part-way and the caller must abort the archive, not just skip the entry: bytes may already
     * have been written.
     */
    fun copyTo(@Suppress("UNUSED_PARAMETER") context: Context, source: File, output: OutputStream): Boolean =
        try {
            if (MediaFileCipher.hasEncryptedHeader(source)) {
                mediaCipher().decryptToStream(source, output)
            } else {
                FileInputStream(source).use { input ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not copy media ${source.name} into the archive", e)
            false
        }

    /**
     * Returns a path whose bytes are playable, decrypting into the cache when needed.
     *
     * A plaintext file is returned unchanged. An encrypted file is decrypted to the deterministic
     * cache path derived from its path, size and modification time; an existing cache file is
     * reused. The write goes to a unique `.part` file, is fsynced, then renamed into place, so a
     * crash or a second caller can never expose a partial decode. Two threads racing for the same
     * file both decode and the rename lets the last complete copy win - the content is identical.
     *
     * @return an absolute path, or null when the file is missing, cannot be decrypted (wrong or
     *   missing key) or the cache cannot be written. Null is never "use the encrypted file".
     */
    fun materialize(context: Context, path: String): String? {
        if (path.isBlank()) return null
        val source = File(path)
        if (!source.isFile) return null

        return try {
            if (MediaFileCipher.hasEncryptedHeader(source)) {
                materializeEncrypted(context, source)
            } else {
                source.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not materialize media ${source.name}", e)
            null
        }
    }

    /** Deletes the whole plaintext cache; call this whenever the app locks. */
    fun clearCache(context: Context) {
        runCatching { cacheDirectory(context).deleteRecursively() }
            .onFailure { Log.w(TAG, "Could not clear the media cache", it) }
    }

    /**
     * Best-effort removal of every cached plaintext generation for [path].
     *
     * Must be called when an attachment is deleted. It works even after the source file is gone
     * because the name starts with a hash of the path alone; the size/mtime hash only changes
     * which generation was current.
     */
    fun deleteCached(context: Context, path: String) {
        runCatching {
            val directory = cacheDirectory(context)
            if (!directory.isDirectory) return@runCatching
            // Same absolutePath the writer used, so a relative DB path cannot miss its cache entry.
            val marker = MediaCacheNames.pathPrefix(File(path).absolutePath) + MediaCacheNames.SEPARATOR
            directory.listFiles()?.forEach { cached ->
                if (cached.name.startsWith(marker)) cached.delete()
            }
        }.onFailure { Log.w(TAG, "Could not delete cached media for ${File(path).name}", it) }
    }

    /**
     * Decrypts [source] to [destination] (read path, never creates a key).
     *
     * [destination] is removed on failure by [MediaFileCipher.decrypt]; pass a fresh file, not an
     * existing user document.
     */
    fun decryptTo(@Suppress("UNUSED_PARAMETER") context: Context, source: File, destination: File): Boolean =
        try {
            mediaCipher().decrypt(source, destination)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not decrypt media ${source.name}", e)
            false
        }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    private fun materializeEncrypted(context: Context, source: File): String? {
        // Capture both before decrypting: they are both the cache key and the staleness guard.
        val size = source.length()
        val lastModified = source.lastModified()
        val directory = cacheDirectory(context)
        val target = File(
            directory,
            MediaCacheNames.fileName(source.absolutePath, size, lastModified, source.extension)
        )

        // Complete files only ever appear through the rename below, so existence is validity.
        if (target.isFile) return target.absolutePath
        if (!directory.isDirectory && !directory.mkdirs()) return null

        val part = File.createTempFile(target.name + ".", CACHE_PART_SUFFIX, directory)
        try {
            if (!decryptTo(context, source, part)) return null
            if (publish(part, target)) return target.absolutePath
            // A racing caller may have published the identical bytes first.
            return target.takeIf { it.isFile }?.absolutePath
        } finally {
            part.delete()
        }
    }

    /** Atomic rename of a finished, fsynced cache file; falls back to the provider's best effort. */
    private fun publish(part: File, target: File): Boolean = try {
        Files.move(
            part.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
        true
    } catch (e: AtomicMoveNotSupportedException) {
        runCatching {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
    } catch (e: Exception) {
        false
    }

    private fun cacheDirectory(context: Context): File = File(context.cacheDir, CACHE_DIRECTORY)

    private fun mediaCipher(createIfMissing: Boolean = false): MediaFileCipher =
        MediaFileCipher(secretKey(createIfMissing))

    /**
     * Loads the AES-256-GCM key, creating it only when [createIfMissing] is true.
     *
     * Mirrors `NoteCrypto`: a missing alias on a read is a hard failure ("this media belongs to a
     * key this install no longer has"), never a silent new key that would make every existing file
     * unreadable while looking healthy.
     */
    private fun secretKey(createIfMissing: Boolean = false): SecretKey {
        cachedKey?.let { return it }
        synchronized(keyLock) {
            cachedKey?.let { return it }

            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { entry ->
                cachedKey = entry.secretKey
                return entry.secretKey
            }

            check(createIfMissing) {
                "The media encryption key is missing; refusing to create a replacement"
            }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    // The format stores a fresh random IV per chunk, so the key must allow it.
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            return generator.generateKey().also { cachedKey = it }
        }
    }
}

/**
 * Pure cache-name derivation, split out so it can be tested on the JVM without Android.
 *
 * `pathPrefix` is a short hash of the path alone and is used only to find every cached generation
 * of one attachment. `key` is the full SHA-256 of path + size + modification time, so replacing a
 * file with the same name always changes the name of its plaintext copy.
 */
internal object MediaCacheNames {

    /** 128 bits of the path hash; collision risk is irrelevant for an app cache. */
    private const val PATH_PREFIX_CHARS = 32

    const val SEPARATOR = "-"

    private const val MAX_EXTENSION_CHARS = 8

    fun pathPrefix(path: String): String =
        hexDigest(path.toByteArray(Charsets.UTF_8)).take(PATH_PREFIX_CHARS)

    fun key(path: String, size: Long, lastModified: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(path.toByteArray(Charsets.UTF_8))
        // A separator byte keeps ("a|b") from hashing the same as ("a", "b", ...).
        digest.update(0.toByte())
        digest.update(ByteBuffer.allocate(16).putLong(size).putLong(lastModified).array())
        return hexDigest(digest.digest())
    }

    fun fileName(path: String, size: Long, lastModified: Long, extension: String): String =
        pathPrefix(path) + SEPARATOR + key(path, size, lastModified) + extensionSuffix(extension)

    /** Keeps the media type visible to players and loaders; drops anything exotic. */
    private fun extensionSuffix(extension: String): String {
        val clean = extension.lowercase().filter { it.isLetterOrDigit() }.take(MAX_EXTENSION_CHARS)
        return if (clean.isEmpty()) "" else ".$clean"
    }

    private fun hexDigest(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            out[i * 2] = digits[value ushr 4]
            out[i * 2 + 1] = digits[value and 0x0F]
        }
        return String(out)
    }
}

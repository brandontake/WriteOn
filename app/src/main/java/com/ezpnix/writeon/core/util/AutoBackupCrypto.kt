package com.ezpnix.writeon.core.util

import android.content.Context
import android.util.Log
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Device-bound encryption for the daily automatic backups.
 *
 * These archives are written by a scheduled worker with nobody there to type a passphrase, so the
 * key comes from the Keystore and never leaves the device: a copy taken off the phone (adb, a
 * forensics image) is unreadable. The flip side is that only this install on this device can
 * restore one - which is why the automatic backup is a self-heal mechanism against a corrupted or
 * accidentally emptied database, while moving to a new phone remains the job of the WebDAV and
 * local (SAF) backups, whose archives are portable by design.
 *
 * `WAB2` is the streaming format (Tink [StreamingAead], written as the file is produced so a
 * backup with gigabytes of recordings never needs a matching byte array in memory). `WAB1` was the
 * one-shot AEAD layout from an unreleased development build; it is still readable so nobody who ran
 * that build loses their archives.
 */
object AutoBackupCrypto {

    private const val TAG = "AutoBackupCrypto"
    private const val KEYSET_NAME = "auto_backup_keyset"
    private const val STORE_NAME = "webdav_prefs"
    private const val MASTER_KEY_URI = "android-keystore://writeon_auto_backup"
    private const val COPY_BUFFER_BYTES = 256 * 1024

    private val ASSOCIATED_DATA = "writeon-auto-backup".toByteArray(StandardCharsets.UTF_8)

    /** Streaming format magic. */
    val MAGIC_V2 = "WAB2".toByteArray(StandardCharsets.US_ASCII)

    /** One-shot AEAD format magic from the development builds; read-only. */
    val MAGIC_V1 = "WAB1".toByteArray(StandardCharsets.US_ASCII)

    init {
        com.google.crypto.tink.aead.AeadConfig.register()
        StreamingAeadConfig.register()
    }

    private fun streamingAead(context: Context): StreamingAead =
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, STORE_NAME)
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(StreamingAead::class.java)

    /**
     * Writes the `WAB2` header and then encrypts [input] into [output].
     *
     * Neither stream is closed; the caller owns both.
     */
    fun encryptStream(context: Context, input: InputStream, output: OutputStream) {
        output.write(MAGIC_V2)
        streamingAead(context)
            .newEncryptingStream(output, ASSOCIATED_DATA)
            .use { cipher -> input.copyTo(cipher, COPY_BUFFER_BYTES) }
    }

    /**
     * Decrypts an automatic backup into [output].
     *
     * @return true when every byte authenticated and reached [output]; false when the file was
     *   written by another device (the key does not transfer), is corrupt, or is not one of ours.
     */
    fun decryptTo(context: Context, headerAndBody: InputStream, output: OutputStream): Boolean = try {
        // The format is decided by the first four bytes; the legacy reader needs them back.
        val magic = ByteArray(MAGIC_V2.size)
        var read = 0
        while (read < magic.size) {
            val result = headerAndBody.read(magic, read, magic.size - read)
            if (result < 0) break
            read += result
        }
        val body = if (read == magic.size && magic.contentEquals(MAGIC_V2)) {
            headerAndBody
        } else if (read > 0) {
            PrefixedInputStream(magic.copyOf(read), headerAndBody)
        } else {
            return false
        }
        val decrypted = if (read == magic.size && magic.contentEquals(MAGIC_V2)) {
            streamingAead(context).newDecryptingStream(body, ASSOCIATED_DATA)
        } else {
            legacyAead(context).decrypt(body.readBytes(), ASSOCIATED_DATA).inputStream()
        }
        decrypted.use { plain -> plain.copyTo(output, COPY_BUFFER_BYTES) }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not decrypt an automatic backup", e)
        false
    }

    private fun legacyAead(context: Context): com.google.crypto.tink.Aead =
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, STORE_NAME)
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(com.google.crypto.tink.Aead::class.java)

    /** Replays already-consumed bytes in front of the rest of a stream. */
    private class PrefixedInputStream(
        private val prefix: ByteArray,
        private val rest: InputStream
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position < prefix.size) return prefix[position++].toInt() and 0xFF
            return rest.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (position < prefix.size) {
                val count = minOf(len, prefix.size - position)
                System.arraycopy(prefix, position, b, off, count)
                position += count
                return count
            }
            return rest.read(b, off, len)
        }

        override fun close() {
            rest.close()
        }
    }
}

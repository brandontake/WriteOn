package com.ezpnix.writeon.core.util

import android.content.Context
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
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

    /**
     * WAB1's keyset. Kept under its old name and old template: `AndroidKeysetManager` only applies
     * a template when it *creates* a keyset, so re-templating or renaming this would strand the
     * archives the development builds left behind.
     */
    private const val LEGACY_KEYSET_NAME = "auto_backup_keyset"

    /**
     * WAB2's keyset. Deliberately a different name, because [AutoBackupTemplates.streamingAead] is
     * a streaming template and must never be applied to the WAB1 AEAD keyset above.
     */
    private const val STREAMING_KEYSET_NAME = "auto_backup_streaming_keyset"

    private const val STORE_NAME = "webdav_prefs"
    private const val MASTER_KEY_URI = "android-keystore://writeon_auto_backup"
    private const val COPY_BUFFER_BYTES = 256 * 1024

    private val ASSOCIATED_DATA = "writeon-auto-backup".toByteArray(StandardCharsets.UTF_8)

    /** Streaming format magic. */
    val MAGIC_V2 = "WAB2".toByteArray(StandardCharsets.US_ASCII)

    /** One-shot AEAD format magic from the development builds; read-only. */
    val MAGIC_V1 = "WAB1".toByteArray(StandardCharsets.US_ASCII)

    init {
        AeadConfig.register()
        StreamingAeadConfig.register()
    }

    private fun streamingAead(context: Context): StreamingAead =
        AndroidKeysetManager.Builder()
            .withSharedPref(context, STREAMING_KEYSET_NAME, STORE_NAME)
            .withKeyTemplate(AutoBackupTemplates.streamingAead())
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(StreamingAead::class.java)

    /**
     * Writes the `WAB2` header and then encrypts [input] into [output].
     *
     * Neither stream is closed; the caller owns both. Tink's encrypting stream is a
     * `FilterOutputStream` whose `close()` closes whatever it wraps, so [output] is handed to it
     * through [nonClosingOutput]: that close becomes a flush. This is what lets a caller `fsync`
     * the file after the ciphertext is complete - a closed `FileOutputStream` throws
     * `SyncFailedException` on `fd.sync()`. [input] is only read, never closed.
     */
    fun encryptStream(context: Context, input: InputStream, output: OutputStream) =
        encryptStream(streamingAead(context), input, output)

    /**
     * The primitive-level body of [encryptStream], split out so the WAB2 stream contract (magic,
     * no close of the caller's streams) can be exercised on the JVM with a generated keyset.
     */
    internal fun encryptStream(aead: StreamingAead, input: InputStream, output: OutputStream) {
        output.write(MAGIC_V2)
        aead.newEncryptingStream(nonClosingOutput(output), ASSOCIATED_DATA)
            .use { cipher -> input.copyTo(cipher, COPY_BUFFER_BYTES) }
    }

    /**
     * Decrypts an automatic backup into [output].
     *
     * Neither stream is closed: the caller owns them, and the caller's input is wrapped so the
     * decrypting stream's `close()` cannot close it either.
     *
     * @return true when every byte authenticated and reached [output]; false when the file was
     *   written by another device (the key does not transfer), is corrupt, or is not one of ours.
     */
    fun decryptTo(context: Context, headerAndBody: InputStream, output: OutputStream): Boolean =
        decryptTo(
            streaming = { streamingAead(context) },
            legacy = { legacyAead(context) },
            headerAndBody = headerAndBody,
            output = output
        )

    /**
     * Primitive-level body of [decryptTo].
     *
     * The primitives are factories because an empty or foreign file is rejected before either
     * keyset is needed; eagerly building the WAB1 keyset would create a second Keystore-backed
     * keyset just to say no to a stray file.
     */
    internal fun decryptTo(
        streaming: () -> StreamingAead,
        legacy: () -> Aead,
        headerAndBody: InputStream,
        output: OutputStream,
    ): Boolean = try {
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
            streaming().newDecryptingStream(nonClosingInput(body), ASSOCIATED_DATA)
        } else {
            legacy().decrypt(body.readBytes(), ASSOCIATED_DATA).inputStream()
        }
        decrypted.use { plain -> plain.copyTo(output, COPY_BUFFER_BYTES) }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not decrypt an automatic backup", e)
        false
    }

    private fun legacyAead(context: Context): Aead =
        AndroidKeysetManager.Builder()
            .withSharedPref(context, LEGACY_KEYSET_NAME, STORE_NAME)
            .withKeyTemplate(AutoBackupTemplates.legacyAead())
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)

    /**
     * An [OutputStream] that forwards every byte and turns `close()` into `flush()`.
     *
     * Tink's streaming primitives extend `FilterOutputStream`, whose `close()` closes the stream it
     * was handed. Without this, [encryptStream] left the caller's file closed and a following
     * `fd.sync()` in `BackupWorker` threw `SyncFailedException` - after the write, so the whole
     * automatic backup failed every day. It also keeps the documented promise that the caller owns
     * the stream it passed in.
     */
    private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(b: Int) = delegate.write(b)

        override fun write(b: ByteArray) = delegate.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)

        override fun flush() = delegate.flush()

        /** The cipher's final chunk is pushed out here; closing the file is the caller's job. */
        override fun close() = flush()
    }

    /** The input twin of [NonClosingOutputStream]: reads delegate, `close()` does nothing. */
    private class NonClosingInputStream(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun skip(n: Long): Long = delegate.skip(n)

        override fun available(): Int = delegate.available()

        override fun close() = Unit
    }

    internal fun nonClosingOutput(output: OutputStream): OutputStream =
        NonClosingOutputStream(output)

    internal fun nonClosingInput(input: InputStream): InputStream =
        NonClosingInputStream(input)

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

/**
 * The two key layouts the automatic backup has used, kept apart from the Keystore plumbing so a
 * JVM test can prove which primitive each one yields.
 *
 * Tink derives the primitive from the keyset's key type, not from what the caller asks for. The
 * original WAB2 implementation created its keyset with [AeadKeyTemplates.AES256_GCM] and then
 * requested a `StreamingAead`; Tink 1.8.0 answers "at least one of the `fullPrimitive` or
 * `primitive` must be set", so every automatic backup failed before writing a byte. Do not
 * collapse these two into one template.
 */
internal object AutoBackupTemplates {

    /** WAB1: the one-shot AEAD template `auto_backup_keyset` was created with. Read-only now. */
    fun legacyAead() = AeadKeyTemplates.AES256_GCM

    /**
     * WAB2: HKDF-derived AES-GCM in 4 KiB chunks - a streaming key type, so Tink can hand back a
     * [StreamingAead]. Chunking keeps memory flat for archives of hundreds of megabytes of
     * recordings; the chunk size is part of the archive format, so it is frozen here.
     */
    fun streamingAead() = StreamingAeadKeyTemplates.AES256_GCM_HKDF_4KB
}

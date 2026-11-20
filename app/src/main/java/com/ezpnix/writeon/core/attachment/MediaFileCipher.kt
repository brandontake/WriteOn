package com.ezpnix.writeon.core.attachment

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Streaming AES-256-GCM container for attachment bytes (SEC-4).
 *
 * ## On-disk format (`WMV1`)
 *
 * ```
 * offset 0   'W' 'M' 'V' '1'        4-byte ASCII magic
 * offset 4   0x01                   format version (one byte)
 * offset 5   chunk*                 zero or more chunks, then the sentinel
 *
 * chunk:
 *   int32  plaintextLength          big-endian, 1..64 KiB (65536)
 *   byte[12] iv                     fresh random IV for this chunk
 *   byte[plaintextLength + 16] ct   AES-GCM ciphertext, 128-bit tag appended
 *
 * sentinel:
 *   int32  -1                       end of stream; nothing follows it
 * ```
 *
 * Each chunk is sealed independently: `AES/GCM/NoPadding`, 128-bit tag, a fresh random IV, and a
 * 256-bit AES key. The 4-byte length is passed to GCM as **AAD** (not stored twice), so a flipped
 * length byte fails authentication instead of silently re-slicing the stream; the layout on disk is
 * exactly the bytes above.
 *
 * A malformed stream is rejected rather than guessed at: a negative length other than -1, a length
 * above [CHUNK_BYTES], a short IV/ciphertext, or a tag that does not verify all throw
 * [MediaFileFormatException]. The chunk limit means a hostile file can never make the reader
 * allocate more than 64 KiB + 16 bytes per chunk.
 *
 * ## Plaintext files are part of the format's contract
 *
 * Files written before this class existed - every picture and recording in today's installs - are
 * ordinary plaintext with no header. They stay readable: [isEncrypted] is a five-byte sniff, and
 * [copyTo] copies a plaintext file through unchanged. Only a file that starts with the magic is
 * treated as ciphertext, which is what lets attachment encryption roll out file by file without a
 * migration that could lose media. Callers encrypt lazily on the next write (see
 * [encryptInPlace]) and leave everything else alone.
 *
 * All methods do blocking IO and never hold plaintext for longer than one chunk. Callers run them
 * on `Dispatchers.IO`, not on the main thread.
 *
 * @param key a 256-bit AES key. On Android this is the Keystore entry behind [MediaVault]; plain JVM
 *   tests pass a generated [SecretKey].
 */
class MediaFileCipher(private val key: SecretKey) {

    /**
     * True when [file] starts with the `WMV1` magic and a version byte.
     *
     * Only the header is read, so this is cheap enough for a list row. It returns false on a
     * missing/short file or any IO error: an unreadable file is treated as plaintext so a read
     * path degrades to "try the bytes as they are" instead of failing the whole screen.
     *
     * The version byte is deliberately **not** checked here (see [hasEncryptedHeader]); a file
     * from a future version still reports true so no caller copies ciphertext into a backup or a
     * player as if it were media.
     */
    fun isEncrypted(file: File): Boolean = hasEncryptedHeader(file)

    /**
     * Encrypts [source] into [destination] in [CHUNK_BYTES] chunks, then fsyncs it.
     *
     * [destination] is overwritten and is removed again if anything fails; [source] is only ever
     * read. The write is not atomic on its own - callers that must never expose a half file (the
     * attachment store) write a sibling temp file with [encryptInPlace] and rename it over the
     * original. Returns when the destination bytes are durable.
     *
     * @throws MediaFileFormatException if the cipher produces an IV the format cannot store.
     */
    fun encrypt(source: File, destination: File) {
        require(source.absolutePath != destination.absolutePath) {
            "Refusing to encrypt a file onto itself"
        }

        val fileOutput = FileOutputStream(destination)
        var complete = false
        try {
            val data = DataOutputStream(BufferedOutputStream(fileOutput, IO_BUFFER_BYTES))
            data.write(MAGIC_BYTES)
            data.writeByte(FORMAT_VERSION)

            BufferedInputStream(FileInputStream(source), IO_BUFFER_BYTES).use { input ->
                val buffer = ByteArray(CHUNK_BYTES)
                while (true) {
                    val plaintextLength = readChunk(input, buffer)
                    if (plaintextLength == 0) {
                        data.writeInt(EOF_SENTINEL)
                        break
                    }

                    val lengthBytes = lengthBytes(plaintextLength)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    // No explicit IV: the provider (Keystore included) generates a random one per
                    // chunk, which is exactly the "fresh IV every chunk" rule.
                    cipher.init(Cipher.ENCRYPT_MODE, key)
                    cipher.updateAAD(lengthBytes)
                    val iv = cipher.iv
                    if (iv.size != IV_BYTES) {
                        throw MediaFileFormatException(
                            "GCM produced a ${iv.size}-byte IV; the format stores $IV_BYTES"
                        )
                    }

                    data.writeInt(plaintextLength)
                    data.write(iv)
                    data.write(cipher.doFinal(buffer, 0, plaintextLength))
                }
            }

            data.flush()
            fileOutput.fd.sync()
            complete = true
        } finally {
            runCatching { fileOutput.close() }
            if (!complete) destination.delete()
        }
    }

    /**
     * Decrypts [source] into [destination] and fsyncs it.
     *
     * [source] is only read. [destination] is removed again if authentication, framing or IO fails,
     * so a caller never ends up with a partially decrypted file that looks complete.
     */
    fun decrypt(source: File, destination: File) {
        require(source.absolutePath != destination.absolutePath) {
            "Refusing to decrypt a file onto itself"
        }

        val fileOutput = FileOutputStream(destination)
        var complete = false
        try {
            val buffered = BufferedOutputStream(fileOutput, IO_BUFFER_BYTES)
            decryptToStream(source, buffered)
            buffered.flush()
            fileOutput.fd.sync()
            complete = true
        } finally {
            runCatching { fileOutput.close() }
            if (!complete) destination.delete()
        }
    }

    /**
     * Copies [source] to [output], decrypting only when it is a `WMV1` file.
     *
     * This is the backup-archive entry point (see [MediaVault.copyTo]): portable archives must hold
     * readable bytes because the media key cannot leave the device, so plaintext passes straight
     * through and encrypted files are decrypted into the caller's stream. [output] is flushed by
     * the caller's framing (a zip entry), never closed or buffered beyond this call.
     */
    fun copyTo(source: File, output: OutputStream) {
        if (hasEncryptedHeader(source)) {
            decryptToStream(source, output)
        } else {
            FileInputStream(source).use { input ->
                input.copyTo(output, IO_BUFFER_BYTES)
            }
        }
    }

    /**
     * Decrypts a `WMV1` stream into [output] without closing it.
     *
     * [output] is left open because callers own it (a zip entry, a socket, the caller's own file
     * stream). Use [copyTo] when plaintext files should pass through unchanged.
     */
    fun decryptToStream(source: File, output: OutputStream) {
        BufferedInputStream(FileInputStream(source), IO_BUFFER_BYTES).use { input ->
            val header = ByteArray(HEADER_BYTES)
            readExactly(input, header, 0, HEADER_BYTES)
            if (!header.copyOf(MAGIC_BYTES.size).contentEquals(MAGIC_BYTES)) {
                throw MediaFileFormatException("not a WMV1 media file")
            }
            val version = header[MAGIC_BYTES.size].toInt() and 0xFF
            if (version != FORMAT_VERSION) {
                throw MediaFileFormatException("unsupported WMV format version $version")
            }

            val data = DataInputStream(input)
            while (true) {
                val plaintextLength = try {
                    data.readInt()
                } catch (e: EOFException) {
                    throw MediaFileFormatException("truncated media file: missing chunk header", e)
                }

                if (plaintextLength == EOF_SENTINEL) return
                if (plaintextLength < 0) {
                    throw MediaFileFormatException("invalid media chunk length $plaintextLength")
                }
                if (plaintextLength > CHUNK_BYTES) {
                    throw MediaFileFormatException(
                        "media chunk length $plaintextLength exceeds $CHUNK_BYTES bytes"
                    )
                }

                val iv = ByteArray(IV_BYTES)
                readExactly(data, iv, 0, IV_BYTES)
                val ciphertext = ByteArray(plaintextLength + TAG_BYTES)
                readExactly(data, ciphertext, 0, ciphertext.size)

                val plaintext = try {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                    cipher.updateAAD(lengthBytes(plaintextLength))
                    cipher.doFinal(ciphertext)
                } catch (e: GeneralSecurityException) {
                    // Wrong key, corrupted bytes and a replayed chunk all land here. The caller is
                    // told the file is unreadable, never handed unauthenticated bytes.
                    throw MediaFileFormatException("media chunk failed authentication", e)
                }

                if (plaintext.size != plaintextLength) {
                    throw MediaFileFormatException(
                        "media chunk decrypted to ${plaintext.size} bytes, expected $plaintextLength"
                    )
                }
                output.write(plaintext)
            }
        }
    }

    /**
     * Encrypts [source] in place through the sibling [temp] file and an atomic rename.
     *
     * Used by [MediaVault.encryptInPlace], where the original must survive every failure: the
     * ciphertext is written and fsynced to [temp] first, then moved over [source] with
     * `ATOMIC_MOVE`. [temp] is deleted on failure and [source] is never opened for writing.
     *
     * @return true when [source] is encrypted afterwards, false when nothing was changed.
     */
    fun encryptInPlace(source: File, temp: File): Boolean {
        if (temp.absolutePath == source.absolutePath) return false

        return try {
            encrypt(source, temp)
            if (replaceAtomically(temp, source)) {
                true
            } else {
                temp.delete()
                false
            }
        } catch (e: Exception) {
            runCatching { temp.delete() }
            false
        }
    }

    /** Same-directory rename that replaces existing content; falls back when ATOMIC_MOVE is unsupported. */
    private fun replaceAtomically(temp: File, target: File): Boolean = try {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
        true
    } catch (e: AtomicMoveNotSupportedException) {
        runCatching {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
    } catch (e: Exception) {
        false
    }

    /**
     * Fills [buffer] from [input] until it is full or the stream ends.
     *
     * A short read is not the end of the file: only a raw `read` result of -1 is. This keeps the
     * chunk boundaries identical for files and pipes.
     */
    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    /** Reads exactly [length] bytes or fails with a format error; used for every fixed-size field. */
    private fun readExactly(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val count = input.read(buffer, offset + read, length - read)
            if (count < 0) {
                throw MediaFileFormatException(
                    "truncated media file: expected $length bytes, got $read"
                )
            }
            read += count
        }
    }

    companion object {
        /** Plaintext bytes per chunk. Also the hard cap the reader accepts. */
        const val CHUNK_BYTES = 64 * 1024

        /** First four bytes of every encrypted file. */
        const val MAGIC = "WMV1"

        /** Second part of the header; only version 1 is written or read today. */
        const val FORMAT_VERSION = 1

        /** [MAGIC] + one version byte. */
        const val HEADER_BYTES = 5

        const val IV_BYTES = 12
        const val TAG_BYTES = 16

        /** `int32 -1` after the last chunk. */
        const val EOF_SENTINEL = -1

        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IO_BUFFER_BYTES = 64 * 1024
        private val MAGIC_BYTES = MAGIC.toByteArray(Charsets.US_ASCII)

        /**
         * Header-only sniff shared by [isEncrypted] and [MediaVault], which must answer "is this a
         * `WMV1` file?" without loading (or creating) the Keystore key.
         *
         * Returns false on a shorter-than-header file and on any IO error, by design: callers then
         * treat the bytes as plaintext instead of failing the screen that wanted a thumbnail.
         */
        fun hasEncryptedHeader(file: File): Boolean {
            if (!file.isFile || file.length() < HEADER_BYTES) return false
            return runCatching {
                FileInputStream(file).use { input ->
                    val header = ByteArray(HEADER_BYTES)
                    var read = 0
                    while (read < HEADER_BYTES) {
                        val count = input.read(header, read, HEADER_BYTES - read)
                        if (count < 0) return@use false
                        read += count
                    }
                    header.copyOf(MAGIC_BYTES.size).contentEquals(MAGIC_BYTES)
                }
            }.getOrDefault(false)
        }
    }

    /** Big-endian 32-bit form of a chunk length; also the GCM AAD for that chunk. */
    private fun lengthBytes(length: Int): ByteArray = byteArrayOf(
        (length ushr 24).toByte(),
        (length ushr 16).toByte(),
        (length ushr 8).toByte(),
        length.toByte()
    )
}

/**
 * The only failure type a `WMV1` reader raises.
 *
 * Framing errors and authentication failures are both reported here, so a caller (a read path, the
 * backup writer, [MediaVault.materialize]) has one exception to catch and cannot mistake a corrupt
 * chunk for a plaintext file. The cause is kept for logs; it is never shown to the user.
 */
class MediaFileFormatException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

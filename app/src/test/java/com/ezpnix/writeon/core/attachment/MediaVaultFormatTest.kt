package com.ezpnix.writeon.core.attachment

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the `WMV1` container (SEC-4).
 *
 * [MediaFileCipher] takes a plain [SecretKey], so these run without Robolectric, a Context or the
 * Android Keystore. The Android half ([MediaVault]) is covered only through its pure cache-name
 * helper; anything that needs a Context or the Keystore belongs to an instrumented test.
 *
 * The framing is parsed back by hand instead of trusting the cipher's own reader: a round trip
 * would still pass if the header or the sentinel were wrong, and those are exactly the bytes a
 * later lane (backup/restore) has to rely on.
 */
class MediaVaultFormatTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val cipher = MediaFileCipher(newKey())

    // -----------------------------------------------------------------------------------------
    // Round trip and framing
    // -----------------------------------------------------------------------------------------

    @Test
    fun `multi chunk payload round trips with a non aligned tail`() {
        val plaintext = payload(200 * 1024 + 7)
        val source = temporaryFolder.newFile("memo.m4a").apply { writeBytes(plaintext) }
        val encrypted = File(temporaryFolder.root, "memo.m4a.wmv")

        cipher.encrypt(source, encrypted)

        assertTrue(cipher.isEncrypted(encrypted))

        val frames = readFrames(encrypted)
        assertEquals(listOf(65_536, 65_536, 65_536, 8_199), frames.map { it.plaintextLength })
        // A fresh IV per chunk: identical plaintext chunks must not repeat a keystream.
        assertEquals(frames.size, frames.map { it.iv.toList() }.toSet().size)

        val decrypted = File(temporaryFolder.root, "memo.m4a.out")
        cipher.decrypt(encrypted, decrypted)
        assertArrayEquals(plaintext, decrypted.readBytes())

        val backedUp = ByteArrayOutputStream()
        cipher.copyTo(encrypted, backedUp)
        assertArrayEquals(plaintext, backedUp.toByteArray())
    }

    @Test
    fun `encrypted files carry the wmv1 header and the eof sentinel`() {
        val encrypted = encryptedCopy(payload(10))
        val bytes = encrypted.readBytes()

        assertEquals("WMV1", String(bytes.copyOf(4), Charsets.US_ASCII))
        assertEquals(MediaFileCipher.FORMAT_VERSION, bytes[4].toInt())
        assertEquals(
            MediaFileCipher.EOF_SENTINEL,
            ByteBuffer.wrap(bytes, bytes.size - 4, 4).int
        )
        // header + length + iv + ciphertext(+tag) + sentinel
        assertEquals(
            MediaFileCipher.HEADER_BYTES + 4 + MediaFileCipher.IV_BYTES +
                (10 + MediaFileCipher.TAG_BYTES) + 4,
            bytes.size
        )
    }

    @Test
    fun `empty files round trip`() {
        val source = temporaryFolder.newFile("empty.bin")
        val encrypted = File(temporaryFolder.root, "empty.bin.wmv")

        cipher.encrypt(source, encrypted)

        assertEquals((MediaFileCipher.HEADER_BYTES + 4).toLong(), encrypted.length())
        assertTrue(cipher.isEncrypted(encrypted))
        assertTrue(readFrames(encrypted).isEmpty())

        val decrypted = File(temporaryFolder.root, "empty.bin.out")
        cipher.decrypt(encrypted, decrypted)
        assertEquals(0L, decrypted.length())
    }

    // -----------------------------------------------------------------------------------------
    // Plaintext compatibility
    // -----------------------------------------------------------------------------------------

    @Test
    fun `plaintext files pass through unchanged`() {
        val plaintext = payload(4_096)
        val file = temporaryFolder.newFile("IMG_0001.jpg").apply { writeBytes(plaintext) }

        assertFalse(cipher.isEncrypted(file))

        val copied = ByteArrayOutputStream()
        cipher.copyTo(file, copied)
        assertArrayEquals(plaintext, copied.toByteArray())
        assertArrayEquals(plaintext, file.readBytes())
    }

    @Test
    fun `files shorter than the header are not encrypted`() {
        val tiny = temporaryFolder.newFile("tiny.bin").apply {
            writeBytes("WMV".toByteArray(Charsets.US_ASCII))
        }
        assertFalse(cipher.isEncrypted(tiny))
    }

    // -----------------------------------------------------------------------------------------
    // Rejection paths
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a corrupted chunk fails authentication and leaves the source untouched`() {
        val encrypted = encryptedCopy(payload(1_000))
        val corrupted = encrypted.readBytes()
        val firstCiphertextByte = MediaFileCipher.HEADER_BYTES + 4 + MediaFileCipher.IV_BYTES + 10
        corrupted[firstCiphertextByte] = (corrupted[firstCiphertextByte].toInt() xor 0x01).toByte()

        val broken = File(temporaryFolder.root, "broken.wmv").apply { writeBytes(corrupted) }
        val before = broken.readBytes()
        val destination = File(temporaryFolder.root, "broken.out")

        assertThrows(MediaFileFormatException::class.java) {
            cipher.decrypt(broken, destination)
        }

        assertArrayEquals(before, broken.readBytes())
        assertFalse(destination.exists())
    }

    @Test
    fun `a different key fails the tag`() {
        val encrypted = encryptedCopy(payload(500))
        val other = MediaFileCipher(newKey())
        val destination = File(temporaryFolder.root, "wrong-key.out")

        assertThrows(MediaFileFormatException::class.java) {
            other.decrypt(encrypted, destination)
        }
        assertFalse(destination.exists())
    }

    @Test
    fun `truncated files are rejected`() {
        val encrypted = encryptedCopy(payload(70_000))
        val bytes = encrypted.readBytes()
        val truncated = File(temporaryFolder.root, "truncated.wmv").apply {
            writeBytes(bytes.copyOf(bytes.size - 10))
        }

        assertThrows(MediaFileFormatException::class.java) {
            cipher.decrypt(truncated, File(temporaryFolder.root, "truncated.out"))
        }
    }

    @Test
    fun `oversized and negative chunk lengths are rejected before allocation`() {
        val oversized = writeHeaderThenLength("oversized.wmv", MediaFileCipher.CHUNK_BYTES + 1)
        val oversizedError = assertThrows(MediaFileFormatException::class.java) {
            cipher.decrypt(oversized, File(temporaryFolder.root, "oversized.out"))
        }
        assertTrue(oversizedError.message.orEmpty().contains("exceeds"))

        val negative = writeHeaderThenLength("negative.wmv", -2)
        assertThrows(MediaFileFormatException::class.java) {
            cipher.decrypt(negative, File(temporaryFolder.root, "negative.out"))
        }
    }

    @Test
    fun `an unknown format version is rejected`() {
        val future = File(temporaryFolder.root, "future.wmv")
        DataOutputStream(future.outputStream()).use { out ->
            out.write("WMV1".toByteArray(Charsets.US_ASCII))
            out.writeByte(MediaFileCipher.FORMAT_VERSION + 1)
            out.writeInt(MediaFileCipher.EOF_SENTINEL)
        }

        assertTrue(cipher.isEncrypted(future))
        assertThrows(MediaFileFormatException::class.java) {
            cipher.decrypt(future, File(temporaryFolder.root, "future.out"))
        }
    }

    // -----------------------------------------------------------------------------------------
    // In-place write contract
    // -----------------------------------------------------------------------------------------

    @Test
    fun `in place encryption replaces the file atomically`() {
        val plaintext = payload(70_001)
        val source = temporaryFolder.newFile("IMG_0002.jpg").apply { writeBytes(plaintext) }
        val temp = File(temporaryFolder.root, source.name + ".media-part")

        assertTrue(cipher.encryptInPlace(source, temp))
        assertTrue(cipher.isEncrypted(source))
        assertFalse(temp.exists())

        val decrypted = File(temporaryFolder.root, "in-place.out")
        cipher.decrypt(source, decrypted)
        assertArrayEquals(plaintext, decrypted.readBytes())
    }

    @Test
    fun `a failed in place encryption leaves the original unchanged`() {
        val plaintext = payload(2_048)
        val source = temporaryFolder.newFile("IMG_0003.jpg").apply { writeBytes(plaintext) }
        // A temp file whose parent does not exist: the write fails before the rename.
        val temp = File(File(temporaryFolder.root, "missing-dir"), "part")

        assertFalse(cipher.encryptInPlace(source, temp))
        assertArrayEquals(plaintext, source.readBytes())
        assertFalse(cipher.isEncrypted(source))
        assertFalse(temp.exists())
    }

    // -----------------------------------------------------------------------------------------
    // Cache naming
    // -----------------------------------------------------------------------------------------

    @Test
    fun `cache names are deterministic and change with size or modification time`() {
        val path = "/data/user/0/com.ezpnix.writeon/files/attachments/memo.m4a"
        val name = MediaCacheNames.fileName(path, 1_024, 1_700_000_000_000, "M4A")

        assertEquals(name, MediaCacheNames.fileName(path, 1_024, 1_700_000_000_000, "m4a"))
        assertTrue(name.endsWith(".m4a"))
        assertTrue(name.startsWith(MediaCacheNames.pathPrefix(path) + MediaCacheNames.SEPARATOR))

        assertNotEquals(name, MediaCacheNames.fileName(path, 1_025, 1_700_000_000_000, "m4a"))
        assertNotEquals(name, MediaCacheNames.fileName(path, 1_024, 1_700_000_000_001, "m4a"))
        assertNotEquals(name, MediaCacheNames.fileName("$path.bak", 1_024, 1_700_000_000_000, "m4a"))
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private data class Frame(
        val plaintextLength: Int,
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    private fun newKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun payload(size: Int): ByteArray = ByteArray(size) { index ->
        ((index * 37 + 11) % 251).toByte()
    }

    private fun encryptedCopy(plaintext: ByteArray, name: String = "memo.bin"): File {
        val source = temporaryFolder.newFile(name).apply { writeBytes(plaintext) }
        val encrypted = File(temporaryFolder.root, "$name.wmv")
        cipher.encrypt(source, encrypted)
        return encrypted
    }

    private fun writeHeaderThenLength(name: String, chunkLength: Int): File {
        val file = File(temporaryFolder.root, name)
        DataOutputStream(file.outputStream()).use { out ->
            out.write("WMV1".toByteArray(Charsets.US_ASCII))
            out.writeByte(MediaFileCipher.FORMAT_VERSION)
            out.writeInt(chunkLength)
        }
        return file
    }

    /** Parses the container by hand so the tests do not reuse the code under test. */
    private fun readFrames(file: File): List<Frame> =
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            val header = ByteArray(MediaFileCipher.HEADER_BYTES)
            input.readFully(header)
            assertEquals("WMV1", String(header.copyOf(4), Charsets.US_ASCII))
            assertEquals(MediaFileCipher.FORMAT_VERSION, header[4].toInt())

            val frames = mutableListOf<Frame>()
            while (true) {
                val length = input.readInt()
                if (length == MediaFileCipher.EOF_SENTINEL) break
                assertTrue(length in 1..MediaFileCipher.CHUNK_BYTES)

                val iv = ByteArray(MediaFileCipher.IV_BYTES)
                input.readFully(iv)
                val ciphertext = ByteArray(length + MediaFileCipher.TAG_BYTES)
                input.readFully(ciphertext)
                frames += Frame(length, iv, ciphertext)
            }
            // Nothing may follow the sentinel.
            assertEquals(-1, input.read())
            frames
        }
}

package com.ezpnix.writeon.core.attachment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TST-01: the `WMV1` media container and [MediaVault] against the real Keystore.
 *
 * The JVM suite (`MediaVaultFormatTest`) uses a plain AES key generated in memory, so it can prove
 * the format but not the wiring: the Keystore alias, the key spec and the encrypted-at-rest
 * promise. This test encrypts a real file through [MediaVault.encryptInPlace], asserts the
 * plaintext is gone, decrypts it to a different path and compares the bytes, and proves that a
 * corrupted or non-`WMV1` input fails closed.
 *
 * Like [com.ezpnix.writeon.data.local.database.NoteCryptoDeviceTest] it only runs on a device or
 * emulator (`connectedDebugAndroidTest`); the infrastructure is part of TST-01, not a claimed
 * local device run.
 */
@RunWith(AndroidJUnit4::class)
class MediaVaultDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = File(context.cacheDir, "media-vault-device-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        // The cache holds plaintext generations; leaving them behind would make a later test that
        // expects "no decryptable bytes" pass for the wrong reason.
        MediaVault.clearCache(context)
        workDir.deleteRecursively()
    }

    @Test
    fun encryptInPlaceRemovesPlaintextAndDecryptsToAnotherPath() {
        val plaintext = "WMV1 test payload: the quick brown fox \uD83D\uDC36 0123456789".toByteArray()
        val source = File(workDir, "recording.m4a").apply { writeBytes(plaintext) }

        assertTrue("encryptInPlace must report success", MediaVault.encryptInPlace(context, source))
        assertTrue("the file must be recognised as WMV1", MediaVault.isEncrypted(context, source))

        val encryptedBytes = source.readBytes()
        assertArrayEquals(
            "the file must start with the WMV1 magic",
            MediaFileCipher.MAGIC.toByteArray(Charsets.US_ASCII),
            encryptedBytes.copyOf(MediaFileCipher.MAGIC.length)
        )
        assertFalse(
            "no plaintext run may survive inside the encrypted file",
            encryptedBytes.containsBytes(plaintext)
        )
        assertTrue("encryption must change the file size", encryptedBytes.size > plaintext.size)

        val restored = File(workDir, "restored.m4a")
        assertTrue("decryptTo must succeed", MediaVault.decryptTo(context, source, restored))
        assertTrue(restored.isFile)
        assertArrayEquals("the decrypted bytes must match the original", plaintext, restored.readBytes())
    }

    @Test
    fun encryptInPlaceIsIdempotent() {
        val plaintext = "already encrypted".toByteArray()
        val source = File(workDir, "once.jpg").apply { writeBytes(plaintext) }

        assertTrue(MediaVault.encryptInPlace(context, source))
        val firstPass = source.readBytes()
        assertTrue(MediaVault.encryptInPlace(context, source))
        assertArrayEquals("a WMV1 file must not be encrypted twice", firstPass, source.readBytes())
    }

    @Test
    fun aCorruptedCiphertextFailsClosed() {
        val source = File(workDir, "broken.jpg").apply {
            writeBytes("corrupt me".repeat(64).toByteArray())
        }
        assertTrue(MediaVault.encryptInPlace(context, source))

        // Corrupt the GCM tag of the last chunk; framing still looks valid, authentication does not.
        val bytes = source.readBytes()
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0x01).toByte()
        source.writeBytes(bytes)

        val target = File(workDir, "broken-out.jpg")
        assertFalse("decryptTo must reject a corrupted stream", MediaVault.decryptTo(context, source, target))
        assertFalse("a failed decrypt must not leave a destination behind", target.exists())
        assertNull(
            "materialize must not return a path for unauthenticated media",
            MediaVault.materialize(context, source.absolutePath)
        )
    }

    @Test
    fun aNonWmvFileFailsClosedOnTheDecryptPath() {
        // Make sure the media key exists, so the failure below is the format check and not a
        // "key is missing" short-circuit (which would also return false but test less).
        MediaVault.encryptInPlace(context, File(workDir, "ensure-key.jpg").apply { writeText("key") })

        val plaintext = File(workDir, "plain.txt").apply { writeText("not encrypted at all") }
        assertFalse(MediaVault.isEncrypted(context, plaintext))

        val target = File(workDir, "plain-out.txt")
        assertFalse(
            "decryptTo must reject input that is not a WMV1 stream",
            MediaVault.decryptTo(context, plaintext, target)
        )
        assertFalse(target.exists())

        // A plaintext file is legal for the read path; only the explicit decrypt call is strict.
        assertEquals(plaintext.absolutePath, MediaVault.materialize(context, plaintext.absolutePath))
    }

    /** Byte-level containment check; a substring search over `String` could misread binary data. */
    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > size) return false
        for (start in 0..(size - needle.size)) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }
}

package com.ezpnix.writeon.core.util

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The JVM-testable half of the automatic-backup cipher (V-1/V-2).
 *
 * The production keyset lives behind `AndroidKeysetManager` + the Android Keystore, which a local
 * unit test does not have - so this suite skips the `Context` overloads and drives the internal
 * primitive-level helpers with a keyset generated in-process (still real Tink, no Keystore).
 * Everything that broke was plain Tink/JDK behaviour: an AEAD keyset cannot yield a
 * [StreamingAead], and Tink's encrypting stream closes the stream it wraps, which made the
 * worker's `fd.sync()` throw after the write. Both are pinned here.
 */
class AutoBackupStreamTest {

    private val associatedData = "writeon-auto-backup".toByteArray(StandardCharsets.UTF_8)

    @Before
    fun registerTink() {
        // Idempotent; KeysetHandle.generateNew resolves its key manager through the registry.
        AeadConfig.register()
        StreamingAeadConfig.register()
    }

    @Test
    fun `an aead keyset cannot produce a streaming primitive`() {
        val handle = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM)

        val failure = runCatching { handle.getPrimitive(StreamingAead::class.java) }.exceptionOrNull()

        // The exact V-1 regression: this is the keyset the old WAB2 code created before asking
        // it for a StreamingAead. Tink 1.8.0 refuses, so a positive test for the streaming
        // template alone would not stop someone reintroducing the AEAD template here.
        assertTrue(
            "an AEAD keyset must not yield a StreamingAead; WAB2 needs a streaming template",
            failure is GeneralSecurityException
        )
    }

    @Test
    fun `the chosen streaming template round trips a byte array`() {
        val aead = streamingAead()
        val payload = ByteArray(300 * 1024).also { SecureRandom().nextBytes(it) }

        val ciphertext = ByteArrayOutputStream().also { out ->
            aead.newEncryptingStream(out, associatedData).use { it.write(payload) }
        }.toByteArray()
        val plain = ByteArrayOutputStream().also { out ->
            aead.newDecryptingStream(ByteArrayInputStream(ciphertext), associatedData)
                .use { it.copyTo(out) }
        }.toByteArray()

        assertTrue("the streaming template must actually produce ciphertext", ciphertext.isNotEmpty())
        assertArrayEquals(payload, plain)
    }

    @Test
    fun `closing tinks encrypting stream leaves the callers stream open`() {
        val aead = streamingAead()
        val bytes = ByteArrayOutputStream()
        val sink = TrackingOutputStream(bytes)
        val payload = "the archive body".toByteArray(StandardCharsets.UTF_8)

        aead.newEncryptingStream(AutoBackupCrypto.nonClosingOutput(sink), associatedData).use { cipher ->
            cipher.write(payload)
        }

        val ciphertext = bytes.toByteArray()
        assertTrue("the wrapper must write through to the caller's stream", ciphertext.isNotEmpty())
        assertFalse("close() must not reach the caller's stream", sink.closed)
        assertTrue("close() must still flush the cipher's final chunk", sink.flushed)
        bytes.write(0)
        assertTrue("the caller's stream must still accept writes after Tink closes", bytes.size() > ciphertext.size)

        val plain = ByteArrayOutputStream().also { out ->
            aead.newDecryptingStream(ByteArrayInputStream(ciphertext), associatedData)
                .use { it.copyTo(out) }
        }.toByteArray()
        assertArrayEquals("wrapping must not change the ciphertext", payload, plain)
    }

    @Test
    fun `encryptStream writes the wab2 header and leaves both streams open`() {
        val payload = ByteArray(64 * 1024).also { SecureRandom().nextBytes(it) }
        val input = TrackingInputStream(ByteArrayInputStream(payload))
        val bytes = ByteArrayOutputStream()
        val output = TrackingOutputStream(bytes)

        AutoBackupCrypto.encryptStream(streamingAead(), input, output)

        assertEquals(
            "WAB2",
            bytes.toByteArray().copyOfRange(0, 4).toString(StandardCharsets.US_ASCII)
        )
        assertFalse("encryptStream must not close the caller's input", input.closed)
        // The worker flushes and fsyncs this stream afterwards; it has to still be open.
        assertFalse("encryptStream must not close the caller's output", output.closed)
        bytes.write(0)
        assertTrue(bytes.size() > 4)
    }

    @Test
    fun `decryptTo reads a wab2 archive without closing the callers input`() {
        val streaming = streamingAead()
        val legacy = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM)
            .getPrimitive(Aead::class.java)
        val payload = ByteArray(128 * 1024).also { SecureRandom().nextBytes(it) }
        val archive = ByteArrayOutputStream().also { out ->
            AutoBackupCrypto.encryptStream(streaming, ByteArrayInputStream(payload), out)
        }.toByteArray()

        val input = TrackingInputStream(ByteArrayInputStream(archive))
        val restored = ByteArrayOutputStream()
        val ok = AutoBackupCrypto.decryptTo(
            streaming = { streaming },
            legacy = { legacy },
            headerAndBody = input,
            output = restored
        )

        assertTrue("a WAB2 archive written by this install must authenticate", ok)
        assertArrayEquals(payload, restored.toByteArray())
        assertFalse("decryptTo must not close the caller's input", input.closed)
    }

    private fun streamingAead(): StreamingAead =
        KeysetHandle.generateNew(AutoBackupTemplates.streamingAead())
            .getPrimitive(StreamingAead::class.java)

    /** Records whether anyone closed or flushed the stream the cipher was handed. */
    private class TrackingOutputStream(private val delegate: ByteArrayOutputStream) : OutputStream() {
        var closed = false
            private set
        var flushed = false
            private set

        override fun write(b: Int) = delegate.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)

        override fun flush() {
            flushed = true
            delegate.flush()
        }

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    /** Records whether anyone closed the stream that was handed in. */
    private class TrackingInputStream(private val delegate: InputStream) : InputStream() {
        var closed = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun close() {
            closed = true
            delegate.close()
        }
    }
}

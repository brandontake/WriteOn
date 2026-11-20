package com.ezpnix.writeon.presentation.components.markdown

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.attachment.MediaFileCipher
import com.ezpnix.writeon.domain.model.Attachment
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SEC-4: the resolution policy of the attachment cache.
 *
 * Robolectric has no Android Keystore, so an encrypted file can never be decrypted here. That is
 * enough to test the two halves that matter and do not need a key:
 * - a plaintext (legacy) attachment resolves to its stored path, exactly as before encryption;
 * - an encrypted attachment is **never** handed to a renderer as ciphertext - the caller gets null
 *   (draw a placeholder), and a failed decryption keeps returning null instead of falling back to
 *   the `WMV1` file.
 *
 * The successful materialisation path needs a real key and stays instrumented.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentRegistryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AttachmentRegistry.initialize(context)
    }

    @After
    fun tearDown() {
        AttachmentRegistry.remove(listOf(PLAINTEXT_ID, ENCRYPTED_ID, MISSING_ID))
        AttachmentRegistry.clearMaterialized()
    }

    @Test
    fun `a plaintext attachment resolves to its stored path`() {
        val file = File(context.filesDir, "legacy.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        AttachmentRegistry.put(attachment(PLAINTEXT_ID, file.absolutePath))

        assertEquals(file.absolutePath, AttachmentRegistry.resolve("attachment://$PLAINTEXT_ID"))
        assertEquals(file.absolutePath, AttachmentRegistry.playablePath(PLAINTEXT_ID))
        assertEquals("the stored path is still the one a delete or a backup must use", file.absolutePath, AttachmentRegistry.path(PLAINTEXT_ID))
    }

    @Test
    fun `an unknown id resolves to null so the renderer draws a placeholder`() {
        assertNull(AttachmentRegistry.resolve("attachment://$MISSING_ID"))
        assertNull(AttachmentRegistry.playablePath(MISSING_ID))
    }

    @Test
    fun `an encrypted attachment is never returned as ciphertext`() {
        val file = File(context.filesDir, "secret.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(wmvHeader())
        }
        AttachmentRegistry.put(attachment(ENCRYPTED_ID, file.absolutePath))

        // Not ready on the frame that asked: the caller draws its placeholder.
        assertNull(AttachmentRegistry.resolve("attachment://$ENCRYPTED_ID"))
        // Robolectric cannot decrypt, so it must keep saying "not available" rather than fall back
        // to the encrypted file's path (which Coil/MediaPlayer would read as garbage).
        assertNull(AttachmentRegistry.playablePath(ENCRYPTED_ID))
        // The durable path is still available for callers that need the file itself.
        assertEquals(file.absolutePath, AttachmentRegistry.path(ENCRYPTED_ID))
    }

    @Test
    fun `a target that is not an attachment id passes through untouched`() {
        // 1.x notes stored absolute paths in their bodies; those files are plaintext by definition.
        assertEquals("/legacy/path/IMG_0001.jpg", AttachmentRegistry.resolve("/legacy/path/IMG_0001.jpg"))
    }

    private fun attachment(id: String, path: String): Attachment =
        Attachment(id = id, type = Attachment.TYPE_IMAGE, path = path)

    /** The header [MediaFileCipher] recognises; no payload a key could authenticate. */
    private fun wmvHeader(): ByteArray {
        val header = ByteArray(MediaFileCipher.HEADER_BYTES)
        "WMV1".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[4] = MediaFileCipher.FORMAT_VERSION.toByte()
        return header
    }

    private companion object {
        const val PLAINTEXT_ID = "registry-plaintext"
        const val ENCRYPTED_ID = "registry-encrypted"
        const val MISSING_ID = "registry-missing"
    }
}

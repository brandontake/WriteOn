package com.ezpnix.writeon.presentation.components.markdown

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.attachment.AttachmentArchive
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
        // REG-05: the row path must live under one of the app's media roots like every other
        // renderable path; a legitimate attachment does.
        val file = File(context.filesDir, AttachmentArchive.DIRECTORY).apply { mkdirs() }
            .resolve("legacy.jpg")
            .apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        AttachmentRegistry.put(attachment(PLAINTEXT_ID, file.absolutePath))

        assertEquals(file.canonicalPath, AttachmentRegistry.resolve("attachment://$PLAINTEXT_ID"))
        assertEquals(file.canonicalPath, AttachmentRegistry.playablePath(PLAINTEXT_ID))
        assertEquals("the stored path is still the one a delete or a backup must use", file.absolutePath, AttachmentRegistry.path(PLAINTEXT_ID))
    }

    @Test
    fun `a row path outside the media roots is refused`() {
        // An `attachments` row that was constructed or restored is untrusted input too.
        val outside = File(context.filesDir, "outside-root.jpg").apply {
            writeBytes(byteArrayOf(1))
        }
        AttachmentRegistry.put(attachment(PLAINTEXT_ID, outside.absolutePath))
        try {
            assertNull(AttachmentRegistry.resolve("attachment://$PLAINTEXT_ID"))
            assertNull(AttachmentRegistry.playablePath(PLAINTEXT_ID))
        } finally {
            AttachmentRegistry.remove(listOf(PLAINTEXT_ID))
        }
    }

    @Test
    fun `an unknown id resolves to null so the renderer draws a placeholder`() {
        assertNull(AttachmentRegistry.resolve("attachment://$MISSING_ID"))
        assertNull(AttachmentRegistry.playablePath(MISSING_ID))
    }

    @Test
    fun `an encrypted attachment is never returned as ciphertext`() {
        val file = File(context.filesDir, AttachmentArchive.DIRECTORY).apply { mkdirs() }
            .resolve("secret.jpg")
            .apply { writeBytes(wmvHeader()) }
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
    fun `a legacy path outside the app media roots is refused`() {
        // 1.x notes stored absolute paths in their bodies. A path is only renderable when it is a
        // canonical file under one of the app's own media roots; anything else could be an
        // attacker-controlled scheme or a private file, so rendering must fall back to the
        // placeholder instead of handing the string to Coil/MediaPlayer (SEC-06).
        assertNull(AttachmentRegistry.resolve("/legacy/path/IMG_0001.jpg"))
    }

    @Test
    fun `a file encrypted in place is never served as plaintext from the old classification`() {
        val file = File(context.filesDir, AttachmentArchive.DIRECTORY).apply { mkdirs() }
            .resolve("migrated.jpg")
            .apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        AttachmentRegistry.put(attachment(PLAINTEXT_ID, file.absolutePath))
        assertEquals("plaintext first", file.canonicalPath, AttachmentRegistry.playablePath(PLAINTEXT_ID))

        // R2-02: the startup LegacyMediaMigration rewrites the file in place while this process is
        // alive. The registry had cached "plaintext" for the path, so it kept returning the raw
        // path - now a WMV1 container - to the image renderer and the save-to-gallery export.
        file.writeBytes(wmvHeader())
        AttachmentRegistry.clearEncryptionClassification()

        assertNull(
            "an encrypted file must not be handed out as if it were plaintext",
            AttachmentRegistry.playablePath(PLAINTEXT_ID)
        )
        AttachmentRegistry.remove(listOf(PLAINTEXT_ID))
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

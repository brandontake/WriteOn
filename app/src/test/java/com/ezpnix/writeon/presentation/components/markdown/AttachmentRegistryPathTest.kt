package com.ezpnix.writeon.presentation.components.markdown

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.attachment.AttachmentFiles
import com.ezpnix.writeon.domain.model.Attachment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SEC-06: legacy `!(...)`/`?audio(...)` targets are an allowlist, not a pass-through.
 *
 * `resolve()` used to return any non-`attachment://` string unchanged, and the image/audio blocks
 * hand the result to Coil/MediaPlayer. Rendering a note therefore performed whatever the body said:
 * an HTTP request, a `file:` read, or a `content:` query. These tests cover the four rejected
 * classes and the two accepted ones.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentRegistryPathTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AttachmentRegistry.initialize(context)
    }

    @Test
    fun `http and https targets are refused`() {
        assertNull(AttachmentRegistry.resolve("http://192.168.1.10/track.png"))
        assertNull(AttachmentRegistry.resolve("https://attacker.example/track.png"))
    }

    @Test
    fun `file and content targets are refused`() {
        assertNull(AttachmentRegistry.resolve("file:///data/data/com.ezpnix.writeon/shared_prefs/writeon_lock.xml"))
        assertNull(AttachmentRegistry.resolve("content://media/external/images/media/1"))
    }

    @Test
    fun `a relative target is refused`() {
        assertNull(AttachmentRegistry.resolve("images/photo.jpg"))
        assertNull(AttachmentRegistry.resolve(""))
    }

    @Test
    fun `a file inside the attachment root resolves`() {
        val file = File(context.filesDir, AttachmentArchive.DIRECTORY).apply { mkdirs() }
            .resolve("legacy-image.jpg")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertEquals(file.canonicalPath, AttachmentRegistry.resolve(file.absolutePath))
    }

    @Test
    fun `a file inside the audio root resolves`() {
        val file = File(context.filesDir, AttachmentFiles.AUDIO_DIRECTORY).apply { mkdirs() }
            .resolve("legacy-recording.m4a")
            .apply { writeBytes(byteArrayOf(1)) }

        assertEquals(file.canonicalPath, AttachmentRegistry.resolve(file.absolutePath))
    }

    @Test
    fun `traversal out of the media roots is refused`() {
        val secret = File(context.filesDir, "shared_prefs/writeon_lock_fallback.xml").apply {
            parentFile?.mkdirs()
            writeText("<map/>")
        }
        val traversal = File(
            context.filesDir,
            AttachmentArchive.DIRECTORY + "/../../shared_prefs/writeon_lock_fallback.xml",
        )

        assertNull(AttachmentRegistry.resolve(traversal.path))
        // The same file named directly is just as much outside the allowlist.
        assertNull(AttachmentRegistry.resolve(secret.absolutePath))
    }

    @Test
    fun `a sibling directory sharing the root name prefix is refused`() {
        // The prefix check must end in a separator; `attachments-evil` is not `attachments`.
        val sibling = File(context.filesDir, AttachmentArchive.DIRECTORY + "-evil").apply { mkdirs() }
            .resolve("note.jpg")
            .apply { writeBytes(byteArrayOf(1)) }

        assertNull(AttachmentRegistry.resolve(sibling.absolutePath))
    }

    @Test
    fun `an attachment row inside the attachment root resolves`() {
        // REG-05: the row's path is validated like a legacy target; a legitimate file under the
        // app's own attachments directory keeps resolving.
        val file = File(context.filesDir, AttachmentArchive.DIRECTORY).apply { mkdirs() }
            .resolve("registry-inside-root.jpg")
            .apply { writeBytes(byteArrayOf(1)) }
        AttachmentRegistry.put(
            Attachment(id = ID, type = Attachment.TYPE_IMAGE, path = file.absolutePath)
        )
        try {
            assertEquals(
                file.canonicalPath,
                AttachmentRegistry.resolve("attachment://$ID"),
            )
            assertEquals(
                file.canonicalPath,
                AttachmentRegistry.openablePath(file.absolutePath),
            )
        } finally {
            AttachmentRegistry.remove(listOf(ID))
        }
    }

    @Test
    fun `an attachment row inside the audio root resolves`() {
        val file = File(context.filesDir, AttachmentFiles.AUDIO_DIRECTORY).apply { mkdirs() }
            .resolve("registry-inside-audio.m4a")
            .apply { writeBytes(byteArrayOf(1)) }
        AttachmentRegistry.put(
            Attachment(id = AUDIO_ID, type = Attachment.TYPE_AUDIO, path = file.absolutePath)
        )
        try {
            assertEquals(file.canonicalPath, AttachmentRegistry.playablePath(AUDIO_ID))
        } finally {
            AttachmentRegistry.remove(listOf(AUDIO_ID))
        }
    }

    @Test
    fun `an attachment row with a remote or content scheme is refused`() {
        val rows = mapOf(
            REMOTE_ID to "https://attacker.example/track.png",
            CONTENT_ID to "content://media/external/images/media/1",
        )
        rows.forEach { (id, path) ->
            AttachmentRegistry.put(Attachment(id = id, type = Attachment.TYPE_IMAGE, path = path))
        }
        try {
            assertNull(AttachmentRegistry.resolve("attachment://$REMOTE_ID"))
            assertNull(AttachmentRegistry.playablePath(REMOTE_ID))
            assertNull(AttachmentRegistry.resolve("attachment://$CONTENT_ID"))
            assertNull(AttachmentRegistry.playablePath(CONTENT_ID))
            assertNull(AttachmentRegistry.openablePath("https://attacker.example/track.png"))
        } finally {
            AttachmentRegistry.remove(rows.keys)
        }
    }

    @Test
    fun `an attachment row with traversal or an outside-root path is refused`() {
        val traversal = File(
            context.filesDir,
            AttachmentArchive.DIRECTORY + "/../../shared_prefs/writeon_lock_fallback.xml",
        ).absolutePath
        val outside = File(context.filesDir, "registry-legacy-anywhere.jpg").apply {
            writeBytes(byteArrayOf(1))
        }.absolutePath
        AttachmentRegistry.put(Attachment(id = TRAVERSAL_ID, type = Attachment.TYPE_IMAGE, path = traversal))
        AttachmentRegistry.put(Attachment(id = OUTSIDE_ID, type = Attachment.TYPE_IMAGE, path = outside))
        try {
            assertNull(AttachmentRegistry.resolve("attachment://$TRAVERSAL_ID"))
            assertNull(AttachmentRegistry.resolve("attachment://$OUTSIDE_ID"))
            assertNull(AttachmentRegistry.openablePath(outside))
        } finally {
            AttachmentRegistry.remove(listOf(TRAVERSAL_ID, OUTSIDE_ID))
        }
    }

    @Test
    fun `an attachment id still resolves through the registry`() {
        // The id path only gained SEC-06's root check: a row inside the media roots is unchanged.
        val file = File(context.filesDir, AttachmentArchive.DIRECTORY).apply { mkdirs() }
            .resolve("registry-legacy-inside.jpg")
            .apply { writeBytes(byteArrayOf(1)) }
        AttachmentRegistry.put(
            Attachment(id = ID, type = Attachment.TYPE_IMAGE, path = file.absolutePath)
        )
        try {
            assertEquals(file.canonicalPath, AttachmentRegistry.resolve("attachment://$ID"))
        } finally {
            AttachmentRegistry.remove(listOf(ID))
        }
    }

    private companion object {
        const val ID = "sec06-registry-path"
        const val AUDIO_ID = "sec06-registry-audio"
        const val REMOTE_ID = "sec06-registry-remote"
        const val CONTENT_ID = "sec06-registry-content"
        const val TRAVERSAL_ID = "sec06-registry-traversal"
        const val OUTSIDE_ID = "sec06-registry-outside"
    }
}

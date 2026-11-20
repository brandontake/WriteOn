package com.ezpnix.writeon.core.attachment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * N-2: the attachment tree paired with `.bak`.
 *
 * Robolectric's filesystem is real enough to exercise both paths of [AttachmentSnapshots]: the
 * hard-link attempt and the copy fallback (Windows commonly refuses links, which is exactly the
 * portable branch). The tests check the property the undo relies on - the snapshot survives live
 * edits, deletions and additions until the database side says it is no longer needed.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentSnapshotsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun image(name: String) = File(context.filesDir, "attachments/$name")
    private fun audio(name: String) = File(context.filesDir, "audios/$name")

    @Before
    fun clearTrees() {
        listOf("attachments", "audios").forEach { name ->
            File(context.filesDir, name).deleteRecursively()
        }
        AttachmentSnapshots.pruneExcept(context, null)
    }

    @After
    fun removeSnapshots() {
        AttachmentSnapshots.pruneExcept(context, null)
    }

    @Test
    fun `install puts back files the live tree deleted or changed`() {
        image("one.jpg").apply { parentFile?.mkdirs(); writeBytes("original-image".toByteArray()) }
        audio("take.m4a").apply { parentFile?.mkdirs(); writeBytes("original-audio".toByteArray()) }

        val id = AttachmentSnapshots.capture(context)
        assertNotNull(id)
        assertTrue(AttachmentSnapshots.exists(context, id))

        replace(image("one.jpg"), "changed")
        image("new.jpg").apply { parentFile?.mkdirs(); writeBytes("added-after-snapshot".toByteArray()) }
        audio("take.m4a").delete()

        assertTrue(AttachmentSnapshots.install(context, id!!))

        assertArrayEquals("the original image bytes must come back", "original-image".toByteArray(), image("one.jpg").readBytes())
        assertArrayEquals("the deleted recording must come back", "original-audio".toByteArray(), audio("take.m4a").readBytes())
        assertFalse("a file added after the snapshot must not survive the undo", image("new.jpg").exists())
        assertTrue("the snapshot stays until the caller rotates .bak", AttachmentSnapshots.exists(context, id))
    }

    @Test
    fun `an empty snapshot removes every live media file`() {
        File(context.filesDir, "attachments").mkdirs()
        File(context.filesDir, "audios").mkdirs()
        val id = AttachmentSnapshots.capture(context)!!

        image("later.jpg").apply { parentFile?.mkdirs(); writeBytes("later".toByteArray()) }
        audio("later.m4a").apply { parentFile?.mkdirs(); writeBytes("later".toByteArray()) }

        assertTrue(AttachmentSnapshots.install(context, id))
        assertFalse(image("later.jpg").exists())
        assertFalse(audio("later.m4a").exists())
    }

    @Test
    fun `prune keeps only the snapshot named by the sidecar`() {
        val keep = AttachmentSnapshots.capture(context)!!
        val drop = AttachmentSnapshots.capture(context)!!

        AttachmentSnapshots.pruneExcept(context, keep)

        assertTrue(AttachmentSnapshots.exists(context, keep))
        assertFalse(AttachmentSnapshots.exists(context, drop))
    }

    @Test
    fun `a missing snapshot cannot be installed`() {
        assertFalse(AttachmentSnapshots.install(context, "snapshot-does-not-exist"))
        assertEquals(0, AttachmentSnapshots.root(context).listFiles().orEmpty().size)
    }

    @Test
    fun `capture refuses a path-like id`() {
        assertEquals(null, AttachmentSnapshots.capture(context, "../escape"))
    }

    /**
     * Attachment writers never rewrite an existing file in place: a picker copy, a new recording
     * and `MediaVault.encryptInPlace` all create a sibling and replace the directory entry. That is
     * what makes the hard link in [AttachmentSnapshots] a true snapshot. This helper models the
     * same replace, not a truncating write.
     */
    private fun replace(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(text.toByteArray())
        Files.move(
            temp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }
}

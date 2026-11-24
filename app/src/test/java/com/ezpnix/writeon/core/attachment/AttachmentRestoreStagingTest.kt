package com.ezpnix.writeon.core.attachment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.domain.model.Attachment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAT-05/SEC-07: restore staging is durable, and a backup may not read app-private metadata.
 *
 * The install journal already lives under `noBackupFilesDir/attachment-restore`, but the extracted
 * attachment members used to sit in `cacheDir`: a cache eviction between a killed restore and the
 * next launch deleted the journal's sources, and recovery read "source missing" as "already
 * installed". These tests pin the durable location, the both-missing failure, and the path guard
 * on the archive writer.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentRestoreStagingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearWorkTree() {
        // Empty, never delete, the durable tree: it is shared with the API under test.
        AttachmentArchive.workDirectory(context)
            .listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    @Test
    fun `restore staging lives inside the durable work tree`() {
        val staging = AttachmentArchive.stagingDirectory(context, "operation-1")

        assertEquals(
            AttachmentArchive.stagingRoot(context).canonicalPath,
            staging.parentFile?.canonicalPath
        )
        assertTrue(
            "staging must survive a cache eviction",
            staging.path.startsWith(context.noBackupFilesDir.path + File.separator)
        )
        assertFalse(staging.path.startsWith(context.cacheDir.path + File.separator))
    }

    @Test
    fun `each operation gets its own staging directory`() {
        // REG-04: the shared fixed directory let a cloud restore delete a local import's bytes.
        val first = AttachmentArchive.newStagingDirectory(context)
        val second = AttachmentArchive.newStagingDirectory(context)

        assertFalse("two operations must never share a staging tree", first == second)
        assertEquals(AttachmentArchive.stagingRoot(context).canonicalPath, first.parentFile?.canonicalPath)
    }

    @Test
    fun `a plan whose source and target both vanished keeps the journal`() {
        val work = temporaryFolder.newFolder("work")
        val missingSource = File(temporaryFolder.root, "staging/abc.jpg")
        val missingTarget = File(temporaryFolder.newFolder("live"), "abc.jpg")
        val journal = AttachmentArchive.writeInstallJournal(
            work,
            listOf(missingSource to missingTarget)
        )

        val recovered = AttachmentArchive.recoverPendingInstall(work)

        assertEquals("nothing was installed, so this is not a success", 0, recovered)
        assertTrue(
            "an unresolved member keeps the plan for the next start",
            journal.isFile
        )
    }

    @Test
    fun `a source that was already installed keeps the journal removed`() {
        val work = temporaryFolder.newFolder("work")
        val alreadyInstalled = File(temporaryFolder.newFolder("live"), "abc.jpg")
            .apply { writeText("restored-bytes") }
        val journal = AttachmentArchive.writeInstallJournal(
            work,
            listOf(File(temporaryFolder.root, "staging/abc.jpg") to alreadyInstalled)
        )

        assertEquals(0, AttachmentArchive.recoverPendingInstall(work))

        assertFalse(journal.exists())
        assertEquals("restored-bytes", alreadyInstalled.readText())
    }

    @Test
    fun `a row pointing into app-private prefs is not archived`() {
        val appData = context.filesDir.parentFile!!
        val secret = File(appData, "shared_prefs/writeon-leak.xml").apply {
            parentFile?.mkdirs()
            writeText("<map><string name=\"pin\">secret</string></map>")
        }
        val mediaDir = File(context.filesDir, AttachmentArchive.DIRECTORY).apply { mkdirs() }
        val image = File(mediaDir, "legit-${System.nanoTime()}.jpg").apply { writeText("image") }

        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                AttachmentArchive.writeTo(
                    zip,
                    listOf(
                        Attachment(id = "leak", type = Attachment.TYPE_IMAGE, path = secret.absolutePath),
                        Attachment(id = "legit", type = Attachment.TYPE_IMAGE, path = image.absolutePath)
                    ),
                    context
                )
            }
        }.toByteArray()

        val names = entryNames(archive)
        assertFalse("private metadata must never enter a backup", names.any { "leak" in it })
        assertTrue("legitimate media is still archived", names.any { it.startsWith("attachments/legit") })

        secret.delete()
        image.delete()
    }

    private fun entryNames(archive: ByteArray): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zip.nextEntry
            }
        }
        return names
    }
}

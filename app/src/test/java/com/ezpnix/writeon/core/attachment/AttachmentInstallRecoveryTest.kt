package com.ezpnix.writeon.core.attachment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
 * V-8: an install killed between the database swap and its last file move must be completed on
 * the next start. The journal is plain file I/O, so these tests drive the recovery helpers with
 * temporary directories; only [AttachmentArchive.install] itself needs a Context.
 *
 * Since V-8 the work tree is `noBackupFilesDir/attachment-restore/` rather than `cacheDir`: the OS
 * may evict the cache, and a lost journal cannot be replayed. The Context-based entry points are
 * therefore checked against that durable location, while the replay logic keeps taking a directory.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentInstallRecoveryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStartupState() {
        // Empty, never delete, the durable tree: context.noBackupFilesDir is shared with the API
        // under test, so the directory itself must stay.
        AttachmentArchive.workDirectory(context)
            .listFiles().orEmpty().forEach { it.deleteRecursively() }
        File(context.filesDir, "${AttachmentArchive.DIRECTORY}/abc.jpg").delete()
    }

    @Test
    fun `recovery completes the plan and parks a replaced target`() {
        val work = temporaryFolder.newFolder("work")
        val staging = temporaryFolder.newFolder("staging")
        val live = temporaryFolder.newFolder("live")
        val stagedImage = File(staging, "abc.jpg").apply { writeText("new-bytes") }
        val replacedImage = File(live, "abc.jpg").apply { writeText("old-bytes") }
        val stagedAudio = File(staging, "def.m4a").apply { writeText("other-bytes") }
        val newAudio = File(live, "def.m4a")

        val journal = AttachmentArchive.writeInstallJournal(
            work,
            listOf(stagedImage to replacedImage, stagedAudio to newAudio)
        )
        assertTrue("the plan must be durable before any move", journal.isFile)

        val installed = AttachmentArchive.recoverPendingInstall(work)

        assertEquals("both entries are replayed", 2, installed)
        assertEquals("new-bytes", replacedImage.readText())
        assertEquals("other-bytes", newAudio.readText())
        assertFalse("the staged source is consumed", stagedImage.exists())
        val parked = recoveryDirectories(work)
            .flatMap { it.listFiles().orEmpty().toList() }
        assertEquals(
            "the replaced bytes stay recoverable",
            "old-bytes",
            parked.single { it.name == "abc.jpg" }.readText()
        )
        assertFalse("the finished plan is removed", journal.exists())

        assertEquals("a second replay is a no-op", 0, AttachmentArchive.recoverPendingInstall(work))
        assertEquals("new-bytes", replacedImage.readText())
        assertEquals("other-bytes", newAudio.readText())
    }

    @Test
    fun `recovery only installs entries the kill did not finish`() {
        val work = temporaryFolder.newFolder("work")
        val staging = temporaryFolder.newFolder("staging")
        val live = temporaryFolder.newFolder("live")
        // The first entry was already installed before the kill: its staged source is gone and
        // the live target already holds the restored bytes.
        val installedImage = File(live, "abc.jpg").apply { writeText("already-installed") }
        val stagedAudio = File(staging, "def.m4a").apply { writeText("pending-bytes") }
        val oldAudio = File(live, "def.m4a").apply { writeText("old-audio") }

        val journal = AttachmentArchive.writeInstallJournal(
            work,
            listOf(File(staging, "abc.jpg") to installedImage, stagedAudio to oldAudio)
        )

        val installed = AttachmentArchive.recoverPendingInstall(work)

        assertEquals("only the missing entry is moved", 1, installed)
        assertEquals(
            "an already-installed target must not be replaced or parked",
            "already-installed",
            installedImage.readText()
        )
        assertEquals("pending-bytes", oldAudio.readText())
        assertFalse(journal.exists())
    }

    @Test
    fun `a journal left after a successful install is removed untouched`() {
        val work = temporaryFolder.newFolder("work")
        val staging = temporaryFolder.newFolder("staging")
        val live = temporaryFolder.newFolder("live")
        val target = File(live, "abc.jpg").apply { writeText("new-bytes") }
        val journal = AttachmentArchive.writeInstallJournal(
            work,
            listOf(File(staging, "abc.jpg") to target)
        )

        assertEquals("nothing is left to move", 0, AttachmentArchive.recoverPendingInstall(work))

        assertFalse(journal.exists())
        assertEquals("the live file is authoritative", "new-bytes", target.readText())
        assertTrue(
            "a finished plan creates no recovery directory",
            recoveryDirectories(work).isEmpty()
        )
    }

    @Test
    fun `a successful install deletes its journal`() {
        val source = temporaryFolder.newFile("abc.jpg").apply { writeText("new-bytes") }
        val target = File(context.filesDir, "${AttachmentArchive.DIRECTORY}/abc.jpg")
        val journal = AttachmentArchive.installJournal(AttachmentArchive.workDirectory(context))

        val prepared = AttachmentArchive.PreparedAttachments(
            stagedFiles = listOf(source to target),
            pathsById = mapOf("abc" to target.absolutePath)
        )

        assertEquals(1, AttachmentArchive.install(context, prepared))

        assertEquals("new-bytes", target.readText())
        assertFalse("no plan survives a completed install", journal.exists())
        assertFalse("startup sees no pending work", AttachmentArchive.hasPendingInstall(context))
    }

    @Test
    fun `the pending check follows the journal to the durable work directory`() {
        val staging = temporaryFolder.newFolder("staging")
        val live = temporaryFolder.newFolder("live")
        File(staging, "abc.jpg").writeText("new-bytes")
        val journal = AttachmentArchive.writeInstallJournal(
            AttachmentArchive.workDirectory(context),
            listOf(File(staging, "abc.jpg") to File(live, "abc.jpg"))
        )

        assertTrue("a durable plan must be visible to startup", AttachmentArchive.hasPendingInstall(context))

        assertEquals(1, AttachmentArchive.recoverPendingInstall(context))

        assertFalse(journal.exists())
        assertFalse(AttachmentArchive.hasPendingInstall(context))
    }

    @Test
    fun `a plan left in the old cache location is neither replayed nor counted`() {
        val staging = temporaryFolder.newFolder("staging")
        val live = temporaryFolder.newFolder("live")
        val staged = File(staging, "abc.jpg").apply { writeText("new-bytes") }
        // A pre-V-8 build could have died with its journal in cacheDir. That tree is evictable, so
        // it is not trusted: replaying a stale plan could move files for a restore that has long
        // been superseded.
        AttachmentArchive.writeInstallJournal(
            context.cacheDir,
            listOf(staged to File(live, "abc.jpg"))
        )

        assertFalse(AttachmentArchive.hasPendingInstall(context))
        assertEquals(0, AttachmentArchive.recoverPendingInstall(context))
        assertTrue("the stale plan must be left alone", staged.isFile)
    }

    private fun recoveryDirectories(work: File): List<File> =
        work.listFiles { file ->
            file.isDirectory && file.name.startsWith(AttachmentArchive.RECOVERY_PREFIX)
        }.orEmpty().toList()
}

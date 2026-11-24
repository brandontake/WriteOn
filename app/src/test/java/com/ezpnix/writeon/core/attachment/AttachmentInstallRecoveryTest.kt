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
 *
 * TST-06: every replay test injects its own work/staging/live tree through [TemporaryFolder], so
 * nothing depends on the order or timing of other tests. The two remaining Context-bound tests use
 * the production directories Robolectric maps into the sandbox; that OS mapping is the only
 * environment-dependent part - they do not sleep or poll, and [clearStartupState] empties the
 * shared tree before each test rather than replacing it.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentInstallRecoveryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStartupState() {
        // Empty, never delete, the durable tree: context.noBackupFilesDir is shared with the API
        // under test, so the directory itself must stay. Only the entries this class can create are
        // removed; the replay tests never depend on this tree because they pass a TemporaryFolder.
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
    fun `a plan whose database was rolled back restores the old files instead of replaying`() {
        val work = temporaryFolder.newFolder("work")
        val staging = temporaryFolder.newFolder("staging")
        val live = temporaryFolder.newFolder("live")
        val stagedImage = File(staging, "abc.jpg").apply { writeText("restored-archive-bytes") }
        val target = File(live, "abc.jpg").apply { writeText("failed-restore-bytes") }
        val rollback = File(work, "attachments-rollback-test").apply { mkdirs() }
        val savedOld = File(rollback, "abc.jpg").apply { writeText("old-database-bytes") }

        val journal = AttachmentArchive.writeInstallJournal(
            work,
            listOf(stagedImage to target),
            databaseHash = "restored-database-hash",
            rollbackDir = rollback,
        )
        assertTrue(journal.isFile)

        // R2-08/P1: the caller rolled the database back to `.bak` after the install failed. The
        // live database is the old one, so the plan must not run forward over its files.
        val installed = AttachmentArchive.recoverPendingInstall(
            work,
            liveDatabaseHash = "old-database-hash",
        ) { }

        assertEquals("nothing is replayed forward", 0, installed)
        assertTrue("the staged archive member is not installed", stagedImage.isFile)
        assertEquals("the old database's file is back", "old-database-bytes", target.readText())
        assertFalse("the finished undo removes the journal", journal.exists())
        assertFalse("the completely restored rollback tree is removed", savedOld.exists())
    }

    @Test
    fun `a pre-prepare hash with an empty rollback tree still replays forward`() {
        val work = temporaryFolder.newFolder("work-pre-hash")
        val staging = temporaryFolder.newFolder("staging-pre-hash")
        val live = temporaryFolder.newFolder("live-pre-hash")
        val stagedImage = File(staging, "abc.jpg").apply { writeText("restored-archive-bytes") }
        val target = File(live, "abc.jpg")
        // Legacy (pre-R3-01) journal: the hash was taken before `prepare` rewrote the staged DB, so
        // it cannot match the live post-prepare file. No old file was displaced, so there is
        // nothing to roll back and replaying the plan is the only correct direction.
        val journal = AttachmentArchive.writeInstallJournal(
            work,
            listOf(stagedImage to target),
            databaseHash = "pre-prepare-hash",
            rollbackDir = File(work, "attachments-rollback-empty").apply { mkdirs() },
        )
        assertTrue(journal.isFile)

        val installed = AttachmentArchive.recoverPendingInstall(
            work,
            liveDatabaseHash = "post-prepare-hash",
        ) { }

        assertEquals(1, installed)
        assertEquals("restored-archive-bytes", target.readText())
        assertFalse("the staged source is consumed by the replay", stagedImage.exists())
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

    @Test
    fun `a fully restored rollback tree with only the keep marker is retired`() {
        val work = AttachmentArchive.workDirectory(context)
        val staging = temporaryFolder.newFolder("staging-marker")
        val live = temporaryFolder.newFolder("live-marker")
        val stagedImage = File(staging, "abc.jpg").apply { writeText("restored-archive-bytes") }
        val target = File(live, "abc.jpg").apply { writeText("failed-restore-bytes") }
        val rollback = File(work, "attachments-rollback-marker").apply { mkdirs() }
        File(rollback, "abc.jpg").writeText("old-database-bytes")
        // R4-02: the marker is bookkeeping, not a displaced attachment. Before the fix it was
        // counted as "still to restore", so this fully recovered plan never finished.
        File(rollback, AttachmentArchive.ROLLBACK_KEEP_MARKER).writeText("keep for .bak")
        val journal = AttachmentArchive.writeInstallJournal(
            work,
            listOf(stagedImage to target),
            databaseHash = "restored-database-hash",
            rollbackDir = rollback,
        )
        assertTrue(journal.isFile)

        val installed = AttachmentArchive.recoverPendingInstall(
            work,
            liveDatabaseHash = "old-database-hash",
        ) { }

        assertEquals("nothing is replayed forward", 0, installed)
        assertEquals("the old database's file is back", "old-database-bytes", target.readText())
        assertFalse("the finished plan is removed", journal.exists())
        assertFalse(
            "R4-02: startup must see no pending install after the restore finished",
            AttachmentArchive.hasPendingInstall(context)
        )
        assertFalse("the emptied rollback directory is reclaimed", rollback.exists())
    }

    @Test
    fun `a partial rollback restore marks the tree so a later install preserves it`() {
        val work = temporaryFolder.newFolder("work-partial")
        val staging = temporaryFolder.newFolder("staging-partial")
        val live = temporaryFolder.newFolder("live-partial")
        val stagedImage = File(staging, "abc.jpg").apply { writeText("restored-archive-bytes") }
        val restorable = File(live, "abc.jpg").apply { writeText("failed-restore-bytes") }
        // A non-empty directory cannot be deleted by `File.delete()`: this member's restore fails.
        val blocked = File(live, "def.jpg").apply { mkdirs() }
        File(blocked, "child").writeText("in the way")
        val rollback = File(
            AttachmentArchive.workDirectory(context),
            "attachments-rollback-partial",
        ).apply { mkdirs() }
        File(rollback, "abc.jpg").writeText("old-abc")
        File(rollback, "def.jpg").writeText("old-def")
        val journal = AttachmentArchive.writeInstallJournal(
            work,
            listOf(stagedImage to restorable, File(staging, "def.jpg") to blocked),
            databaseHash = "restored-database-hash",
            rollbackDir = rollback,
        )

        assertEquals(
            0,
            AttachmentArchive.recoverPendingInstall(work, liveDatabaseHash = "old-database-hash") { }
        )

        assertEquals("the restorable member was put back", "old-abc", restorable.readText())
        assertTrue("an incomplete restore keeps the journal for a retry", journal.isFile)
        assertTrue(
            "R4-02: the partial restore must mark the tree like an incomplete undo does",
            File(rollback, AttachmentArchive.ROLLBACK_KEEP_MARKER).isFile
        )

        // A later successful install prunes every unmarked rollback round; the marked one pairs
        // with the `.bak` database that still points at these bytes.
        val stagedNew = temporaryFolder.newFile("new-install.jpg").apply { writeText("new") }
        val newTarget = File(AttachmentArchive.restoreDirectory(context), "new-install.jpg")
        newTarget.delete()
        AttachmentArchive.install(
            context,
            AttachmentArchive.PreparedAttachments(
                stagedFiles = listOf(stagedNew to newTarget),
                pathsById = mapOf("new-install" to newTarget.absolutePath),
            )
        )

        assertTrue("the marked rollback tree survives the next install", rollback.isDirectory)
        assertEquals("old-def", File(rollback, "def.jpg").readText())

        newTarget.delete()
        rollback.deleteRecursively()
        journal.delete()
        blocked.deleteRecursively()
        restorable.delete()
    }

    @Test
    fun `a content write does not turn a restored database into a rollback`() {
        val work = temporaryFolder.newFolder("work-generation")
        val staging = temporaryFolder.newFolder("staging-generation")
        val live = temporaryFolder.newFolder("live-generation")
        val stagedImage = File(staging, "abc.jpg").apply { writeText("restored-archive-bytes") }
        val target = File(live, "abc.jpg")
        val rollback = File(work, "attachments-rollback-generation").apply { mkdirs() }
        File(rollback, "old.jpg").writeText("old-database-bytes")
        val journal = AttachmentArchive.writeInstallJournal(
            work,
            listOf(stagedImage to target),
            databaseHash = "hash-at-install",
            rollbackDir = rollback,
            generationToken = 7,
        )
        assertTrue(journal.isFile)

        // R4-03: a note save (or the startup plaintext sweep) changed the live database's bytes,
        // but the generation token is unchanged - this is still the restored database, so the
        // plan must be replayed forward, never mistaken for a rollback.
        val installed = AttachmentArchive.recoverPendingInstall(
            work,
            liveDatabaseHash = "hash-after-a-note-save",
            liveDatabaseToken = 7,
        ) { }

        assertEquals(1, installed)
        assertEquals("restored-archive-bytes", target.readText())
        assertTrue(
            "the old database's file is not moved over the live rows",
            File(rollback, "old.jpg").isFile
        )
        assertFalse(journal.exists())
    }

    @Test
    fun `a different database generation restores the displaced files`() {
        val work = temporaryFolder.newFolder("work-generation-rollback")
        val staging = temporaryFolder.newFolder("staging-generation-rollback")
        val live = temporaryFolder.newFolder("live-generation-rollback")
        val stagedImage = File(staging, "abc.jpg").apply { writeText("restored-archive-bytes") }
        val target = File(live, "abc.jpg").apply { writeText("failed-restore-bytes") }
        val rollback = File(work, "attachments-rollback-generation-rollback").apply { mkdirs() }
        File(rollback, "abc.jpg").writeText("old-database-bytes")
        val journal = AttachmentArchive.writeInstallJournal(
            work,
            listOf(stagedImage to target),
            databaseHash = "hash-at-install",
            rollbackDir = rollback,
            generationToken = 7,
        )

        // The live file is a different generation (the caller put `.bak` back): restore.
        val installed = AttachmentArchive.recoverPendingInstall(
            work,
            liveDatabaseHash = "hash-at-install",
            liveDatabaseToken = 8,
        ) { }

        assertEquals(0, installed)
        assertEquals("old-database-bytes", target.readText())
        assertFalse(journal.exists())
    }

    @Test
    fun `an unreadable generation keeps the journal instead of restoring the rollback tree`() {
        val work = temporaryFolder.newFolder("work-unreadable")
        val staging = temporaryFolder.newFolder("staging-unreadable")
        val live = temporaryFolder.newFolder("live-unreadable")
        val stagedImage = File(staging, "abc.jpg").apply { writeText("restored-archive-bytes") }
        val target = File(live, "abc.jpg").apply { writeText("failed-restore-bytes") }
        val rollback = File(work, "attachments-rollback-unreadable").apply { mkdirs() }
        File(rollback, "abc.jpg").writeText("old-database-bytes")
        val journal = AttachmentArchive.writeInstallJournal(
            work,
            listOf(stagedImage to target),
            // R5-02: an older build recorded the hash before stamping; the live file's hash now
            // describes the stamped bytes. Comparing them would look like a rollback.
            databaseHash = "hash-before-the-stamp",
            rollbackDir = rollback,
            generationToken = 7,
        )

        val installed = AttachmentArchive.recoverPendingInstall(
            work,
            liveDatabaseHash = "hash-after-the-stamp",
            liveDatabaseToken = null,
        ) { }

        assertEquals("no direction may be guessed", 0, installed)
        assertEquals(
            "the live attachment must not be replaced by the rollback copy",
            "failed-restore-bytes",
            target.readText()
        )
        assertEquals("old-database-bytes", File(rollback, "abc.jpg").readText())
        assertTrue("the journal stays for a launch that can identify the database", journal.isFile)
    }

    private fun recoveryDirectories(work: File): List<File> =
        work.listFiles { file ->
            file.isDirectory && file.name.startsWith(AttachmentArchive.RECOVERY_PREFIX)
        }.orEmpty().toList()
}

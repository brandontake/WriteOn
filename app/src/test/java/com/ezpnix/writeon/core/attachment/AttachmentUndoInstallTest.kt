package com.ezpnix.writeon.core.attachment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.constant.DatabaseConst
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAT-10: when an install fails and its rollback also fails, the rollback tree must survive.
 *
 * The old code ignored every rename/copy failure in `undoInstall` and then deleted the rollback
 * directory, destroying the only copy of files the still-live `.bak` database points at. These
 * tests inject the failing move/restore primitives at the internal seam.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentUndoInstallTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an incomplete undo keeps the rollback directory and its files`() {
        val workDir = AttachmentArchive.workDirectory(context)
        val before = rollbackDirectories(workDir).map { it.name }
        // R3-01: the plan's database hash must describe the *live* database the caller swapped in,
        // not a pre-prepare staged copy. `install` hashes it at this point; a stale hash would make
        // startup mistake a normal forward install for a rollback.
        val liveDb = context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME).apply {
            parentFile?.mkdirs()
            writeBytes("live database after swap".toByteArray())
        }

        val live = temporaryFolder.newFolder("live")
        val installedTarget = File(live, "new-a.jpg")
        val replacedTarget = File(live, "replaced-b.jpg").apply { writeText("old b") }
        val stagedDir = temporaryFolder.newFolder("staged")
        val stagedA = File(stagedDir, "new-a.jpg").apply { writeText("new a") }
        val stagedB = File(stagedDir, "new-b.jpg").apply { writeText("new b") }

        val prepared = AttachmentArchive.PreparedAttachments(
            stagedFiles = listOf(stagedA to installedTarget, stagedB to replacedTarget),
            pathsById = mapOf("a" to installedTarget.absolutePath, "b" to replacedTarget.absolutePath),
        )

        // The first file installs; the second install move fails. The rollback then also fails,
        // which is exactly the state that must not delete the parked originals.
        val failure = assertThrows(IOException::class.java) {
            AttachmentArchive.install(
                context = context,
                prepared = prepared,
                move = { source, target ->
                    if (source == stagedB) {
                        false
                    } else {
                        source.renameTo(target)
                    }
                },
                restore = { _, _ -> false },
            )
        }

        val rollback = rollbackDirectories(workDir).filter { it.name !in before }
        assertTrue("a new rollback directory is expected", rollback.size == 1)
        val kept = rollback.single()
        assertTrue("the rollback directory must survive a failed undo", kept.isDirectory)
        assertTrue(
            "the displaced live file must still be there: ${failure.message}",
            kept.walkTopDown().any { it.isFile && it.name.contains("replaced-b") }
        )
        assertTrue(
            "the install journal must stay replayable",
            AttachmentArchive.hasPendingInstall(context)
        )
        assertTrue(
            "R2-08: the kept directory must be marked so later installs do not prune it",
            File(kept, AttachmentArchive.ROLLBACK_KEEP_MARKER).isFile
        )
        assertEquals(
            "R3-01: the journal must name the live database, not the pre-prepare copy",
            sha256(liveDb),
            AttachmentArchive.installJournalDatabaseHash(workDir)
        )

        // Do not leak the kept state into other tests sharing this work tree.
        kept.deleteRecursively()
        AttachmentArchive.installJournal(workDir).delete()
        installedTarget.delete()
        replacedTarget.delete()
        liveDb.delete()
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `a later successful install preserves a marked rollback directory`() {
        val workDir = AttachmentArchive.workDirectory(context)
        val preserved = File(workDir, "attachments-rollback-preserved").apply { mkdirs() }
        File(preserved, AttachmentArchive.ROLLBACK_KEEP_MARKER).writeText("keep for .bak")
        val preservedFile = File(preserved, "old.jpg").apply { writeText("old bytes") }
        val pruneCandidate = File(workDir, "attachments-rollback-stale").apply { mkdirs() }

        val stagedDir = temporaryFolder.newFolder("staged-ok")
        val staged = File(stagedDir, "new.jpg").apply { writeText("new bytes") }
        val target = File(AttachmentArchive.restoreDirectory(context), "new.jpg")
        target.delete()

        AttachmentArchive.install(
            context,
            AttachmentArchive.PreparedAttachments(
                stagedFiles = listOf(staged to target),
                pathsById = mapOf("new" to target.absolutePath),
            )
        )

        assertTrue("R2-08: a marked rollback tree must survive pruning", preserved.isDirectory)
        assertTrue(preservedFile.isFile)
        assertFalse("an unmarked older rollback round is still pruned", pruneCandidate.exists())

        preserved.deleteRecursively()
        target.delete()
    }

    @Test
    fun `a marker-only rollback tree is reclaimed by a later install`() {
        // R4-02/R5-06: a marker with no displaced file left is stale bookkeeping. Keeping the
        // directory forever leaks storage and made the "is a .bak paired with this?" question
        // unanswerable; the next successful install reclaims it.
        val workDir = AttachmentArchive.workDirectory(context)
        val stale = File(workDir, "attachments-rollback-marker-only").apply { mkdirs() }
        File(stale, AttachmentArchive.ROLLBACK_KEEP_MARKER).writeText("keep for .bak")

        val stagedDir = temporaryFolder.newFolder("staged-marker-only")
        val staged = File(stagedDir, "fresh.jpg").apply { writeText("new bytes") }
        val target = File(AttachmentArchive.restoreDirectory(context), "fresh.jpg")
        target.delete()

        AttachmentArchive.install(
            context,
            AttachmentArchive.PreparedAttachments(
                stagedFiles = listOf(staged to target),
                pathsById = mapOf("fresh" to target.absolutePath),
            )
        )

        assertFalse(
            "a tree with nothing but the marker has nothing left to pair with .bak",
            stale.exists()
        )
        target.delete()
    }

    @Test
    fun `a verified undo reports success`() {
        val live = temporaryFolder.newFolder("live-ok")
        val target = File(live, "a.jpg")
        val saved = temporaryFolder.newFile("saved-a.jpg").apply { writeText("old") }

        assertTrue(AttachmentArchive.undoInstall(emptyList(), listOf(target to saved)))
        assertTrue(target.isFile)
        assertFalse("a successful rollback consumes the saved copy", saved.exists())
    }

    private fun rollbackDirectories(workDir: File): List<File> =
        workDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("attachments-rollback-") }
}

package com.ezpnix.writeon.core.attachment

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DAT-05/SEC-11: abandoned per-operation staging trees are removed at startup, but a directory
 * the pending install journal still names is kept so a paused replay can finish.
 */
class AttachmentStagingCleanupTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `stale unreferenced staging is removed while the journal source survives`() {
        val stagingRoot = temporaryFolder.newFolder("staging")
        val work = temporaryFolder.newFolder("work")

        val active = File(stagingRoot, "operation-active/attachments").apply { mkdirs() }
        val activeSource = File(active, "active.jpg").apply { writeText("needed by the journal") }
        val abandoned = File(stagingRoot, "operation-abandoned").apply {
            mkdirs()
            File(this, "leftover.jpg").writeText("plaintext bytes")
        }
        val fresh = File(stagingRoot, "operation-fresh").apply { mkdirs() }
        val unrelated = File(stagingRoot, "not-a-staging-dir.txt").apply { writeText("keep me") }

        val now = System.currentTimeMillis()
        val old = now - 60 * 60 * 1000
        assertTrue(abandoned.setLastModified(old))
        assertTrue(active.parentFile!!.setLastModified(old))

        val journal = AttachmentArchive.writeInstallJournal(
            work,
            listOf(activeSource to File(temporaryFolder.root, "live/active.jpg"))
        )

        val removed = AttachmentArchive.cleanupAbandonedStaging(
            stagingRoot = stagingRoot,
            installJournal = journal,
            now = now,
            maxAgeMillis = 60_000,
        )

        assertFalse("an abandoned operation's plaintext tree must go", abandoned.exists())
        assertTrue("a fresh operation may be in flight", fresh.exists())
        assertTrue("the pending journal still needs its source", activeSource.isFile)
        assertTrue(unrelated.isFile)
        org.junit.Assert.assertEquals(1, removed)
    }

    @Test
    fun `without a journal every stale operation directory is removed`() {
        val stagingRoot = temporaryFolder.newFolder("staging")
        val stale = File(stagingRoot, "operation-old").apply {
            mkdirs()
            File(this, "db-copy").writeText("plaintext")
            setLastModified(System.currentTimeMillis() - 60 * 60 * 1000)
        }

        val removed = AttachmentArchive.cleanupAbandonedStaging(
            stagingRoot = stagingRoot,
            installJournal = null,
            now = System.currentTimeMillis(),
            maxAgeMillis = 60_000,
        )

        assertFalse(stale.exists())
        org.junit.Assert.assertEquals(1, removed)
    }

    @Test
    fun `a staging tree named by the restore journal survives the age gate`() {
        val stagingRoot = temporaryFolder.newFolder("staging")
        val needed = File(stagingRoot, "operation-restore").apply { mkdirs() }
        val neededFile = File(needed, "attachments/a.jpg").apply {
            parentFile!!.mkdirs()
            writeText("the live database points here")
        }
        assertTrue(needed.setLastModified(System.currentTimeMillis() - 60 * 60 * 1000))

        val removed = AttachmentArchive.cleanupAbandonedStaging(
            stagingRoot = stagingRoot,
            installJournal = null,
            now = System.currentTimeMillis(),
            maxAgeMillis = 60_000,
            // R2-09: RestoreJournal.pendingStagingDirectories() feeds this set.
            protectedDirectories = setOf(needed.canonicalPath),
        )

        assertTrue(neededFile.isFile)
        org.junit.Assert.assertEquals(0, removed)
    }
}

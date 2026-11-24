package com.ezpnix.writeon.data.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SYN-03: the encrypted archive is built only when it is actually going to be uploaded.
 *
 * `writeEncryptedArchive()` copies the database, zips it and re-encrypts every recording. It used
 * to run before the remote state was read, so a retry that was skipped by the conflict dedupe
 * still paid that whole cost. The ordering itself lives in `doWork` (it reads DataStore and runs a
 * worker), which is not reachable from a JVM test; this guard pins the source order instead:
 * remote read -> conflict dedupe -> publication decision -> the single lazy build.
 */
class CloudBackupWorkerArchiveOrderingTest {

    @Test
    fun `the conflict dedupe and publication decision precede the only archive build`() {
        val source = workerSource()

        val build = "writeEncryptedArchive(archive, passphrase)"
        assertEquals(
            "doWork must build through the one lazy builder, not eagerly",
            1,
            source.split(build).size - 1,
        )

        val builderStart = source.indexOf("suspend fun archiveOnce()")
        assertTrue("the only build must live in the lazy builder", builderStart >= 0)
        assertTrue(source.indexOf(build) > builderStart)

        val remoteRead = source.indexOf("val remote = fetchRemote(canonicalUrl, authorization)")
        val dedupe = source.indexOf("if (existingConflictCopy(remote) != null)")
        assertTrue("the remote state must be read before the dedupe", remoteRead in 0 until dedupe)

        val firstBuildAfterDedupe = source.indexOf("archiveOnce()", dedupe)
        assertTrue(
            "a retry that finds the conflict copy already parked must not build the archive",
            firstBuildAfterDedupe > dedupe,
        )

        val decision = source.indexOf("when (val publication = WebDavPreconditions.decide(")
        assertTrue(decision >= 0)
        val canonicalBuild = source.indexOf("archiveOnce()", decision)
        assertTrue(
            "the canonical upload must build after the publication decision refused or allowed it",
            canonicalBuild > decision,
        )

        // A first-time conflict copy still shares that one build: the branch passes the already
        // read state into keepConflictCopy instead of fetching again or building a second archive.
        val conflictCall = source.indexOf("keepConflictCopy(canonicalUrl, authorization, archive, remoteState = remote)")
        assertTrue(conflictCall > firstBuildAfterDedupe)
    }

    /**
     * Gradle runs unit tests with the module directory (`app/`) as the working directory, while an
     * IDE may use the project root. Walk up from either instead of hard-coding one layout.
     */
    private fun workerSource(): String {
        val relative = "src/main/java/com/ezpnix/writeon/data/backup/CloudBackupWorker.kt"
        val anchors = generateSequence(File("").absoluteFile) { it.parentFile }.take(5)
        for (anchor in anchors) {
            val candidates = listOf(
                File(anchor, relative),
                File(anchor, "app/$relative"),
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText() }
        }
        throw AssertionError("Could not find CloudBackupWorker.kt from ${File("").absolutePath}")
    }
}

package com.ezpnix.writeon.core.util

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Regression guard for the backup exclusion lists.
 *
 * Both files are negative lists: anything under `files/` that is not named there travels to Google
 * Drive and to a new device. That is how `attachments-quarantine/` - complete pictures and
 * recordings that reconcile parked instead of deleting - used to leave the device (SEC-1). Each
 * directory the app creates under `filesDir` is listed here, so adding one without adding its
 * exclude fails this test instead of silently uploading user media.
 */
class BackupRulesTest {

    private companion object {
        /**
         * Every directory the app creates directly under `filesDir`. Keep this in sync with
         * `AttachmentFiles`, DataStore and the widget state path; a name here that is not excluded
         * in both rule sets means its contents are backed up.
         */
        val APP_CREATED_FILES_DIRECTORIES = listOf(
            "datastore",
            "audios",
            "attachments",
            "attachments-quarantine",
            "BackupFolder",
            "fonts",
            "glance"
        )
    }

    @Test
    fun `backup rules exclude every app created files directory`() {
        val document = parse("backup_rules.xml")
        val root = document.documentElement
        assertEquals("full-backup-content", root.tagName)

        assertExclusions("backup_rules.xml", root)
    }

    @Test
    fun `data extraction rules exclude every app created files directory in both sections`() {
        val document = parse("data_extraction_rules.xml")
        assertEquals("data-extraction-rules", document.documentElement.tagName)

        val cloudBackup = section(document, "cloud-backup")
        val deviceTransfer = section(document, "device-transfer")
        assertExclusions("cloud-backup", cloudBackup)
        assertExclusions("device-transfer", deviceTransfer)
    }

    private fun section(document: Document, tag: String): Element {
        val nodes = document.getElementsByTagName(tag)
        assertEquals("expected exactly one <$tag>", 1, nodes.length)
        return nodes.item(0) as Element
    }

    private fun assertExclusions(label: String, section: Element) {
        val excluded = exclusionsIn(section)
        for (directory in APP_CREATED_FILES_DIRECTORIES) {
            assertTrue(
                "$label must exclude domain=file path=$directory/ (found $excluded)",
                "file:$directory" in excluded
            )
        }
        // The database is the one thing the app's own backup packages; the Keystore copy is
        // unreadable elsewhere. Preferences hold the lock and WebDAV credentials.
        assertTrue("$label must exclude domain=database", "database:." in excluded)
        assertTrue("$label must exclude domain=sharedpref", "sharedpref:." in excluded)
    }

    private fun exclusionsIn(element: Element): Set<String> {
        val nodes = element.getElementsByTagName("exclude")
        val excluded = linkedSetOf<String>()
        for (index in 0 until nodes.length) {
            val exclude = nodes.item(index) as Element
            excluded += key(exclude.getAttribute("domain"), exclude.getAttribute("path"))
        }
        return excluded
    }

    /** `<exclude domain="file" path="audios/" />` and `path="audios"` are the same exclusion. */
    private fun key(domain: String, path: String): String = "$domain:${path.trimEnd('/')}"

    private fun parse(name: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(rulesFile(name))
    }

    /**
     * Gradle runs unit tests with the module directory (`app/`) as the working directory, while an
     * IDE may use the project root. Walk up from either instead of hard-coding one layout.
     */
    private fun rulesFile(name: String): File {
        val anchors = generateSequence(File("").absoluteFile) { it.parentFile }.take(5)
        for (anchor in anchors) {
            val candidates = listOf(
                File(anchor, "src/main/res/xml/$name"),
                File(anchor, "app/src/main/res/xml/$name")
            )
            candidates.firstOrNull { it.isFile }?.let { return it }
        }
        throw AssertionError(
            "Could not find res/xml/$name from ${File("").absolutePath}"
        )
    }
}

package com.ezpnix.writeon.core.util

import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.attachment.AttachmentFiles
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.data.backup.WebDavPrefs
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
 *
 * TST-03: the expected list is no longer a hand-copy that can drift from production. Media names
 * come from [AttachmentArchive] and [AttachmentFiles], the database name from [DatabaseConst], and
 * the DataStore/private-constant entries are documented below with their owning file. The
 * extra-directory test proves the checker really reports an uncovered child.
 */
class BackupRulesTest {

    private companion object {
        /**
         * The filesDir directories whose production constant is private, with the owner that would
         * have to be updated. Widening a production constant just for a test would grow the public
         * surface, so the name is repeated here together with the source of truth it must match.
         */
        val PRIVATE_CONSTANT_DIRECTORIES = mapOf(
            // Every `preferencesDataStore` file lives in `files/datastore/<store>.preferences_pb`;
            // this directory name is fixed by androidx.datastore, not by an app constant.
            "datastore" to "androidx.datastore preferences directory",
            "BackupFolder" to "data/backup/BackupWorker.kt BACKUP_DIRECTORY",
            "fonts" to "presentation/theme/LocalNoteFont.kt FONT_DIR",
            "glance" to "widget/NotesWidget.kt widget-state path",
        )

        /**
         * Every directory the app creates directly under `filesDir`. A name here that is not
         * excluded in both rule sets means its contents are backed up.
         */
        val APP_CREATED_FILES_DIRECTORIES: Set<String> = linkedSetOf(
            AttachmentArchive.DIRECTORY,
            AttachmentFiles.AUDIO_DIRECTORY,
            AttachmentFiles.QUARANTINE_DIRECTORY,
        ).apply {
            addAll(PRIVATE_CONSTANT_DIRECTORIES.keys)
        }
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

    @Test
    fun `a files directory that the rules do not exclude is detected`() {
        val excluded = exclusionsIn(parse("backup_rules.xml").documentElement)

        assertTrue(
            "the known directories must all be covered before this test can prove anything",
            uncoveredDirectories(APP_CREATED_FILES_DIRECTORIES, excluded).isEmpty()
        )
        assertEquals(
            "the checker must report a new filesDir child that no exclude names",
            setOf("future-media"),
            uncoveredDirectories(APP_CREATED_FILES_DIRECTORIES + "future-media", excluded)
        )
    }

    /**
     * The DataStore files are covered by their directory, never by a store name - a rule that
     * names one store would silently leave every other preference file in the backup.
     */
    @Test
    fun `the datastore exclusion covers every preferences store`() {
        assertTrue(
            "the fixed DataStore directory must be excluded, it holds ${WebDavPrefs.STORE_NAME} " +
                "and the settings/placeholder stores",
            "datastore" in PRIVATE_CONSTANT_DIRECTORIES
        )
        val excluded = exclusionsIn(parse("backup_rules.xml").documentElement)
        assertTrue("file:datastore must be excluded", "file:datastore" in excluded)
    }

    /**
     * The hand-maintained set above is only as good as its last update. This scan catches a new
     * *literal* `filesDir/<name>` added to production without touching the set (and therefore
     * without touching the rules); names built from a private constant still have to be added to
     * [PRIVATE_CONSTANT_DIRECTORIES], which is why that map names its owner.
     */
    @Test
    fun `every filesDir literal in production source is in the expected directory set`() {
        val literalDirectory = Regex("""filesDir\s*,\s*"([^"/]+)""")
        val found = productionSourceFiles()
            .flatMap { source ->
                literalDirectory.findAll(source.readText()).map { it.groupValues[1] }.toList()
            }
            .toSet()

        assertTrue("the source scan must find at least the literal media roots", found.isNotEmpty())
        assertEquals(
            "production writes filesDir children that no exclude names",
            emptySet<String>(),
            found - APP_CREATED_FILES_DIRECTORIES
        )
    }

    private fun section(document: Document, tag: String): Element {
        val nodes = document.getElementsByTagName(tag)
        assertEquals("expected exactly one <$tag>", 1, nodes.length)
        return nodes.item(0) as Element
    }

    private fun assertExclusions(label: String, section: Element) {
        val excluded = exclusionsIn(section)
        val uncovered = uncoveredDirectories(APP_CREATED_FILES_DIRECTORIES, excluded)
        assertTrue(
            "$label must exclude every app-created files directory, missing: $uncovered " +
                "(found $excluded)",
            uncovered.isEmpty()
        )
        // The database is the one thing the app's own backup packages; the Keystore copy is
        // unreadable elsewhere. Preferences hold the lock and WebDAV credentials.
        assertTrue(
            "$label must exclude domain=database (${DatabaseConst.NOTES_DATABASE_FILE_NAME})",
            "database:." in excluded
        )
        assertTrue("$label must exclude domain=sharedpref", "sharedpref:." in excluded)
    }

    /** Directories whose `file:` exclusion is missing from [excluded]. */
    private fun uncoveredDirectories(directories: Set<String>, excluded: Set<String>): Set<String> =
        directories.filterTo(linkedSetOf()) { directory -> "file:$directory" !in excluded }

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

    /** Production Kotlin files, found the same way as [rulesFile] so both IDE and Gradle work. */
    private fun productionSourceFiles(): List<File> {
        val anchors = generateSequence(File("").absoluteFile) { it.parentFile }.take(5)
        for (anchor in anchors) {
            val root = listOf(
                File(anchor, "src/main/java"),
                File(anchor, "app/src/main/java")
            ).firstOrNull { it.isDirectory } ?: continue
            return root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        }
        throw AssertionError(
            "Could not find src/main/java from ${File("").absolutePath}"
        )
    }
}

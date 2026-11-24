package com.ezpnix.writeon.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SYN-03/SYN-04: conflict notices survive a successful upload, and a conflict copy is only
 * uploaded once per remote state.
 */
@RunWith(RobolectricTestRunner::class)
class WebDavBackupStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val copyUrl = "https://dav.example/notes-backup-encrypted.conflict-20261120-101500-123.zip"

    @Before
    fun clearState() {
        WebDavBackupState.clearConflicts(context)
    }

    @Test
    fun `a successful canonical upload does not erase an unresolved conflict`() {
        WebDavBackupState.recordConflict(context, copyUrl, "etag:\"abc\"")

        // SYN-04: this used to call clearConflicts() and remove the only in-app handle on the
        // copy, even though the copy was still unmerged on the server.
        WebDavBackupState.recordSuccess(context)

        assertEquals(listOf(copyUrl), WebDavBackupState.conflicts(context))
    }

    @Test
    fun `only an explicit dismissal removes that one notice`() {
        val other = "https://dav.example/notes-backup-encrypted.conflict-20261120-111500-456.zip"
        WebDavBackupState.recordConflict(context, copyUrl, "etag:\"abc\"")
        WebDavBackupState.recordConflict(context, other, "etag:\"def\"")

        WebDavBackupState.dismissConflict(context, copyUrl)

        assertEquals(listOf(other), WebDavBackupState.conflicts(context))
        assertNull(
            "a dismissed copy must not suppress the next conflict for the same validator",
            WebDavBackupState.conflictUrlForValidator(context, "etag:\"abc\""),
        )
        assertEquals(other, WebDavBackupState.conflictUrlForValidator(context, "etag:\"def\""))
    }

    @Test
    fun `restoring one parked copy resolves only that notice`() {
        val other = "https://dav.example/notes-backup-encrypted.conflict-20261120-121500-789.zip"
        WebDavBackupState.recordConflict(context, copyUrl, "etag:\"abc\"")
        WebDavBackupState.recordConflict(context, other, "etag:\"def\"")

        // WebDAVRestoreWorker calls exactly this after a successful restore of the conflict URL;
        // the other parked copy stays listed because it has not been restored or acknowledged.
        WebDavBackupState.dismissConflict(context, copyUrl)

        assertEquals(listOf(other), WebDavBackupState.conflicts(context))
    }

    @Test
    fun `a validator maps to the still-listed conflict copy`() {
        WebDavBackupState.recordConflict(context, copyUrl, "etag:\"abc\"")

        assertEquals(copyUrl, WebDavBackupState.conflictUrlForValidator(context, "etag:\"abc\""))
        assertNull(WebDavBackupState.conflictUrlForValidator(context, "etag:\"other\""))
    }

    @Test
    fun `the list is capped but the newest entries and their validators stay`() {
        repeat(WebDavBackupState.MAX_REMEMBERED_CONFLICTS + 2) { index ->
            WebDavBackupState.recordConflict(
                context,
                "https://dav.example/copy-$index.zip",
                "etag:\"$index\"",
            )
        }

        val listed = WebDavBackupState.conflicts(context)
        assertEquals(WebDavBackupState.MAX_REMEMBERED_CONFLICTS, listed.size)
        assertTrue("oldest entries fall off", listed.none { it.endsWith("copy-0.zip") })
        assertEquals(
            "https://dav.example/copy-${WebDavBackupState.MAX_REMEMBERED_CONFLICTS + 1}.zip",
            WebDavBackupState.conflictUrlForValidator(
                context,
                "etag:\"${WebDavBackupState.MAX_REMEMBERED_CONFLICTS + 1}\"",
            ),
        )
    }

    @Test
    fun `a dismissed notice removes its validator entry from the preference file`() {
        val other = "https://dav.example/notes-backup-encrypted.conflict-20261120-131500-000.zip"
        WebDavBackupState.recordConflict(context, copyUrl, "etag:\"abc\"")
        // Keep a second entry: with only one conflict, the production code removes the whole
        // preference key, and the old assertion nested under `stored != null` skipped itself.
        WebDavBackupState.recordConflict(context, other, "etag:\"def\"")

        WebDavBackupState.dismissConflict(context, copyUrl)

        // R2-10: the map is URL -> validator; filtering the values left a dead URL entry behind.
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("conflict_validators", null)
        assertTrue("the remaining notice must keep the persisted map: $stored", stored != null)
        val validators = org.json.JSONObject(stored!!)
        assertTrue(
            "the dismissed URL must not stay in the persisted map: $stored",
            !validators.has(copyUrl)
        )
        assertTrue(
            "the other notice's validator must stay: $stored",
            validators.has(other)
        )
    }

    @Test
    fun `a changed local library is not suppressed by the old conflict copy`() {
        val remote = WebDavBackupState.conflictFingerprint(
            etag = "etag:\"abc\"",
            lastModified = null,
            localContent = "100:200:300:400",
        )!!
        WebDavBackupState.recordConflict(context, copyUrl, remote)

        assertEquals(copyUrl, WebDavBackupState.conflictUrlForValidator(context, remote))
        // R2-01/SYN-03: the same remote with a new note on this device is a new archive; the old
        // dedupe entry must not skip uploading it forever.
        assertNull(
            WebDavBackupState.conflictUrlForValidator(
                context,
                WebDavBackupState.conflictFingerprint(
                    etag = "etag:\"abc\"",
                    lastModified = null,
                    localContent = "100:200:300:900",
                )!!
            )
        )
    }

    private companion object {
        const val PREFS_NAME = "webdav_backup_state"
    }
}

package com.ezpnix.writeon.presentation.screens.edit.model

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PERF-05: the draft lifecycle outside the editor.
 *
 * Three gaps are pinned here: permanent deletion never removed `<id>.draft` (no editor exists at
 * that point), a note that became a vault note between the debounce and the write could still land
 * plaintext on disk, and the vault rule itself must delete a pending draft instead of only skipping
 * the new one.
 */
@RunWith(RobolectricTestRunner::class)
class EditDraftLifecycleTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(): EditDraftStore =
        EditDraftStore(File(temporaryFolder.root, DRAFT_DIRECTORY))

    @Test
    fun `permanent delete clears the draft file`() {
        val application: Application = ApplicationProvider.getApplicationContext()
        // Exercise the helper against the same location production uses.
        val productionStore = EditDraftStore(File(application.noBackupFilesDir, DRAFT_DIRECTORY))
        productionStore.write(77, "title", "unsaved body")
        assertTrue(File(application.noBackupFilesDir, "$DRAFT_DIRECTORY/77.draft").isFile)

        EditDraftStore.clearFor(application, 77)

        assertNull("permanent deletion must not leave plaintext behind", productionStore.read(77))
        assertFalse(File(application.noBackupFilesDir, "$DRAFT_DIRECTORY/77.draft").exists())
    }

    @Test
    fun `a note that became a vault note clears the pending draft instead of writing`() {
        val store = store()
        store.write(5, "title", "old plain draft")

        // The flag flips while the debounce/dispatch hop is in flight.
        val written = store.writeIfStillAllowed(5, "title", "new plain draft") { true }

        assertFalse(written)
        assertNull("SEC-7: no plaintext may survive the vault transition", store.read(5))
    }

    @Test
    fun `a write that loses the race to the vault flag leaves no plaintext`() {
        val store = store()
        // The pre-write check saw a plain note; the lambda read on the IO dispatcher sees the
        // already-flipped flag, which is exactly the race the extra check exists for.
        var isEncrypted = true
        val written = store.writeIfStillAllowed(6, "title", "raced text") { isEncrypted }
        assertFalse(written)
        assertNull(store.read(6))

        isEncrypted = false
        assertTrue(store.writeIfStillAllowed(6, "title", "plain text") { isEncrypted })
        assertTrue(store.read(6)?.body == "plain text")
    }

    @Test
    fun `the plain case still writes the draft`() {
        val store = store()
        assertTrue(store.writeIfStillAllowed(8, "title", "body") { false })
        assertTrue(store.read(8)?.body == "body")
    }
}

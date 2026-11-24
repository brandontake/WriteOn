package com.ezpnix.writeon.presentation.screens.edit.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PERF-05: the draft store that keeps note bodies out of the saved-instance Bundle.
 *
 * Every case runs on a temporary folder - the class takes a directory and uses no Android API -
 * so write/read/clear, the size cap, corruption handling and the vault rule are pinned without an
 * emulator. The Vault check lives in `draftAllowed`, which is the predicate EditViewModel uses
 * before it ever touches the store.
 */
class EditDraftStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newStore(): EditDraftStore =
        EditDraftStore(File(temporaryFolder.root, "edit-drafts"))

    @Test
    fun `write and read round trip text and CRLF`() {
        val store = newStore()
        val name = "标题 with a \uD83D\uDCDD emoji"
        val body = "first line\r\nsecond line\n\n\u0000 inside"

        assertTrue(store.write(41, name, body))

        val draft = store.read(41)!!
        assertEquals(name, draft.name)
        assertEquals(body, draft.body)
    }

    @Test
    fun `drafts are per note id and clear removes only one`() {
        val store = newStore()
        store.write(1, "one", "body one")
        store.write(2, "two", "body two")

        store.clear(1)

        assertNull(store.read(1))
        assertEquals("body two", store.read(2)?.body)
    }

    @Test
    fun `a body over the cap is not stored, and an older draft is cleared`() {
        val store = newStore()
        store.write(7, "name", "older body")

        val tooLarge = "x".repeat(1024 * 1024 + 1)
        assertFalse(store.write(7, "name", tooLarge))

        assertNull("no partial draft may be offered back", store.read(7))
    }

    @Test
    fun `a corrupted file is ignored and removed`() {
        val directory = File(temporaryFolder.root, "edit-drafts").apply { mkdirs() }
        val corrupted = File(directory, "5.draft").apply { writeText("not a draft file") }

        assertNull(EditDraftStore(directory).read(5))
        assertFalse("a corrupt draft is deleted instead of retried forever", corrupted.exists())
    }

    @Test
    fun `a file with extra trailing bytes is treated as corrupted`() {
        val store = newStore()
        store.write(9, "name", "body")
        val file = File(File(temporaryFolder.root, "edit-drafts"), "9.draft")
        file.appendBytes(byteArrayOf(1, 2, 3))

        assertNull(store.read(9))
    }

    @Test
    fun `clearing a missing draft is a no-op`() {
        newStore().clear(123)
    }

    @Test
    fun `vault notes are never allowed a draft`() {
        assertFalse("SEC-7: a vault body must not reach a plaintext file", draftAllowed(isEncrypted = true))
        assertTrue(draftAllowed(isEncrypted = false))
    }
}

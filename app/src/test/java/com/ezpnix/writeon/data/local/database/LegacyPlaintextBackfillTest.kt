package com.ezpnix.writeon.data.local.database

import com.ezpnix.writeon.data.local.dao.LegacyNoteRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * SEC-5: the policy behind the startup sweep that seals rows stored before encryption existed.
 *
 * The sweep rewrites the only copy of a user's notes, so its two dangerous mistakes are pinned here
 * in plain JVM tests (no Room, no Keystore):
 * - treating a real `w1:` ciphertext as plain text and re-sealing it - that would replace
 *   text this process cannot read with new ciphertext, i.e. permanent loss;
 * - writing anything at all when sealing throws, or sealing only one of a row's two columns.
 *
 * The Keystore itself cannot fail on demand here, so the failure path is exercised through
 * [LegacyPlaintextBackfill.seal]'s `seal` parameter - the same seam production fills with
 * `NoteCrypto::seal`. The structural `w1:` test those decisions rest on is pinned by
 * `NoteCryptoFormatTest`; what still needs a device is (a) a real seal producing `w1:` and being
 * readable again, and (b) the conditional write-back SQL reaching (or skipping) the row, which
 * `LegacyNoteRow`'s projection and `EncryptedNoteDao.sealLegacyPlaintextRows` own.
 */
class LegacyPlaintextBackfillTest {

    @Test
    fun `both plain columns are sealed`() {
        val row = LegacyNoteRow(id = 1, name = "groceries", description = "milk")

        val outcome = LegacyPlaintextBackfill.seal(row) { "sealed($it)" }

        assertEquals(LegacyRowSeal.Sealed(name = "sealed(groceries)", description = "sealed(milk)"), outcome)
    }

    @Test
    fun `a plain body next to a sealed name seals only the body`() {
        val sealedName = sealedPayload()
        val row = LegacyNoteRow(id = 2, name = sealedName, description = "milk")

        val sealedInputs = mutableListOf<String>()
        val outcome = LegacyPlaintextBackfill.seal(row) { sealedInputs += it; "sealed($it)" }

        assertEquals(listOf("milk"), sealedInputs)
        assertEquals(
            LegacyRowSeal.Sealed(name = sealedName, description = "sealed(milk)"),
            outcome
        )
    }

    @Test
    fun `a plain name next to a sealed body seals only the name`() {
        val sealedBody = sealedPayload()
        val row = LegacyNoteRow(id = 3, name = "groceries", description = sealedBody)

        val sealedInputs = mutableListOf<String>()
        val outcome = LegacyPlaintextBackfill.seal(row) { sealedInputs += it; "sealed($it)" }

        assertEquals(listOf("groceries"), sealedInputs)
        assertEquals(
            LegacyRowSeal.Sealed(name = "sealed(groceries)", description = sealedBody),
            outcome
        )
    }

    @Test
    fun `w1 hello is plain text and does get sealed`() {
        // The V-11 case: an old note that legitimately starts with the marker. Its payload is not
        // base64 of an IV + ciphertext, so it is readable text, not a seal.
        val row = LegacyNoteRow(id = 4, name = "w1:hello", description = "")

        val outcome = LegacyPlaintextBackfill.seal(row) { "sealed($it)" }

        assertEquals(LegacyRowSeal.Sealed(name = "sealed(w1:hello)", description = ""), outcome)
    }

    @Test
    fun `a real but unreadable w1 value is never rewritten`() {
        // Shaped exactly like a seal, but no key is involved here: the sweep must pass it through
        // even though this process could not open it. Re-sealing would destroy the ciphertext.
        val stored = sealedPayload()
        val row = LegacyNoteRow(id = 5, name = stored, description = stored)

        var sealCalls = 0
        val outcome = LegacyPlaintextBackfill.seal(row) { sealCalls++; "sealed($it)" }

        assertEquals(0, sealCalls)
        assertSame(LegacyRowSeal.NothingToDo, outcome)
    }

    @Test
    fun `empty columns are not sealed`() {
        val row = LegacyNoteRow(id = 6, name = "", description = "")

        var sealCalls = 0
        val outcome = LegacyPlaintextBackfill.seal(row) { sealCalls++; "sealed($it)" }

        assertEquals(0, sealCalls)
        assertSame(LegacyRowSeal.NothingToDo, outcome)
    }

    @Test
    fun `an empty name with a plain body seals only the body`() {
        val row = LegacyNoteRow(id = 7, name = "", description = "body")

        val sealedInputs = mutableListOf<String>()
        val outcome = LegacyPlaintextBackfill.seal(row) { sealedInputs += it; "sealed($it)" }

        assertEquals(listOf("body"), sealedInputs)
        assertEquals(LegacyRowSeal.Sealed(name = "", description = "sealed(body)"), outcome)
    }

    @Test
    fun `a sealing failure produces no row write, even when the other column sealed`() {
        val row = LegacyNoteRow(id = 8, name = "plain name", description = "plain body")

        var sealCalls = 0
        val outcome = LegacyPlaintextBackfill.seal(row) {
            sealCalls++
            // The second column is the one the Keystore refuses.
            if (sealCalls == 2) throw IllegalStateException("Keystore unavailable")
            "sealed($it)"
        }

        // The first column was sealed in memory, but the outcome carries nothing: the caller writes
        // only on Sealed, so the row keeps both original bytes.
        assertEquals(2, sealCalls)
        assertSame(LegacyRowSeal.Failed, outcome)
    }

    @Test
    fun `an already sealed row costs no seal calls`() {
        val sealedBody = sealedPayload()
        val row = LegacyNoteRow(id = 9, name = sealedPayload(), description = sealedBody)

        var sealCalls = 0
        val outcome = LegacyPlaintextBackfill.seal(row) { sealCalls++; "sealed($it)" }

        assertEquals(0, sealCalls)
        assertSame(LegacyRowSeal.NothingToDo, outcome)
    }

    /**
     * A structurally valid seal: 12 byte IV plus one 16 byte GCM block, base64 encoded.
     *
     * Uses `java.util.Base64` because this is a plain JVM test - `android.util.Base64` would need
     * Robolectric, and the format `NoteSealFormat` checks is the same either way.
     */
    private fun sealedPayload(): String =
        NoteSealFormat.MARKER + java.util.Base64.getEncoder().encodeToString(ByteArray(12 + 16))
}

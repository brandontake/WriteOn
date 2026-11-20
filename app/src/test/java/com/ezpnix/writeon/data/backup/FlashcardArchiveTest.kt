package com.ezpnix.writeon.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAT-11: the flashcard deck travels with the app's own archives.
 *
 * Everything here runs against the [FlashcardArchive] seams ([FlashcardArchive.DeckStore] and
 * [FlashcardArchive.Sealer]) so no test needs SharedPreferences or the Android Keystore - the two
 * pieces that made the deck invisible to backups are also the two pieces that would make the
 * failure paths untestable. Robolectric only supplies `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
class FlashcardArchiveTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val deck = """[{"question":"2+2","answer":"4"}]"""

    /** Stands in for `flashcards_prefs`; [writes] lets a test prove nothing was stored. */
    private class FakeStore(var value: String? = null) : FlashcardArchive.DeckStore {
        var writes = 0
        override fun read(): String? = value
        override fun write(sealed: String): Boolean {
            writes++
            value = sealed
            return true
        }

        override fun clear(): Boolean {
            writes++
            value = null
            return true
        }
    }

    /**
     * Structure-only seal: [PREFIX] marks ciphertext, [unopenable] models a value this install
     * cannot decrypt (Keystore reset, deck from another device), [sealFails] models a missing key.
     */
    private class FakeSealer(
        private val sealFails: Boolean = false,
        private val unopenable: String? = null,
    ) : FlashcardArchive.Sealer {
        override fun seal(plaintext: String): String {
            if (sealFails) throw IllegalStateException("keystore unavailable")
            return PREFIX + plaintext
        }

        override fun openOrNull(stored: String): String? =
            if (stored == unopenable) null else stored.removePrefix(PREFIX)
    }

    /** Every member of [zipBytes] by name, so entry presence and content can both be asserted. */
    private fun entries(zipBytes: ByteArray): Map<String, String> {
        val members = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                members[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return members
    }

    @Test
    fun `the deck entry survives a zip round trip`() {
        val bytes = ByteArrayOutputStream().use { buffer ->
            ZipOutputStream(buffer).use { zip -> FlashcardArchive.writeDeckEntry(zip, deck) }
            buffer.toByteArray()
        }

        val members = entries(bytes)
        assertEquals(setOf(FlashcardArchive.ENTRY_NAME), members.keys)
        assertEquals(deck, members.getValue(FlashcardArchive.ENTRY_NAME))
    }

    @Test
    fun `legacy plain json is exported as it is stored`() {
        val store = FakeStore(deck)

        assertEquals(deck, FlashcardArchive.readPlainJson(store, FakeSealer()))
    }

    @Test
    fun `a sealed deck is opened through the sealer`() {
        val store = FakeStore("$PREFIX$deck")

        assertEquals(deck, FlashcardArchive.readPlainJson(store, FakeSealer()))
    }

    @Test
    fun `an undecryptable deck is skipped`() {
        val store = FakeStore("w1:from-another-device")

        assertNull(FlashcardArchive.readPlainJson(store, FakeSealer(unopenable = "w1:from-another-device")))
    }

    @Test
    fun `a stored value that is not a json array is skipped`() {
        assertNull(FlashcardArchive.readPlainJson(FakeStore("[{\"question\":"), FakeSealer()))
        assertNull(FlashcardArchive.readPlainJson(FakeStore("""{"question":"x"}"""), FakeSealer()))
    }

    @Test
    fun `no deck means no archive member`() {
        assertNull(FlashcardArchive.readPlainJson(FakeStore(null), FakeSealer()))

        val bytes = ByteArrayOutputStream().use { buffer ->
            ZipOutputStream(buffer).use { zip -> FlashcardArchive.writeDeckEntry(zip, null) }
            buffer.toByteArray()
        }

        assertTrue(entries(bytes).isEmpty())
    }

    @Test
    fun `restoring seals the staged deck into storage`() {
        val staging = temporaryFolder.newFolder("staging")
        File(staging, FlashcardArchive.ENTRY_NAME).writeText(deck, Charsets.UTF_8)
        val store = FakeStore("$PREFIX[{\"question\":\"old\",\"answer\":\"x\"}]")

        assertTrue(FlashcardArchive.restoreFromStaging(staging, store, FakeSealer()))
        assertEquals("$PREFIX$deck", store.value)
        assertEquals(1, store.writes)
    }

    @Test
    fun `an archive without the member keeps the stored deck`() {
        val staging = temporaryFolder.newFolder("staging-no-deck")
        val store = FakeStore("$PREFIX$deck")

        assertFalse(FlashcardArchive.restoreFromStaging(staging, store, FakeSealer()))
        assertEquals("$PREFIX$deck", store.value)
        assertEquals(0, store.writes)
    }

    @Test
    fun `a failed seal leaves the stored deck untouched`() {
        val staging = temporaryFolder.newFolder("staging-seal-failure")
        File(staging, FlashcardArchive.ENTRY_NAME).writeText(deck, Charsets.UTF_8)
        val store = FakeStore("$PREFIX[{\"question\":\"old\",\"answer\":\"x\"}]")

        assertFalse(FlashcardArchive.restoreFromStaging(staging, store, FakeSealer(sealFails = true)))
        assertEquals("$PREFIX[{\"question\":\"old\",\"answer\":\"x\"}]", store.value)
        assertEquals(0, store.writes)
    }

    @Test
    fun `an invalid staged member leaves the stored deck untouched`() {
        val staging = temporaryFolder.newFolder("staging-corrupt")
        File(staging, FlashcardArchive.ENTRY_NAME).writeText("not json at all", Charsets.UTF_8)
        val store = FakeStore("$PREFIX$deck")

        assertFalse(FlashcardArchive.restoreFromStaging(staging, store, FakeSealer()))
        assertEquals("$PREFIX$deck", store.value)
        assertEquals(0, store.writes)
    }

    @Test
    fun `the raw snapshot copy keeps the stored form byte for byte`() {
        // N-2: the .bak sidecar must not open, re-seal or "normalize" the deck - a legacy plain
        // value has to come back as plain and a sealed one has to come back sealed.
        val store = FakeStore("$PREFIX$deck")

        assertEquals("$PREFIX$deck", FlashcardArchive.readRaw(store))
        assertEquals(0, store.writes)
    }

    @Test
    fun `restoring the raw snapshot clears a deck the snapshot did not have`() {
        val store = FakeStore("$PREFIX$deck")

        assertTrue(FlashcardArchive.restoreRaw(store, null))
        assertNull(store.value)
        assertEquals(1, store.writes)
    }

    private companion object {
        const val PREFIX = "sealed:"
    }
}

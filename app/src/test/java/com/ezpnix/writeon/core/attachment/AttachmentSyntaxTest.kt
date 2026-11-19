package com.ezpnix.writeon.core.attachment

import com.ezpnix.writeon.domain.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D13: `newId()` went from 8 hex chars (32 bit) to 32 hex chars (128 bit). What must not regress
 * is the contract around it: the charset every body regex accepts, round-tripping through the
 * Markdown syntax, and the fact that ids minted by older builds still parse.
 */
class AttachmentSyntaxTest {

    private val bodyIdPattern = Regex("[A-Za-z0-9_-]+")

    @Test
    fun `newId is 32 lowercase hex characters - 128 bits of entropy`() {
        repeat(64) {
            val id = AttachmentSyntax.newId()
            assertEquals("32 hex chars = 128 bit", 32, id.length)
            assertTrue("id must be lowercase hex: $id", id.all { it in '0'..'9' || it in 'a'..'f' })
            assertTrue("must stay inside the charset the body regexes accept", bodyIdPattern.matches(id))
        }
    }

    @Test
    fun `newId does not collide across a hundred thousand draws`() {
        val ids = HashSet<String>(131_072)
        val draws = 100_000
        repeat(draws) { ids += AttachmentSyntax.newId() }
        assertEquals("128-bit ids must not collide at 100k", draws, ids.size)
    }

    @Test
    fun `legacy 8 hex ids still parse everywhere a new id does`() {
        val legacy = "a1b2c3d4" // exactly what builds before D13 minted

        assertTrue(bodyIdPattern.matches(legacy))
        assertEquals(legacy, AttachmentSyntax.idOf(AttachmentSyntax.SCHEME + legacy))

        val imageBody = AttachmentSyntax.image(legacy)
        assertEquals("![](attachment://a1b2c3d4)", imageBody)
        assertEquals(setOf(legacy), AttachmentSyntax.idsIn(imageBody))
        assertTrue(AttachmentSyntax.IMAGE_LINE.matches(imageBody))

        val audioBody = AttachmentSyntax.audio(legacy, "voice")
        assertEquals(setOf(legacy), AttachmentSyntax.idsIn(audioBody))
        val audioMatch = AttachmentSyntax.AUDIO_LINE.matchEntire(audioBody)
        assertTrue(audioMatch != null)
        assertEquals(legacy, audioMatch!!.groupValues[2])

        // Removing a legacy id must leave no line behind, same as for a new id.
        val body = "before\n$imageBody\n$audioBody\nafter"
        assertEquals("before\nafter", AttachmentSyntax.removeId(body, legacy))
    }

    @Test
    fun `new 32 hex ids round trip through the body syntax`() {
        val id = AttachmentSyntax.newId()

        val imageBody = AttachmentSyntax.image(id)
        assertEquals("![](attachment://$id)", imageBody)
        assertEquals(setOf(id), AttachmentSyntax.idsIn(imageBody))
        assertEquals(id, AttachmentSyntax.IMAGE_LINE.matchEntire(imageBody)!!.groupValues[1])

        val audioBody = AttachmentSyntax.audio(id, "note")
        assertEquals(setOf(id), AttachmentSyntax.idsIn(audioBody))
        assertEquals(id, AttachmentSyntax.AUDIO_LINE.matchEntire(audioBody)!!.groupValues[2])

        assertEquals(id, AttachmentSyntax.idOf(AttachmentSyntax.SCHEME + id))
        assertFalse(AttachmentSyntax.isAttachment("plain text"))

        // A new id must survive removeId exactly like a legacy one.
        val body = "head\n$imageBody\ntail"
        assertEquals("head\ntail", AttachmentSyntax.removeId(body, id))
        // ...and ids it does not reference are left alone.
        assertEquals(body, AttachmentSyntax.removeId(body, AttachmentSyntax.newId()))
    }

    @Test
    fun `ids in one body are all found and mixed legacy and new ids coexist`() {
        val legacy = "deadbeef"
        val fresh = AttachmentSyntax.newId()
        val body = listOf(
            AttachmentSyntax.image(legacy),
            AttachmentSyntax.audio(fresh, "clip"),
            AttachmentSyntax.image(fresh),
        ).joinToString("\n")

        assertEquals(setOf(legacy, fresh), AttachmentSyntax.idsIn(body))
    }

    @Test
    fun `distinct draws are never equal to each other by construction check`() {
        // Guard against an implementation that accidentally returns a constant.
        assertNotEquals(AttachmentSyntax.newId(), AttachmentSyntax.newId())
    }

    @Test
    fun `lineFor builds the parseable line for both types`() {
        val id = AttachmentSyntax.newId()
        assertTrue(
            AttachmentSyntax.IMAGE_LINE.matches(AttachmentSyntax.lineFor(id, Attachment.TYPE_IMAGE, "ignored")),
        )
        val audio = AttachmentSyntax.lineFor(id, Attachment.TYPE_AUDIO, "caption")
        assertTrue(AttachmentSyntax.AUDIO_LINE.matches(audio))
        assertEquals("caption", AttachmentSyntax.AUDIO_LINE.matchEntire(audio)!!.groupValues[1])
    }
}

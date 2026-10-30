package com.ezpnix.writeon.data.local.database

import com.ezpnix.writeon.core.attachment.AttachmentSyntax
import com.ezpnix.writeon.domain.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 1.x -> 2.0 body rewrite runs inside a Room migration over every note the user has, so a bug
 * here is a bug in everybody's data on upgrade. It is pure string work, which is exactly why it
 * can be pinned with plain JVM tests instead of a device.
 */
class LegacyBodyMigrationTest {

    @Test
    fun `image token becomes an attachment link with a matching row`() {
        val body = "before\n!(/storage/emulated/0/Android/data/com.ezpnix.writeon/files/1234.jpg)\nafter"

        val result = LegacyBodyMigration.rewrite(body, noteId = 7)

        assertTrue(result.changed)
        assertEquals(1, result.attachments.size)
        val attachment = result.attachments.single()
        assertEquals(Attachment.TYPE_IMAGE, attachment.type)
        assertEquals("/storage/emulated/0/Android/data/com.ezpnix.writeon/files/1234.jpg", attachment.path)
        assertEquals(7, attachment.noteId)
        assertEquals("before\n${AttachmentSyntax.image(attachment.id)}\nafter", result.body)
    }

    @Test
    fun `audio token becomes a captioned link`() {
        val body = "?audio(/data/user/0/com.ezpnix.writeon/files/audios/20261018-2245.m4a)"

        val result = LegacyBodyMigration.rewrite(body, noteId = 3)

        assertTrue(result.changed)
        val attachment = result.attachments.single()
        assertEquals(Attachment.TYPE_AUDIO, attachment.type)
        assertEquals("20261018-2245", attachment.caption)
        assertEquals(AttachmentSyntax.audio(attachment.id, "20261018-2245"), result.body)
    }

    @Test
    fun `the same path twice yields one row and two links`() {
        val path = "/data/user/0/com.ezpnix.writeon/files/audios/take.m4a"
        val body = "?audio($path)\n?audio($path)"

        val result = LegacyBodyMigration.rewrite(body)

        assertEquals(1, result.attachments.size)
        val id = result.attachments.single().id
        val expected = "${AttachmentSyntax.audio(id, "take")}\n${AttachmentSyntax.audio(id, "take")}"
        assertEquals(expected, result.body)
    }

    @Test
    fun `running twice is a no-op`() {
        val body = "text\n!(/tmp/pic.jpg)"

        val once = LegacyBodyMigration.rewrite(body)
        val twice = LegacyBodyMigration.rewrite(once.body)

        assertFalse(twice.changed)
        assertEquals(once.body, twice.body)
        assertTrue(twice.attachments.isEmpty())
    }

    @Test
    fun `fenced code blocks keep their tokens literal`() {
        val body = "```\n!(/tmp/should-not-move.jpg)\n```\n!(/tmp/should-move.jpg)"

        val result = LegacyBodyMigration.rewrite(body)

        assertEquals(1, result.attachments.size)
        assertEquals("/tmp/should-move.jpg", result.attachments.single().path)
        assertTrue(result.body.contains("!(/tmp/should-not-move.jpg)"))
        assertFalse(result.body.contains("/tmp/should-move.jpg)"))
    }

    @Test
    fun `plain text is returned untouched`() {
        val body = "just a note with !( and ?audio( as literal text but no closing paren"

        val result = LegacyBodyMigration.rewrite(body)

        // The tokens above have no `)`, so neither regex can match; nothing may change.
        assertFalse(result.changed)
        assertEquals(body, result.body)
        assertTrue(result.attachments.isEmpty())
    }

    @Test
    fun `empty paths are left alone`() {
        val body = "!()\n?audio()"

        val result = LegacyBodyMigration.rewrite(body)

        assertFalse(result.changed)
        assertEquals(body, result.body)
    }

    @Test
    fun `new note ids start at the placeholder`() {
        val result = LegacyBodyMigration.rewrite("!(/tmp/a.jpg)")

        // The editor passes 0 explicitly; the default must match so a brand-new note's rows can be
        // claimed by assignAttachments once Room hands out the real id.
        assertEquals(0, result.attachments.single().noteId)
    }
}

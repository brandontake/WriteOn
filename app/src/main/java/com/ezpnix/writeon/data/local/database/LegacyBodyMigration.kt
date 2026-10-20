package com.ezpnix.writeon.data.local.database

import com.ezpnix.writeon.core.attachment.AttachmentSyntax
import com.ezpnix.writeon.domain.model.Attachment
import java.io.File

/**
 * Rewrites the private 1.x body syntax into 2.0 attachments.
 *
 * Before 2.0 an image was a line like `!(/storage/.../1234.jpg)` and a recording was
 * `?audio(/data/.../audios/rec.m4a)`. Both were rendered only by this app's own parser and leaked a
 * filesystem path into the note text. The rewrite turns
 *
 *     !(/storage/emulated/0/Android/data/com.ezpnix.writeon/files/1234.jpg)
 *     ?audio(/data/user/0/com.ezpnix.writeon/files/audios/20261018-2245.m4a)
 *
 * into
 *
 *     ![](attachment://a1b2c3d4)
 *     [20261018-2245](attachment://e5f6a7b8)
 *
 * and returns the rows the caller must insert into `attachments`. The function is idempotent: text
 * that already uses `attachment://` contains none of the old tokens, so running it twice is a
 * no-op. Repeating a path reuses one id, so a picture shown twice is stored once.
 *
 * Lines inside fenced code blocks are left alone - a note that documents these very tokens should
 * keep them literal.
 */
object LegacyBodyMigration {

    private val IMAGE_TOKEN = Regex("""!\(([^)\n]+)\)""")
    private val AUDIO_TOKEN = Regex("""\?audio\(([^)\n]+)\)""")

    data class Result(
        val body: String,
        val attachments: List<Attachment>
    ) {
        val changed: Boolean get() = attachments.isNotEmpty()
    }

    /**
     * @param noteId owner to stamp on the returned rows; the editor passes 0 because a brand new
     * note has no id yet and claims its rows once Room assigns one.
     */
    fun rewrite(body: String, noteId: Int = 0): Result {
        if (!body.contains("!(") && !body.contains("?audio(")) {
            return Result(body, emptyList())
        }

        val attachments = LinkedHashMap<String, Attachment>()
        val idsByPath = HashMap<String, String>()

        fun idFor(type: String, path: String, caption: String): String {
            val key = "$type\u0000$path"
            idsByPath[key]?.let { return it }

            val id = AttachmentSyntax.newId()
            idsByPath[key] = id
            attachments[id] = Attachment(
                id = id,
                noteId = noteId,
                type = type,
                path = path,
                caption = caption,
                createdAt = System.currentTimeMillis()
            )
            return id
        }

        val output = StringBuilder()
        var insideFence = false

        body.split('\n').forEachIndexed { index, line ->
            if (index > 0) output.append('\n')

            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                insideFence = !insideFence
                output.append(line)
                return@forEachIndexed
            }
            if (insideFence) {
                output.append(line)
                return@forEachIndexed
            }

            output.append(rewriteLine(line, ::idFor))
        }

        if (attachments.isEmpty()) return Result(body, emptyList())

        return Result(output.toString(), attachments.values.toList())
    }

    private fun rewriteLine(line: String, idFor: (String, String, String) -> String): String {
        var rewritten = line

        if (rewritten.contains("?audio(")) {
            rewritten = AUDIO_TOKEN.replace(rewritten) { match ->
                val path = match.groupValues[1].trim()
                if (path.isEmpty()) {
                    match.value
                } else {
                    val caption = captionFor(path)
                    AttachmentSyntax.audio(idFor(Attachment.TYPE_AUDIO, path, caption), caption)
                }
            }
        }

        if (rewritten.contains("!(")) {
            rewritten = IMAGE_TOKEN.replace(rewritten) { match ->
                val path = match.groupValues[1].trim()
                if (path.isEmpty()) {
                    match.value
                } else {
                    AttachmentSyntax.image(idFor(Attachment.TYPE_IMAGE, path, ""))
                }
            }
        }

        return rewritten
    }

    /** `20261018-2245.m4a` becomes `20261018-2245`; the renderer prefers the user's own label. */
    private fun captionFor(path: String): String =
        runCatching { File(path).nameWithoutExtension }.getOrDefault("").ifBlank { "audio" }
}

package com.ezpnix.writeon.presentation.screens.edit.segments

import com.ezpnix.writeon.core.attachment.AttachmentSyntax
import com.ezpnix.writeon.domain.model.Attachment

/**
 * The editor body split into the two kinds of thing it can contain.
 *
 * A note is still one Markdown string in the database. This model is a projection used only by
 * the segmented editor: text runs stay ordinary text fields, while the lines that are nothing but
 * an `attachment://` link become blocks. [assemble] turns the projection back into the exact same
 * string, which is what lets save, encryption, backup, export and the widget keep working without
 * knowing that the editor changed.
 */
sealed interface NoteSegment {
    /** First line of the segment, inclusive and zero-based. */
    val startLine: Int

    /** Last line of the segment, inclusive. */
    val endLine: Int

    /** Character offset of the first character, in the whole body. */
    val startOffset: Int

    /** Character offset just past the last character, in the whole body. */
    val endOffset: Int

    /** Stable across recompositions; only used as a LazyColumn item key. */
    val key: String
}

/** A run of ordinary Markdown lines. May be empty (blank line, end of the note). */
data class TextSegment(
    override val startLine: Int,
    override val endLine: Int,
    override val startOffset: Int,
    override val endOffset: Int,
    val text: String,
    override val key: String,
    /**
     * True for the zero-length field appended after a body that ends on an attachment line with no
     * trailing newline. It is not part of [assemble] until the user types into it, at which point
     * the editor has to open a line first.
     */
    val synthetic: Boolean = false
) : NoteSegment

/** One line that is exactly an image or a recording. [raw] keeps its original line ending. */
data class AttachmentSegment(
    override val startLine: Int,
    override val endLine: Int,
    override val startOffset: Int,
    override val endOffset: Int,
    val raw: String,
    val attachmentId: String,
    val type: String,
    val caption: String,
    override val key: String = "attachment:$attachmentId"
) : NoteSegment

/**
 * Splits [text] into text and attachment segments without changing a single byte.
 *
 * Fenced code is always text: an `![](attachment://...)` line inside a fence has to stay source,
 * because that is the only way to write about the syntax. A line only becomes an attachment when
 * the whole trimmed line matches the same regex the renderer uses, so pasted prose that merely
 * mentions a link keeps working.
 */
fun segmentize(text: String): List<NoteSegment> {
    val lines = splitLines(text)
    val segments = mutableListOf<NoteSegment>()
    var textOrdinal = 0

    // The same attachment id can legitimately appear on two lines (copy/paste, or a legacy body
    // that reused one id for one file). A LazyColumn key must be unique, so only the second and
    // later occurrences get an ordinal suffix. Using the occurrence rather than the offset keeps
    // the key stable while the user types in a text segment above the attachment.
    val attachmentOccurrences = mutableMapOf<String, Int>()

    var textStartLine = -1
    var textEndLine = -1
    var textStartOffset = -1
    var textEndOffset = -1
    var insideFence = false

    fun flushText() {
        if (textStartLine < 0) return
        segments += TextSegment(
            startLine = textStartLine,
            endLine = textEndLine,
            startOffset = textStartOffset,
            endOffset = textEndOffset,
            text = text.substring(textStartOffset, textEndOffset),
            key = "text:${textOrdinal++}"
        )
        textStartLine = -1
        textEndLine = -1
        textStartOffset = -1
        textEndOffset = -1
    }

    lines.forEachIndexed { index, line ->
        val content = text.substring(line.start, line.end)
        val trimmed = content.trim()
        val opensOrClosesFence = trimmed.startsWith("```")

        if (!insideFence && !opensOrClosesFence) {
            val image = AttachmentSyntax.IMAGE_LINE.matchEntire(trimmed)
            val audio = AttachmentSyntax.AUDIO_LINE.matchEntire(trimmed)
            if (image != null || audio != null) {
                flushText()
                val id = image?.groupValues?.getOrNull(1)
                    ?: audio?.groupValues?.getOrNull(2)
                    ?: ""
                val caption = audio?.groupValues?.getOrNull(1).orEmpty()
                val occurrence = attachmentOccurrences.getOrDefault(id, 0)
                attachmentOccurrences[id] = occurrence + 1
                segments += AttachmentSegment(
                    startLine = index,
                    endLine = index,
                    startOffset = line.start,
                    endOffset = line.end,
                    raw = content,
                    attachmentId = id,
                    type = if (image != null) Attachment.TYPE_IMAGE else Attachment.TYPE_AUDIO,
                    caption = caption,
                    key = if (occurrence == 0) {
                        "attachment:$id"
                    } else {
                        "attachment:$id#$occurrence"
                    }
                )
                return@forEachIndexed
            }
        }

        if (opensOrClosesFence) insideFence = !insideFence
        if (textStartLine < 0) {
            textStartLine = index
            textStartOffset = line.start
        }
        textEndLine = index
        textEndOffset = line.end
    }
    flushText()

    // A body that ends on an attachment still needs somewhere for the caret to live. The empty
    // segment is synthetic when the original body had no trailing newline, so assemble() does not
    // invent one.
    if (segments.isEmpty() || segments.last() is AttachmentSegment) {
        val last = segments.lastOrNull()
        val endsWithNewline = text.endsWith('\n')
        segments += TextSegment(
            startLine = last?.endLine ?: 0,
            endLine = last?.endLine ?: 0,
            startOffset = text.length,
            endOffset = text.length,
            text = "",
            key = "text:${textOrdinal++}",
            synthetic = !endsWithNewline
        )
    }

    return segments
}

/** Undoes [segmentize]. For every string `assemble(segmentize(x)) == x`. */
fun assemble(segments: List<NoteSegment>): String = buildString {
    segments.forEachIndexed { index, segment ->
        val skipSeparator = segment is TextSegment && segment.synthetic && segment.text.isEmpty()
        if (index > 0 && !skipSeparator) append('\n')
        when (segment) {
            is TextSegment -> append(segment.text)
            is AttachmentSegment -> append(segment.raw)
        }
    }
}

private data class LineRange(val start: Int, val end: Int)

/**
 * Splits on `\n` while keeping a trailing `\r` in the line content. That is what makes a CRLF body
 * round-trip through [assemble] unchanged: the `\r` stays with the preceding line and the `\n` is
 * the separator the assembler writes back.
 */
private fun splitLines(text: String): List<LineRange> {
    val result = mutableListOf<LineRange>()
    var start = 0
    var index = 0
    while (index < text.length) {
        if (text[index] == '\n') {
            result += LineRange(start, index)
            start = index + 1
        }
        index++
    }
    result += LineRange(start, text.length)
    return result
}

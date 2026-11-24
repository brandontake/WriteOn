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
 * True when [text] contains something [segmentize] could possibly turn into a block.
 *
 * PERF-01: this is the check that keeps an ordinary note off the segmentation path entirely. There
 * are only two shapes that become blocks - `![](attachment://id)` and `[caption](attachment://id)` -
 * plus the legacy `!()`, `?audio()` and `![` forms the parser recognises as blocks. A body without
 * any of those markers is one text field, no matter how long it is, and a handful of `indexOf`
 * scans cost a fraction of splitting every line into a substring and running two regexes on it.
 *
 * Deliberately conservative: a candidate that turns out to be prose only means [segmentize] runs,
 * never that an attachment line is missed.
 */
internal fun mayContainAttachmentSyntax(text: String): Boolean =
    text.contains(AttachmentSyntax.SCHEME) ||
        text.contains("?audio(") ||
        text.contains("!(") ||
        text.contains("![")

/**
 * The one field a body without attachment syntax is edited as.
 *
 * Kept identical to what [segmentize] would produce for such a body (one segment covering every
 * byte, key `text:0`), so callers can switch between the two without a behavioural difference.
 */
internal fun wholeBodyTextSegment(text: String): TextSegment = TextSegment(
    startLine = 0,
    endLine = text.count { it == '\n' },
    startOffset = 0,
    endOffset = text.length,
    text = text,
    key = "text:0"
)

/**
 * The segments the editor should lay out for [text].
 *
 * PERF-01: [mayContainAttachmentSyntax] is the fast path - a note that has never held an
 * attachment skips [segmentize] completely. [segmentizeFn] exists so a test can pass a counting
 * wrapper and prove that without a counter in production code; real callers keep the default.
 */
fun segmentsFor(
    text: String,
    segmentizeFn: (String) -> List<NoteSegment> = ::segmentize
): List<NoteSegment> =
    if (mayContainAttachmentSyntax(text)) segmentizeFn(text) else listOf(wholeBodyTextSegment(text))

/**
 * The text segment called [key] in the current [text], without segmentizing an ordinary body.
 *
 * PERF-01: the editor's `onSegmentChange` used to call [segmentize] a second time per keystroke just
 * to translate a composition-captured key back into offsets. With no attachment markers there is
 * exactly one text field, so the offsets are the whole body and no scan is needed; a note that does
 * have markers keeps the exact lookup it had before.
 *
 * REG-09: the no-marker branch answers only for the one key that field actually has. A disposed
 * segment can still deliver one callback carrying the key it had before the body lost its last
 * attachment (`text:1`, say). Returning the whole body for that stale key let the callback replace
 * the entire note with one field's text; the caller treats null as "ignore this edit".
 */
internal fun currentTextSegment(text: String, key: String): TextSegment? =
    if (mayContainAttachmentSyntax(text)) {
        segmentize(text).filterIsInstance<TextSegment>().firstOrNull { it.key == key }
    } else {
        wholeBodyTextSegment(text).takeIf { it.key == key }
    }

/**
 * Splits [text] into text and attachment segments without changing a single byte.
 *
 * Fenced code is always text: an `![](attachment://...)` line inside a fence has to stay source,
 * because that is the only way to write about the syntax. A line only becomes an attachment when
 * the whole trimmed line matches the same regex the renderer uses, so pasted prose that merely
 * mentions a link keeps working.
 *
 * One pass, no per-line result list: start/end offsets are tracked over the string itself. Lines
 * whose first visible character cannot start an attachment (`!` or `[`) or a fence (`` ` ``) never
 * allocate a substring or run a regex; only the few candidate lines pay for `trim()` and a match.
 */
fun segmentize(text: String): List<NoteSegment> {
    val segments = mutableListOf<NoteSegment>()
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
            // REG-09 residual: assigned in the second pass below, where the segment's identity can
            // be derived from the attachment that precedes it instead of its ordinal.
            key = ""
        )
        textStartLine = -1
        textEndLine = -1
        textStartOffset = -1
        textEndOffset = -1
    }

    // Hoisted out of the loop: the regexes are process-wide, but a note with thousands of lines
    // dereferences them thousands of times.
    val imageLine = AttachmentSyntax.IMAGE_LINE
    val audioLine = AttachmentSyntax.AUDIO_LINE

    var lineIndex = 0
    var lineStart = 0
    // `<=` deliberately: a body ending in `\n` still has one empty line after it, exactly like
    // `splitLines` produced before this loop replaced it.
    while (lineStart <= text.length) {
        var lineEnd = lineStart
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++

        var first = lineStart
        while (first < lineEnd && text[first].isWhitespace()) first++
        val firstChar = if (first < lineEnd) text[first] else '\u0000'
        val opensOrClosesFence = firstChar == '`' &&
            first + 2 < lineEnd && text[first + 1] == '`' && text[first + 2] == '`'

        if (!insideFence && !opensOrClosesFence && (firstChar == '!' || firstChar == '[')) {
            val content = text.substring(lineStart, lineEnd)
            val trimmed = content.trim()
            val image = imageLine.matchEntire(trimmed)
            val audio = audioLine.matchEntire(trimmed)
            if (image != null || audio != null) {
                flushText()
                val id = image?.groupValues?.getOrNull(1)
                    ?: audio?.groupValues?.getOrNull(2)
                    ?: ""
                val caption = audio?.groupValues?.getOrNull(1).orEmpty()
                val occurrence = attachmentOccurrences.getOrDefault(id, 0)
                attachmentOccurrences[id] = occurrence + 1
                segments += AttachmentSegment(
                    startLine = lineIndex,
                    endLine = lineIndex,
                    startOffset = lineStart,
                    endOffset = lineEnd,
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
                lineIndex++
                if (lineEnd >= text.length) break
                lineStart = lineEnd + 1
                continue
            }
        }

        if (opensOrClosesFence) insideFence = !insideFence
        if (textStartLine < 0) {
            textStartLine = lineIndex
            textStartOffset = lineStart
        }
        textEndLine = lineIndex
        textEndOffset = lineEnd

        lineIndex++
        if (lineEnd >= text.length) break
        lineStart = lineEnd + 1
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
            key = "",
            synthetic = !endsWithNewline
        )
    }

    return keyTextSegments(segments)
}

/**
 * REG-09 residual: gives every text segment an identity that survives typing but not a structural
 * edit.
 *
 * The old keys were ordinals (`text:0`, `text:1`, ...). Deleting one attachment line merged two
 * text runs while the ordinal of the following run stayed free for reuse, so a callback captured by
 * a disposed field could be applied to a completely different range. Every text run is now keyed by
 * the attachment next to it (`text:after:<id>` or, for the leading run, `text:before:<id>`), which
 * keeps the LazyColumn key stable while the user types and makes it disappear the moment that
 * attachment line is removed or rewritten. A body without attachments keeps the fast-path
 * `text:0` key.
 */
private fun keyTextSegments(segments: List<NoteSegment>): List<NoteSegment> {
    var precedingAttachment: String? = null
    return segments.mapIndexed { index, segment ->
        when (segment) {
            is AttachmentSegment -> {
                precedingAttachment = segment.key
                segment
            }
            is TextSegment -> {
                // The leading run is named after the attachment that *follows* it. With a plain
                // `text:0` for every leading run, deleting the first attachment line made an old
                // field's key reusable for a different range - the residual REG-09 window. Every
                // text key in a segmented body now depends on an attachment id, so a structural
                // edit that removes/renames one invalidates exactly the keys that described it.
                val key = precedingAttachment?.let { "text:after:$it" }
                    ?: segments.subList(index, segments.size)
                        .filterIsInstance<AttachmentSegment>()
                        .firstOrNull()
                        ?.let { "text:before:${it.key}" }
                    ?: "text:0"
                segment.copy(key = key)
            }
        }
    }
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

// The line walk above replaces the old `splitLines`/`LineRange` pair. Folding it into `segmentize`
// keeps a `\r` with its line (so CRLF bodies still round-trip through `assemble`) without building
// a range object for every line of a note that has no attachments at all.

package com.ezpnix.writeon.presentation.components.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

class MarkdownBuilder(internal val lines: List<String>, private var lineProcessors: List<MarkdownLineProcessor>) {
    var lineIndex = -1

    internal val content = mutableListOf<MarkdownElement>()

    fun add(element: MarkdownElement) {
        content.add(element)
    }

    fun parse() {
        while (hasNextLine()) {
            val line = nextLine()
            val processor = lineProcessors.find { it.canProcessLine(line) }
            if (processor != null) {
                processor.processLine(line, this)
            } else {
                add(NormalText(line))
            }
        }
    }

    private fun hasNextLine(): Boolean = lineIndex + 1 < lines.size

    private fun nextLine(): String {
        lineIndex++
        return lines[lineIndex]
    }
}

/**
 * Splits the input string by the specified delimiter and returns a list of index pairs.
 * Each pair represents the start and end indices of segments between delimiters.
 */
fun splitByDelimiter(input: String, delimiter: String): List<Pair<Int, Int>> {
    val segments = mutableListOf<Pair<Int, Int>>()
    var startIndex = 0
    var delimiterIndex = input.indexOf(delimiter, startIndex)

    while (delimiterIndex != -1) {
        if (startIndex != delimiterIndex) {
            segments.add(Pair(startIndex, delimiterIndex))
        } else {
            segments.add(Pair(startIndex, startIndex))
        }
        startIndex = delimiterIndex + delimiter.length
        delimiterIndex = input.indexOf(delimiter, startIndex)
    }

    if (startIndex < input.length) {
        segments.add(Pair(startIndex, input.length))
    } else if (startIndex == input.length) {
        segments.add(Pair(startIndex, startIndex))
    }

    return segments.filterIndexed { index, _ -> index % 2 == 1 }
}

/**
 * A run of consecutive characters that share the same set of active inline styles.
 */
private class StyledRun(val start: Int, val endExclusive: Int, val styleMask: Int)

/**
 * Builds an AnnotatedString with styles applied based on markdown-like syntax.
 *
 * The original walked the text one character at a time and, for each one, re-scanned every style
 * segment's ranges and pushed a fresh span around a single character. A few hundred characters of
 * prose turned into hundreds of style spans and thousands of range comparisons, which is why long
 * notes stuttered while scrolling. This resolves each character's styles once, collapses
 * consecutive characters that share them into a run, and emits one span per run, so uniform text
 * costs one span instead of one per character.
 *
 * Rendering is unchanged: styles are merged in the same order, and characters that form part of any
 * delimiter are still dropped from the output.
 */
fun buildString(
    input: String,
    defaultFontWeight: FontWeight = FontWeight.Normal,
    linkColor: Color = Color(0xFF1976D2)
): AnnotatedString {
    if (input.isEmpty()) return AnnotatedString("")

    // Check for markdown links: [text](url). A backslash in front of the opening bracket marks it as
    // an escaped literal (see protectEscapes), so those matches are left to the styled-run builder
    // and render as text instead of turning into a tappable link.
    val linkRegex = Regex("\\[([^\\]]+)\\]\\(([^\\)]+)\\)")
    val linkMatches = linkRegex.findAll(input)
        .filterNot { match -> match.range.first > 0 && input[match.range.first - 1] == '\\' }
        .toList()

    if (linkMatches.isEmpty()) {
        return buildStyledRunString(input, defaultFontWeight)
    }

    return buildAnnotatedString {
        var lastIndex = 0
        for (match in linkMatches) {
            val range = match.range
            if (range.first > lastIndex) {
                append(buildStyledRunString(input.substring(lastIndex, range.first), defaultFontWeight))
            }
            val linkText = match.groupValues[1]
            val linkUrl = match.groupValues[2]
            val styledLinkText = buildStyledRunString(linkText, defaultFontWeight)

            val startPos = length
            append(styledLinkText)
            val endPos = length

            addLink(
                url = LinkAnnotation.Url(
                    url = linkUrl,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        )
                    )
                ),
                start = startPos,
                end = endPos
            )
            lastIndex = range.last + 1
        }
        if (lastIndex < input.length) {
            append(buildStyledRunString(input.substring(lastIndex), defaultFontWeight))
        }
    }
}

private fun buildStyledRunString(rawInput: String, defaultFontWeight: FontWeight): AnnotatedString {
    if (rawInput.isEmpty()) return AnnotatedString("")

    // Escaped characters are swapped for sentinels before anything is measured: a delimiter
    // character is dropped from the output by design below, so `\*` has to stop looking like a
    // delimiter. The sentinel is a private-use code point, which no delimiter can match.
    val input = protectEscapes(rawInput)

    val segments: List<TextStyleSegment> = listOf(
        BoldSegment(),
        ItalicSegment(),
        HighlightSegment(),
        Strikethrough(),
        Underline(),
        InlineCode()
    )

    val covered = Array(segments.size) { BooleanArray(input.length) }
    val delimiterChars = HashSet<Char>()
    segments.forEachIndexed { index, segment ->
        delimiterChars.addAll(segment.delimiter.toList())
        splitByDelimiter(input, segment.delimiter).forEach { (start, end) ->
            for (offset in start until end) covered[index][offset] = true
        }
    }

    fun styleMaskAt(index: Int): Int {
        var mask = 0
        for (segment in segments.indices) {
            if (covered[segment][index]) mask = mask or (1 shl segment)
        }
        return mask
    }

    val runs = mutableListOf<StyledRun>()
    var runStart = -1
    var runMask = 0
    for (index in input.indices) {
        if (input[index] in delimiterChars) {
            if (runStart != -1) {
                runs.add(StyledRun(runStart, index, runMask))
                runStart = -1
            }
            continue
        }
        val mask = styleMaskAt(index)
        if (runStart != -1 && mask == runMask) continue
        if (runStart != -1) runs.add(StyledRun(runStart, index, runMask))
        runStart = index
        runMask = mask
    }
    if (runStart != -1) runs.add(StyledRun(runStart, input.length, runMask))

    if (runs.isEmpty()) return AnnotatedString("")

    val baseStyle = SpanStyle(fontWeight = defaultFontWeight)
    val styleCache = HashMap<Int, SpanStyle>()
    fun styleFor(mask: Int): SpanStyle = styleCache.getOrPut(mask) {
        segments.foldIndexed(baseStyle) { index, acc, segment ->
            if (mask and (1 shl index) != 0) acc.merge(segment.getSpanStyle()) else acc
        }
    }

    return buildAnnotatedString {
        runs.forEach { run ->
            withStyle(style = styleFor(run.styleMask)) {
                append(restoreEscapes(input, run.start, run.endExclusive))
            }
        }
    }
}

/**
 * Base code point used to stand in for an escaped ASCII character. U+E000..U+E05E is the private-use
 * area, so a sentinel can never be mistaken for one of the markdown delimiters (`* _ = ~ ` |`).
 */
private const val ESCAPE_SENTINEL_BASE = 0xE000

/** Lowest and highest escaped code points the sentinel range covers (printable ASCII 0x20..0x7E). */
private const val ESCAPE_SENTINEL_FIRST = 0x20
private const val ESCAPE_SENTINEL_LAST = 0x7E

/**
 * Replaces `\X` with a sentinel character carrying X, so the delimiters a writer escaped stay
 * literal. A lone backslash at the end of the text is kept as-is.
 */
private fun protectEscapes(input: String): String {
    if (input.indexOf('\\') == -1) return input

    val out = StringBuilder(input.length)
    var index = 0
    while (index < input.length) {
        val char = input[index]
        val next = input.getOrNull(index + 1)
        if (char == '\\' && next != null) {
            out.append(
                if (next.code in ESCAPE_SENTINEL_FIRST..ESCAPE_SENTINEL_LAST) {
                    (ESCAPE_SENTINEL_BASE + next.code - ESCAPE_SENTINEL_FIRST).toChar()
                } else {
                    // Non-ASCII escapes (a Chinese character after a backslash, say) have nothing to
                    // escape, so the backslash is dropped and the character kept.
                    next
                }
            )
            index += 2
        } else {
            out.append(char)
            index++
        }
    }
    return out.toString()
}

/** Turns the sentinels in [start] until [endExclusive] back into the characters they stand for. */
private fun restoreEscapes(input: String, start: Int, endExclusive: Int): String {
    var needsRestore = false
    for (index in start until endExclusive) {
        if (input[index].code >= ESCAPE_SENTINEL_BASE) {
            needsRestore = true
            break
        }
    }
    if (!needsRestore) return input.substring(start, endExclusive)

    val out = StringBuilder(endExclusive - start)
    for (index in start until endExclusive) {
        val char = input[index]
        val code = char.code
        out.append(
            if (code in ESCAPE_SENTINEL_BASE..(ESCAPE_SENTINEL_BASE + ESCAPE_SENTINEL_LAST - ESCAPE_SENTINEL_FIRST)) {
                (code - ESCAPE_SENTINEL_BASE + ESCAPE_SENTINEL_FIRST).toChar()
            } else {
                char
            }
        )
    }
    return out.toString()
}
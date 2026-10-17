package com.ezpnix.writeon.presentation.components.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
fun buildString(input: String, defaultFontWeight: FontWeight = FontWeight.Normal): AnnotatedString {
    if (input.isEmpty()) return AnnotatedString("")

    val segments: List<TextStyleSegment> = listOf(
        BoldSegment(),
        ItalicSegment(),
        HighlightSegment(),
        Strikethrough(),
        Underline()
    )

    // Mark which offsets each style covers in one pass per style, rather than asking every style
    // whether it contains the current index while iterating over the characters.
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
                append(input, run.start, run.endExclusive)
            }
        }
    }
}
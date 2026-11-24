package com.ezpnix.writeon.presentation.screens.home.widgets

/** Enough of a note to fill the 150dp body area on any density, far less than most notes. */
internal const val CARD_BODY_MAX_LINES = 12

/** A single line longer than this (a pasted paragraph, a one-line log) is cut as well. */
internal const val CARD_BODY_MAX_LINE_CHARS = 400

/**
 * PERF-08: the part of a note body a home card renders, produced by a bounded scan.
 *
 * There are no `tails`/`hiddenLines` any more. Those existed so a checkbox tick could rebuild the
 * full note from the summary, which meant keeping one substring for every line of a possibly
 * megabyte body alive on each visible card. The card's checkbox now edits the latest stored body
 * (UX-09), so the summary only ever has to be read.
 */
internal class CardBody(
    val text: String,
    val isTruncated: Boolean,
)

/**
 * Builds the card summary without materialising every line of [description].
 *
 * `description.lines()` allocated one substring per line - and `take(12)` still walked the whole
 * body first - so twelve visible cards over a 1 MB note scanned ~12 MB and allocated all of it on
 * every scroll step. This scanner stops at whichever comes first:
 *
 *  - the 13th line (it only has to know that one more line exists, not read it),
 *  - character [CARD_BODY_MAX_LINE_CHARS] of an over-long line,
 *  - the end of the body.
 *
 * Within those bounds the output matches the old
 * `lines().take(12).joinToString("\n") { take(400) }` except at one boundary: when the character
 * cut would split a surrogate pair, the summary stops one character earlier (PERF-08) so the card
 * can never render an isolated half of an emoji. The trailing-newline case is the same as before
 * (a body ending in `\n` has an empty 13th line and is truncated). A line that exceeds the
 * character cap ends the summary there: the lines after it sit below the card's clipped area, and
 * reading them would mean walking the rest of a pasted megabyte.
 *
 * [onLineScanned] is a test seam: it is called once per line the scan enters, so a test can assert
 * that a 1 MB single-line body costs one call and one bounded scan instead of a full line split.
 */
internal fun buildCardBody(
    description: String,
    onLineScanned: () -> Unit = {}
): CardBody {
    if (description.isEmpty()) return CardBody("", isTruncated = false)

    val out = StringBuilder()
    var lineStart = 0
    var linesTaken = 0
    var truncated = false

    while (true) {
        onLineScanned()
        // The scan of this line is capped, so an over-long line costs 400 characters, not its
        // whole length.
        val scanLimit = minOf(lineStart + CARD_BODY_MAX_LINE_CHARS, description.length)
        var end = lineStart
        while (end < scanLimit && description[end] != '\n') end++
        val lineContinues = end < description.length && description[end] != '\n'
        // PERF-08: the character cap is a UTF-16 index, so it can land between the two halves of
        // an emoji and leave an isolated surrogate at the end of the card text. Back the cut off
        // by one so the summary always ends on a whole code point.
        if (
            lineContinues &&
            end > lineStart &&
            description[end - 1].isHighSurrogate() &&
            description[end].isLowSurrogate()
        ) {
            end--
        }

        if (linesTaken > 0) out.append('\n')
        out.append(description, lineStart, end)
        linesTaken++

        if (lineContinues) {
            truncated = true
            break
        }
        if (end >= description.length) break
        // description[end] is '\n', so a next line exists. After the last line the summary may
        // keep, there is no need to inspect it - its existence alone truncates.
        lineStart = end + 1
        if (linesTaken == CARD_BODY_MAX_LINES) {
            truncated = true
            break
        }
    }

    return CardBody(out.toString(), isTruncated = truncated)
}

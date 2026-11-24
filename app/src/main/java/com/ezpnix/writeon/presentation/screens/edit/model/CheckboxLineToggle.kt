package com.ezpnix.writeon.presentation.screens.edit.model

/**
 * SPL-1: toggles one checkbox on the **current** body instead of rewriting the body a debounced
 * preview parsed earlier.
 *
 * The tablet split preview renders a snapshot that can be 120 ms (or, while the user keeps typing,
 * much longer) behind the editor. The old checkbox path rebuilt the whole note from that snapshot,
 * so tapping a checkbox silently discarded every character typed since the snapshot was taken.
 *
 * This helper locates the checkbox by line and text in the live body. The line number is a hint,
 * not an identity: if typing above shifted the line, a unique matching text still finds it. If the
 * checkbox itself was edited in the meantime, the helper returns null and the caller does nothing -
 * a tap that has to be repeated is strictly better than overwriting live text.
 *
 * Kept pure and internal so CRLF, indentation and duplicate-text behaviour are pinned by plain JVM
 * tests without a ViewModel or Compose runtime.
 */
internal object CheckboxLineToggle {

    /**
     * @return the body with the target line toggled, or null when the rendered checkbox no longer
     *   exists in the live body (stale preview). A missing change is also null.
     */
    fun toggle(markdown: String, lineNumber: Int, text: String, checked: Boolean): String? {
        // Split on LF only, so a CRLF body keeps its `\r` on every line and the join below writes
        // it back unchanged. `String.lines()` would normalize the separators.
        val lines = markdown.split('\n').toMutableList()
        val target = findTarget(lines, lineNumber, text) ?: return null
        val updatedRaw = toggleMarker(lines[target], checked) ?: return null
        if (updatedRaw == lines[target]) return null
        lines[target] = updatedRaw
        return lines.joinToString("\n")
    }

    /**
     * UX-08: the preview page path, where [lineNumber] and [text] come from parsing the same
     * [markdown] string.
     *
     * The old code rebuilt the line as `"$prefix ${element.text}"`, and `element.text` is trimmed:
     * ticking an indented child moved it to column 0 (where it can read as a code block), a
     * malformed `[ ]task` became `[x] [ ]task`, and joining with `\n` turned every CRLF into LF.
     * This finds the line by number, checks it still carries [text] (a debounced snapshot may have
     * moved on), and rewrites only the marker.
     *
     * @return the updated body, or null when the line is no longer that checkbox / cannot change.
     */
    fun toggleRenderedLine(
        markdown: String,
        lineNumber: Int,
        text: String,
        checked: Boolean
    ): String? {
        // Split on LF only - see [toggle]. The join below never touches a `\r`.
        val lines = markdown.split('\n')
        if (lineNumber !in lines.indices) return null
        val raw = lines[lineNumber]
        if (!matches(raw, text)) return null
        val updatedRaw = toggleMarker(raw, checked) ?: return null
        if (updatedRaw == raw) return null
        val updated = lines.toMutableList()
        updated[lineNumber] = updatedRaw
        return updated.joinToString("\n")
    }

    /**
     * Replaces the `[ ]`/`[x]` marker at the line's first non-whitespace position, keeping the
     * indentation, the rest of the line and a trailing `\r` exactly as they were.
     *
     * Returns null when the line is not a checkbox or already carries the requested marker. The
     * separator space is added only when the text is glued to the marker (`[ ]task`), so a second
     * toggle cannot leave two markers behind.
     */
    private fun toggleMarker(rawLine: String, checked: Boolean): String? {
        val carriageReturn = rawLine.endsWith('\r')
        val line = if (carriageReturn) rawLine.dropLast(1) else rawLine
        var indent = 0
        while (indent < line.length && line[indent].isWhitespace()) indent++
        if (indent >= line.length) return null
        if (!line.startsWith("[ ]", indent) && !line.startsWith("[x]", indent)) return null

        val rest = line.substring(indent + 3)
        val separator = if (rest.isEmpty() || rest.first().isWhitespace()) "" else " "
        val marker = if (checked) "[x]" else "[ ]"
        val updatedLine = line.substring(0, indent) + marker + separator + rest
        return if (carriageReturn) "$updatedLine\r" else updatedLine
    }

    private fun findTarget(lines: List<String>, lineNumber: Int, text: String): Int? {
        val hinted = lines.getOrNull(lineNumber)
        if (hinted != null && matches(hinted, text)) return lineNumber
        // R3-04: the hinted line is still a checkbox, just not this label - the checkbox itself was
        // edited (or a duplicate earlier in the note was tapped). Falling back to a text match
        // would toggle a *different* row; a second tap on the re-rendered line is safer.
        if (hinted != null && isCheckbox(hinted)) return null

        // The hint was stale for a structural reason (typing above shifted every line, or the
        // parsed list came from a previous snapshot). Only a unique text match is unambiguous.
        val matches = lines.indices.filter { matches(lines[it], text) }
        return matches.singleOrNull()
    }

    private fun isCheckbox(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("[ ]") || trimmed.startsWith("[x]")
    }

    private fun matches(line: String, text: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("[ ]") && !trimmed.startsWith("[x]")) return false
        return trimmed.substring(3).trimStart().trimEnd() == text.trim()
    }
}

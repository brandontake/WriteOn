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
        val raw = lines[target]
        val carriageReturn = raw.endsWith('\r')
        val line = if (carriageReturn) raw.dropLast(1) else raw
        val indent = line.indexOfFirst { !it.isWhitespace() }
        if (indent < 0) return null
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("[ ]") && !trimmed.startsWith("[x]")) return null

        val remainder = trimmed.substring(3).trimStart()
        val marker = if (checked) "[x]" else "[ ]"
        val updatedLine = line.substring(0, indent) + marker + " " + remainder
        val updatedRaw = if (carriageReturn) "$updatedLine\r" else updatedLine
        if (updatedRaw == raw) return null
        lines[target] = updatedRaw
        return lines.joinToString("\n")
    }

    private fun findTarget(lines: List<String>, lineNumber: Int, text: String): Int? {
        if (lineNumber in lines.indices && matches(lines[lineNumber], text)) return lineNumber

        // The hint was stale (typing above shifted it, or the parsed line list came from a previous
        // snapshot). A unique text match is unambiguous; duplicates fall back to the closest line.
        val matches = lines.indices.filter { matches(lines[it], text) }
        if (matches.isEmpty()) return null
        if (matches.size == 1) return matches.single()
        return matches.minByOrNull { kotlin.math.abs(it - lineNumber) }
    }

    private fun matches(line: String, text: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("[ ]") && !trimmed.startsWith("[x]")) return false
        return trimmed.substring(3).trimStart().trimEnd() == text.trim()
    }
}

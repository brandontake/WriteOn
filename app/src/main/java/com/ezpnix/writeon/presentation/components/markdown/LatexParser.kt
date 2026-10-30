package com.ezpnix.writeon.presentation.components.markdown

/**
 * The small LaTeX subset WriteOn renders without shipping a TeX engine or a WebView.
 *
 * A note is still plain Markdown. `$...$` is inline math and a `$$` fenced block is display math;
 * both are parsed into the same tiny tree here so the inline and block renderers cannot disagree
 * about what an expression means. Anything the parser does not know is kept literally, so an
 * unsupported command degrades to the source the writer typed instead of disappearing.
 */
internal sealed interface LatexNode {
    data class Text(val text: String) : LatexNode

    data class Row(val children: List<LatexNode>) : LatexNode

    data class Fraction(
        val numerator: List<LatexNode>,
        val denominator: List<LatexNode>
    ) : LatexNode

    data class Root(val body: List<LatexNode>) : LatexNode

    data class Script(
        val base: LatexNode?,
        val superscript: List<LatexNode>?,
        val subscript: List<LatexNode>?
    ) : LatexNode
}

/** A `$...$` span found in ordinary text. [start] and [endExclusive] include the dollar signs. */
internal data class InlineMathSpan(
    val start: Int,
    val endExclusive: Int,
    val expression: String
)

/**
 * The text after every inline formula has been replaced by a private-use placeholder, plus the
 * expression each placeholder stands for.
 *
 * Placeholders keep the formula out of the Markdown style scanner: without them the `_` in `x_1`
 * would be read as italics and the `*` in `a*b` as bold.
 */
internal data class InlineMathExtraction(
    val text: String,
    val expressions: Map<Char, String>
)

internal object LatexParser {

    /** First and last private-use code points used for inline formula placeholders. */
    const val PLACEHOLDER_FIRST = 0xE100
    const val PLACEHOLDER_LAST = 0xE6FF

    private val SPECIAL = charArrayOf('\\', '{', '}', '^', '_', '&', '~', '%')

    private val SYMBOLS = mapOf(
        // Greek, lower case.
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "omicron" to "ο", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ",
        "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ",
        "upsilon" to "υ", "phi" to "φ", "varphi" to "ϕ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω",
        // Greek, upper case.
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
        "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        // Operators and relations.
        "times" to "×", "div" to "÷", "cdot" to "·", "cdots" to "⋯",
        "ldots" to "…", "dots" to "…", "pm" to "±", "mp" to "∓",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
        "neq" to "≠", "ne" to "≠", "approx" to "≈", "sim" to "∼",
        "simeq" to "≃", "equiv" to "≡", "propto" to "∝", "infty" to "∞",
        "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫",
        "iint" to "∬", "iiint" to "∭", "oint" to "∮", "partial" to "∂",
        "nabla" to "∇", "forall" to "∀", "exists" to "∃", "nexists" to "∄",
        "emptyset" to "∅", "varnothing" to "∅", "in" to "∈", "notin" to "∉",
        "ni" to "∋", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃",
        "supseteq" to "⊇", "cup" to "∪", "cap" to "∩", "setminus" to "∖",
        "land" to "∧", "lor" to "∨", "neg" to "¬", "not" to "¬",
        "oplus" to "⊕", "otimes" to "⊗", "perp" to "⊥", "parallel" to "∥",
        "angle" to "∠", "degree" to "°", "prime" to "′", "therefore" to "∴",
        "because" to "∵", "rightarrow" to "→", "to" to "→", "leftarrow" to "←",
        "gets" to "←", "leftrightarrow" to "↔", "Rightarrow" to "⇒",
        "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "implies" to "⇒",
        "iff" to "⇔", "mapsto" to "↦", "uparrow" to "↑", "downarrow" to "↓",
        "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ",
        // Delimiters and punctuation.
        "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋",
        "lceil" to "⌈", "rceil" to "⌉", "lbrace" to "{", "rbrace" to "}",
        "vert" to "|", "Vert" to "‖", "star" to "⋆", "ast" to "∗",
        "circ" to "∘", "bullet" to "•", "diamond" to "⋄", "triangle" to "△",
        "square" to "□", "checkmark" to "✓", "dagger" to "†", "ddagger" to "‡",
        "S" to "§", "P" to "¶", "copyright" to "©", "pounds" to "£", "euro" to "€",
        "quad" to " ", "qquad" to "  ", "enspace" to " ", "thinspace" to " ",
        "negthinspace" to ""
    )

    private val FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh", "log", "ln", "lg", "exp", "lim", "max", "min",
        "sup", "inf", "det", "gcd", "mod", "bmod"
    )

    /** Parses one expression into a render tree. */
    fun parse(expression: String): List<LatexNode> =
        Parser(expression.trim()).parseAll()

    /** Splits a display expression on `\\` and parses every line. */
    fun parseLines(expression: String): List<List<LatexNode>> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < expression.length) {
            val char = expression[index]
            if (char == '\\' && index + 1 < expression.length) {
                val next = expression[index + 1]
                if (next == '\\') {
                    lines.add(current.toString())
                    current.setLength(0)
                    index += 2
                    continue
                }
                // Keep other commands intact; only the line separator is special here.
                current.append(char).append(next)
                index += 2
                continue
            }
            current.append(char)
            index++
        }
        lines.add(current.toString())
        return lines.map { parse(it) }
    }

    /**
     * Finds inline `$...$` spans.
     *
     * A `$$` pair is display math and is left alone. `\$` is a literal dollar sign. A formula may
     * not cross a line break, which keeps a stray dollar in prose from swallowing the rest of a note.
     */
    fun findInlineMath(input: String): List<InlineMathSpan> {
        if ('$' !in input) return emptyList()
        val spans = mutableListOf<InlineMathSpan>()
        var index = 0
        while (index < input.length) {
            val char = input[index]
            if (char == '\\' && index + 1 < input.length) {
                index += 2
                continue
            }
            if (char != '$') {
                index++
                continue
            }
            if (index + 1 < input.length && input[index + 1] == '$') {
                index += 2
                continue
            }
            var close = index + 1
            while (close < input.length) {
                val candidate = input[close]
                if (candidate == '\\' && close + 1 < input.length) {
                    close += 2
                    continue
                }
                if (candidate == '\n') break
                if (candidate == '$') break
                close++
            }
            if (close < input.length && input[close] == '$' && close > index + 1) {
                spans.add(InlineMathSpan(index, close + 1, input.substring(index + 1, close)))
                index = close + 1
            } else {
                index++
            }
        }
        return spans
    }

    /** Replaces inline formulas with placeholders and returns the expressions they stand for. */
    fun extractInline(input: String): InlineMathExtraction {
        val spans = findInlineMath(input)
        if (spans.isEmpty()) return InlineMathExtraction(input, emptyMap())
        val output = StringBuilder(input.length)
        val expressions = LinkedHashMap<Char, String>()
        var last = 0
        var nextPlaceholder = PLACEHOLDER_FIRST
        for (span in spans) {
            if (span.start > last) output.append(input, last, span.start)
            if (nextPlaceholder <= PLACEHOLDER_LAST) {
                val placeholder = nextPlaceholder.toChar()
                output.append(placeholder)
                expressions[placeholder] = span.expression
                nextPlaceholder++
            } else {
                // A note with thousands of formulas is not worth a wider placeholder range; keep the
                // rest as written rather than reusing a placeholder and rendering the wrong formula.
                output.append(input, span.start, span.endExclusive)
            }
            last = span.endExclusive
        }
        if (last < input.length) output.append(input, last, input.length)
        return InlineMathExtraction(output.toString(), expressions)
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parseAll(): List<LatexNode> = parseNodes(stop = null)

        private fun parseNodes(stop: Char?): List<LatexNode> {
            val nodes = mutableListOf<LatexNode>()
            while (index < source.length) {
                val char = source[index]
                if (stop != null && char == stop) break
                when (char) {
                    '\\' -> nodes.add(parseCommand())
                    '{' -> {
                        index++
                        val inner = parseNodes('}')
                        if (index < source.length && source[index] == '}') index++
                        nodes.add(LatexNode.Row(inner))
                    }
                    '}' -> {
                        index++
                        if (stop == null) nodes.add(LatexNode.Text("}"))
                    }
                    '^' -> {
                        index++
                        attachScript(nodes, superscript = parseArgument(), subscript = null)
                    }
                    '_' -> {
                        index++
                        attachScript(nodes, superscript = null, subscript = parseArgument())
                    }
                    '&', '~' -> {
                        index++
                        nodes.add(LatexNode.Text(" "))
                    }
                    '%' -> {
                        while (index < source.length && source[index] != '\n') index++
                    }
                    else -> {
                        val start = index
                        while (index < source.length && source[index] !in SPECIAL) index++
                        if (index == start) index++
                        nodes.add(LatexNode.Text(normalizeSpaces(source.substring(start, index))))
                    }
                }
            }
            return mergeText(nodes)
        }

        private fun parseCommand(): LatexNode {
            index++ // backslash
            if (index >= source.length) return LatexNode.Text("\\")
            if (!source[index].isLetter()) {
                val symbol = source[index]
                index++
                return when (symbol) {
                    '\\' -> LatexNode.Text(" ")
                    ',', ';', ':', '!', ' ' -> LatexNode.Text(" ")
                    '{' -> LatexNode.Text("{")
                    '}' -> LatexNode.Text("}")
                    '%' -> LatexNode.Text("%")
                    '$' -> LatexNode.Text("$")
                    '_' -> LatexNode.Text("_")
                    '^' -> LatexNode.Text("^")
                    '#' -> LatexNode.Text("#")
                    '&' -> LatexNode.Text(" ")
                    else -> LatexNode.Text("\\$symbol")
                }
            }
            val start = index
            while (index < source.length && source[index].isLetter()) index++
            val name = source.substring(start, index)
            return when (name) {
                "frac" -> LatexNode.Fraction(parseArgument(), parseArgument())
                "sqrt" -> LatexNode.Root(parseArgument())
                "text", "mathrm", "mathbf", "mathit", "operatorname" ->
                    LatexNode.Text(parseRawGroup())
                "left", "right" -> LatexNode.Text("")
                else -> {
                    val symbol = SYMBOLS[name]
                    when {
                        symbol != null -> LatexNode.Text(symbol)
                        name in FUNCTIONS -> LatexNode.Text("$name ")
                        // Unknown commands stay visible: a writer can see what the renderer did not
                        // understand instead of silently losing it.
                        else -> LatexNode.Text("\\$name")
                    }
                }
            }
        }

        private fun parseArgument(): List<LatexNode> {
            skipSpaces()
            if (index >= source.length) return emptyList()
            return when (source[index]) {
                '{' -> {
                    index++
                    val inner = parseNodes('}')
                    if (index < source.length && source[index] == '}') index++
                    inner
                }
                '\\' -> listOf(parseCommand())
                else -> {
                    val start = index
                    if (source[index].isDigit()) {
                        while (index < source.length && source[index].isDigit()) index++
                    } else {
                        index++
                    }
                    listOf(LatexNode.Text(source.substring(start, index)))
                }
            }
        }

        private fun parseRawGroup(): String {
            skipSpaces()
            if (index >= source.length || source[index] != '{') {
                return if (index < source.length) source[index++].toString() else ""
            }
            index++
            val out = StringBuilder()
            var depth = 1
            while (index < source.length && depth > 0) {
                val char = source[index]
                when (char) {
                    '{' -> {
                        depth++
                        out.append(char)
                    }
                    '}' -> {
                        depth--
                        if (depth > 0) out.append(char)
                    }
                    else -> out.append(char)
                }
                index++
            }
            return out.toString()
        }

        private fun attachScript(
            nodes: MutableList<LatexNode>,
            superscript: List<LatexNode>?,
            subscript: List<LatexNode>?
        ) {
            val last = nodes.removeLastOrNull()
            if (last is LatexNode.Script) {
                nodes.add(
                    last.copy(
                        superscript = superscript ?: last.superscript,
                        subscript = subscript ?: last.subscript
                    )
                )
            } else {
                nodes.add(LatexNode.Script(last, superscript, subscript))
            }
        }

        private fun skipSpaces() {
            while (index < source.length && source[index].isWhitespace()) index++
        }
    }

    private fun normalizeSpaces(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var previousSpace = false
        for (char in text) {
            val isSpace = char == ' ' || char == '\t' || char == '\n' || char == '\r'
            if (isSpace) {
                if (!previousSpace) out.append(' ')
                previousSpace = true
            } else {
                out.append(char)
                previousSpace = false
            }
        }
        return out.toString()
    }

    private fun mergeText(nodes: List<LatexNode>): List<LatexNode> {
        val merged = mutableListOf<LatexNode>()
        for (node in nodes) {
            val previous = merged.lastOrNull()
            if (node is LatexNode.Text && previous is LatexNode.Text) {
                merged[merged.lastIndex] = LatexNode.Text(previous.text + node.text)
            } else if (node is LatexNode.Text && node.text.isEmpty()) {
                continue
            } else {
                merged.add(node)
            }
        }
        return merged
    }
}

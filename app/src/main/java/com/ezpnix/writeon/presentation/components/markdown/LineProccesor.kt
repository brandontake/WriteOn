package com.ezpnix.writeon.presentation.components.markdown

import com.ezpnix.writeon.core.attachment.AttachmentSyntax

interface MarkdownLineProcessor {
    fun canProcessLine(line: String): Boolean
    fun processLine(line: String, builder: MarkdownBuilder)
}

/**
 * `![](attachment://a1b2c3d4)` - a picture stored under an id instead of a device path.
 *
 * Images and recordings share the `ImageInsertion` / `AudioInsertion` elements with the 1.x
 * processors below; only the target changes. That keeps the renderers (Compose, Glance widget,
 * bitmap export) unaware of which syntax produced the element - they all go through
 * [AttachmentRegistry.resolve].
 */
class AttachmentImageProcessor : MarkdownLineProcessor {
    override fun canProcessLine(line: String): Boolean =
        AttachmentSyntax.IMAGE_LINE.matches(line.trim())

    override fun processLine(line: String, builder: MarkdownBuilder) {
        val id = AttachmentSyntax.IMAGE_LINE.matchEntire(line.trim())
            ?.groupValues?.getOrNull(1) ?: return
        builder.add(ImageInsertion(AttachmentSyntax.SCHEME + id))
    }
}

/** `[录音 20261018-2245](attachment://e5f6a7b8)` - a recording with a human caption. */
class AttachmentAudioProcessor : MarkdownLineProcessor {
    override fun canProcessLine(line: String): Boolean =
        AttachmentSyntax.AUDIO_LINE.matches(line.trim())

    override fun processLine(line: String, builder: MarkdownBuilder) {
        val id = AttachmentSyntax.AUDIO_LINE.matchEntire(line.trim())
            ?.groupValues?.getOrNull(2) ?: return
        builder.add(AudioInsertion(AttachmentSyntax.SCHEME + id))
    }
}

/**
 * The single processor pipeline used by every renderer - note cards, the editor, the Glance widget
 * and the image exporter.
 *
 * It used to be copy-pasted in `MarkdownText.kt` and `WidgetText.kt`, which is how the widget ended
 * up unable to show anything the note list could. When the two lists disagree the widget and the
 * note disagree, so there is now exactly one list.
 *
 * The legacy processors stay in the pipeline: a body that the 7 -> 8 migration could not rewrite
 * (password-protected vault notes, text restored from an old backup, anything a user pastes by
 * hand) must keep rendering until it is opened and saved once.
 *
 * [renderLatex] is a user setting and defaults to off: when it is false the `$$` processor is not
 * installed, so a body containing dollar signs keeps rendering exactly as it did before.
 */
fun defaultMarkdownLineProcessors(renderLatex: Boolean = false): List<MarkdownLineProcessor> = buildList {
    add(AttachmentImageProcessor())
    add(AttachmentAudioProcessor())
    if (renderLatex) add(MathBlockProcessor())
    add(TableProcessor())
    add(HeadingProcessor())
    add(HorizontalRuleProcessor())
    add(ListItemProcessor())
    add(CodeBlockProcessor())
    add(QuoteProcessor())
    add(ImageInsertionProcessor())
    add(AudioInsertionProcessor())
    add(CheckboxProcessor())
}


/**
 * A display formula fenced by `$$`:
 *
 * ```
 * $$
 * \frac{a}{b}
 * $$
 * ```
 *
 * The opening and closing fences may share a line with the expression (`$$a^2$$`). An opening
 * fence with no closing fence is left as ordinary text instead of swallowing the rest of the note.
 */
class MathBlockProcessor : MarkdownLineProcessor {
    override fun canProcessLine(line: String): Boolean = line.trim().startsWith("$$")

    override fun processLine(line: String, builder: MarkdownBuilder) {
        val trimmed = line.trim()
        if (trimmed.length > 4 && trimmed.endsWith("$$")) {
            builder.add(MathBlock(trimmed.substring(2, trimmed.length - 2).trim()))
            return
        }

        val expression = StringBuilder(trimmed.removePrefix("$$"))
        var index = builder.lineIndex + 1
        var closed = false
        while (index < builder.lines.size) {
            val next = builder.lines[index]
            val nextTrimmed = next.trim()
            if (nextTrimmed.endsWith("$$")) {
                expression.append('\n').append(nextTrimmed.removeSuffix("$$"))
                builder.lineIndex = index
                closed = true
                break
            }
            expression.append('\n').append(next)
            index++
        }

        if (closed) {
            builder.add(MathBlock(expression.toString().trim()))
        } else {
            // The lines were only read, never consumed, so they are parsed normally afterwards.
            builder.add(NormalText(line))
        }
    }
}


/**
 * A pipe table:
 *
 * ```
 * | a | b |
 * | --- | --- |
 * | 1 | 2 |
 * ```
 *
 * Consecutive rows are consumed as one element so the renderer can draw a grid instead of a run of
 * paragraphs. The row whose cells are all dashes is the alignment row and is dropped. Cells are
 * split on unescaped pipes only, so a cell can contain a literal `\|`.
 */
class TableProcessor : MarkdownLineProcessor {
    override fun canProcessLine(line: String): Boolean = line.trim().startsWith("|")

    override fun processLine(line: String, builder: MarkdownBuilder) {
        val rows = mutableListOf(splitCells(line))

        while (builder.lineIndex + 1 < builder.lines.size) {
            val next = builder.lines[builder.lineIndex + 1]
            if (!next.trim().startsWith("|")) break
            builder.lineIndex++
            rows.add(splitCells(next))
        }

        val body = rows.filterNot { row -> row.all { ALIGNMENT_CELL.matches(it) } }
        if (body.isEmpty()) {
            builder.add(NormalText(line))
            return
        }
        builder.add(TableElement(body))
    }

    private fun splitCells(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var index = 0
        while (index < trimmed.length) {
            val char = trimmed[index]
            if (char == '\\' && index + 1 < trimmed.length && trimmed[index + 1] == '|') {
                cell.append('|')
                index += 2
                continue
            }
            if (char == '|') {
                cells.add(cell.toString().trim())
                cell.setLength(0)
            } else {
                cell.append(char)
            }
            index++
        }
        cells.add(cell.toString().trim())
        return cells
    }

    private companion object {
        val ALIGNMENT_CELL = Regex("^:?-{2,}:?$")
    }
}


class CodeBlockProcessor : MarkdownLineProcessor {
    override fun canProcessLine(line: String): Boolean {
        return line.startsWith("```")
    }

    override fun processLine(line: String, builder: MarkdownBuilder) {
        val codeBlock = StringBuilder()
        var index = builder.lineIndex + 1
        var isEnded = false

        while (index < builder.lines.size) {
            val nextLine = builder.lines[index]
            if (nextLine == "```") {
                builder.lineIndex = index
                isEnded = true
                break
            }
            codeBlock.appendLine(nextLine)
            index++
        }

        builder.add(CodeBlock(codeBlock.toString(), isEnded, line))
    }
}

class CheckboxProcessor : MarkdownLineProcessor {
    override fun canProcessLine(line: String): Boolean {
        return line.trim().startsWith("[ ]") || line.trim().startsWith("[x]")
    }

    override fun processLine(line: String, builder: MarkdownBuilder) {
        val checked = line.trim().startsWith("[x]")
        val text = line.trim().substringAfter("[ ] ").substringAfter("[x] ").trim()
        builder.add(CheckboxItem(text, checked, lineNumber = builder.lineIndex))
    }
}

class HeadingProcessor : MarkdownLineProcessor {
    override fun canProcessLine(line: String): Boolean = line.startsWith("#")

    override fun processLine(line: String, builder: MarkdownBuilder) {
        val level = line.takeWhile { it == '#' }.length
        val text = line.drop(level).trim()
        builder.add(Heading(level, text))
    }
}

class QuoteProcessor : MarkdownLineProcessor {
    override fun canProcessLine(line: String): Boolean = line.trim().startsWith(">")

    override fun processLine(line: String, builder: MarkdownBuilder) {
        val level = line.takeWhile { it == '>' }.length
        val text = line.drop(level).trim()
        builder.add(Quote(level, text))
    }
}

class ListItemProcessor : MarkdownLineProcessor {
    override fun canProcessLine(line: String): Boolean = line.startsWith("- ")

    override fun processLine(line: String, builder: MarkdownBuilder) {
        val text = line.removePrefix("- ").trim()
        builder.add(ListItem(text))
    }
}

class ImageInsertionProcessor : MarkdownLineProcessor {
    override fun canProcessLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("!(") && trimmed.endsWith(")")
    }

    override fun processLine(line: String, builder: MarkdownBuilder) {
        val photoUri = line.trim().substringAfter("!(", "").substringBefore(")")
        builder.add(ImageInsertion(photoUri))
    }
}

class AudioInsertionProcessor : MarkdownLineProcessor {
    override fun canProcessLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("?audio(") && trimmed.endsWith(")")
    }

    override fun processLine(line: String, builder: MarkdownBuilder) {
        val audioUri = line.trim().substringAfter("?audio(", "").substringBefore(")")
        builder.add(AudioInsertion(audioUri))
    }
}

class HorizontalRuleProcessor : MarkdownLineProcessor {
    override fun canProcessLine(line: String): Boolean {
        val trimmed = line.trim()
        return (trimmed == "---" || trimmed == "***" || trimmed == "___")
    }

    override fun processLine(line: String, builder: MarkdownBuilder) {
        builder.add(HorizontalRule)
    }
}

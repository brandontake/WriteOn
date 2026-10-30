package com.ezpnix.writeon.presentation.components.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * Whether `$...$` and `$$...$$` are rendered as math instead of being shown as written.
 *
 * Off by default. [com.ezpnix.writeon.presentation.MainActivity] provides the persisted setting once
 * for the whole app, so every reader, card, preview and the image exporter agree.
 */
val LocalLatexEnabled = compositionLocalOf { false }

/** Renders one inline formula, including its superscripts and subscripts, as styled text. */
internal fun inlineMath(expression: String): AnnotatedString = buildAnnotatedString {
    appendInlineMath(LatexParser.parse(expression))
}

private fun AnnotatedString.Builder.appendInlineMath(nodes: List<LatexNode>) {
    for (node in nodes) {
        when (node) {
            is LatexNode.Text -> append(node.text)
            is LatexNode.Row -> appendInlineMath(node.children)
            is LatexNode.Fraction -> {
                append("(")
                appendInlineMath(node.numerator)
                append(")/(")
                appendInlineMath(node.denominator)
                append(")")
            }
            is LatexNode.Root -> {
                append("√(")
                appendInlineMath(node.body)
                append(")")
            }
            is LatexNode.Script -> {
                node.base?.let { appendInlineMath(listOf(it)) }
                node.superscript?.let {
                    withStyle(
                        SpanStyle(
                            fontSize = 0.72.em,
                            baselineShift = BaselineShift.Superscript
                        )
                    ) {
                        appendInlineMath(it)
                    }
                }
                node.subscript?.let {
                    withStyle(
                        SpanStyle(
                            fontSize = 0.72.em,
                            baselineShift = BaselineShift.Subscript
                        )
                    ) {
                        appendInlineMath(it)
                    }
                }
            }
        }
    }
}

/**
 * A display formula: fractions stack, roots get a bar, and scripts are drawn at a smaller size.
 *
 * The block is centred like a LaTeX display equation. It deliberately uses the platform serif face
 * so operators and Greek letters come from a font that actually has them; the note's custom font is
 * still used for ordinary prose around the formula.
 */
@Composable
internal fun MathBlockView(
    expression: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val lines = remember(expression) { LatexParser.parseLines(expression) }
    val color = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        lines.forEachIndexed { index, line ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            LatexRow(nodes = line, fontSize = fontSize, color = color)
        }
    }
}

@Composable
private fun LatexRow(
    nodes: List<LatexNode>,
    fontSize: TextUnit,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        nodes.forEach { node -> LatexNodeView(node, fontSize, color) }
    }
}

@Composable
private fun LatexNodeView(
    node: LatexNode,
    fontSize: TextUnit,
    color: Color
) {
    when (node) {
        is LatexNode.Text -> Text(
            text = node.text,
            fontSize = fontSize,
            fontFamily = FontFamily.Serif,
            color = color
        )
        is LatexNode.Row -> LatexRow(node.children, fontSize, color)
        is LatexNode.Fraction -> Column(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .padding(horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LatexRow(node.numerator, fontSize, color)
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                thickness = 1.dp,
                color = color.copy(alpha = 0.75f)
            )
            LatexRow(node.denominator, fontSize, color)
        }
        is LatexNode.Root -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "√",
                fontSize = fontSize * 1.15f,
                fontFamily = FontFamily.Serif,
                color = color
            )
            Box(
                modifier = Modifier
                    .drawBehind {
                        drawLine(
                            color = color,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    .padding(top = 3.dp)
            ) {
                LatexRow(node.body, fontSize, color)
            }
        }
        is LatexNode.Script -> Row(verticalAlignment = Alignment.CenterVertically) {
            node.base?.let { LatexNodeView(it, fontSize, color) }
            Column(
                verticalArrangement = when {
                    node.superscript != null && node.subscript == null -> Arrangement.Top
                    node.subscript != null && node.superscript == null -> Arrangement.Bottom
                    else -> Arrangement.Center
                }
            ) {
                node.superscript?.let { LatexRow(it, fontSize * 0.7f, color) }
                node.subscript?.let { LatexRow(it, fontSize * 0.7f, color) }
            }
        }
    }
}

package com.ezpnix.writeon.presentation.components.markdown

import androidx.compose.ui.text.style.BaselineShift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexParserTest {

    @Test
    fun `inline math is extracted and display fences are left alone`() {
        val extraction = LatexParser.extractInline("Area \$a^2\$ and \$\$b\$\$")

        assertEquals("a^2", extraction.expressions.values.single())
        assertTrue(extraction.text.startsWith("Area "))
        assertTrue(extraction.text.endsWith(" and \$\$b\$\$"))
        assertEquals(1, extraction.expressions.size)
    }

    @Test
    fun `escaped dollar is not a formula`() {
        assertTrue(LatexParser.findInlineMath("cost \\\$5 and \\\$6").isEmpty())
    }

    @Test
    fun `an unclosed dollar does not swallow the rest of the line`() {
        assertTrue(LatexParser.findInlineMath("price is \$5 today").isEmpty())
        assertTrue(LatexParser.findInlineMath("a \$b").isEmpty())
    }

    @Test
    fun `a fraction becomes a stacked node`() {
        val fraction = LatexParser.parse("\\frac{a}{b}").single() as LatexNode.Fraction

        assertEquals("a", (fraction.numerator.single() as LatexNode.Text).text)
        assertEquals("b", (fraction.denominator.single() as LatexNode.Text).text)
    }

    @Test
    fun `a root keeps its body`() {
        val root = LatexParser.parse("\\sqrt{x + 1}").single() as LatexNode.Root

        assertEquals("x + 1", (root.body.single() as LatexNode.Text).text)
    }

    @Test
    fun `subscript and superscript attach to the same base`() {
        val script = LatexParser.parse("x_1^2").single() as LatexNode.Script

        assertEquals("x", (script.base as LatexNode.Text).text)
        assertEquals("1", (script.subscript?.single() as LatexNode.Text).text)
        assertEquals("2", (script.superscript?.single() as LatexNode.Text).text)
    }

    @Test
    fun `known symbols are translated and unknown commands stay visible`() {
        assertEquals("α", (LatexParser.parse("\\alpha").single() as LatexNode.Text).text)
        assertEquals("\\unknown", (LatexParser.parse("\\unknown").single() as LatexNode.Text).text)
    }

    @Test
    fun `parseLines splits on a double backslash`() {
        val lines = LatexParser.parseLines("a \\\\ b")

        assertEquals(2, lines.size)
        assertEquals("a", (lines[0].single() as LatexNode.Text).text)
        assertEquals("b", (lines[1].single() as LatexNode.Text).text)
    }

    @Test
    fun `display block is consumed as one element when latex is on`() {
        val builder = MarkdownBuilder(
            lines = listOf("\$\$", "\\frac{1}{2}", "\$\$", "after"),
            lineProcessors = defaultMarkdownLineProcessors(renderLatex = true)
        )
        builder.parse()

        val block = builder.content.first() as MathBlock
        assertEquals("\\frac{1}{2}", block.expression)
        assertTrue(builder.content.last() is NormalText)
    }

    @Test
    fun `same line display block is supported`() {
        val builder = MarkdownBuilder(
            lines = listOf("\$\$a^2\$\$"),
            lineProcessors = defaultMarkdownLineProcessors(renderLatex = true)
        )
        builder.parse()

        assertEquals("a^2", (builder.content.single() as MathBlock).expression)
    }

    @Test
    fun `unclosed display block stays plain text`() {
        val builder = MarkdownBuilder(
            lines = listOf("\$\$", "x"),
            lineProcessors = defaultMarkdownLineProcessors(renderLatex = true)
        )
        builder.parse()

        assertTrue(builder.content.none { it is MathBlock })
        assertEquals(2, builder.content.size)
    }

    @Test
    fun `latex off keeps dollar signs as ordinary text`() {
        val builder = MarkdownBuilder(
            lines = listOf("\$\$x\$\$"),
            lineProcessors = defaultMarkdownLineProcessors(renderLatex = false)
        )
        builder.parse()

        assertTrue(builder.content.none { it is MathBlock })
        assertEquals("\$\$x\$\$", (builder.content.single() as NormalText).text)
    }

    @Test
    fun `inline formula becomes styled text when enabled`() {
        val output = buildString("area \$x^2\$", latexEnabled = true)

        assertEquals("area x2", output.text)
        assertTrue(output.spanStyles.any { it.item.baselineShift == BaselineShift.Superscript })
    }

    @Test
    fun `inline formula keeps its source when disabled`() {
        val output = buildString("area \$x^2\$")

        assertEquals("area \$x^2\$", output.text)
        assertFalse(output.spanStyles.any { it.item.baselineShift != null })
    }
}

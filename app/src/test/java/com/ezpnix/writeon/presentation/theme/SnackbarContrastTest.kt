package com.ezpnix.writeon.presentation.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-07: the snackbar's message, action label and status icon sit on `inverseSurface`.
 *
 * The component now paints all of them with `inverseOnSurface`; `inversePrimary` is only a
 * fallback accent for any other inverse-surface consumer and must stay at least 3:1 (non-text).
 * R3: the assertion follows the token the snackbar actually uses, so a future switch back to
 * `inversePrimary` becomes visible in this test.
 */
class SnackbarContrastTest {

    @Test
    fun `snackbar content meets AA on the inverse surface in both schemes`() {
        listOf("light" to lightScheme, "dark" to darkScheme).forEach { (name, scheme) ->
            val background = scheme.inverseSurface
            val text = contrastRatio(scheme.inverseOnSurface, background)
            val accent = contrastRatio(scheme.inversePrimary, background)
            assertTrue("$name inverseOnSurface is only ${"%.2f".format(text)}:1", text >= 4.5)
            assertTrue("$name inversePrimary is only ${"%.2f".format(accent)}:1", accent >= 3.0)
        }
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }
}

package com.ezpnix.writeon.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The rounded-corner size the user picked in Settings > Colours, in dp.
 *
 * Every corner the app designs itself derives from this value instead of a hard-coded dp literal, so
 * that one setting stays in charge of the whole look. Elements that are much smaller than a card
 * (thumbnails, chips, colour swatches) scale it down by the same fifth the settings rows use, and
 * bars or pills take half of it - mirroring how
 * [com.ezpnix.writeon.presentation.screens.settings.settings.shapeManager] treats a full-width block.
 */
val LocalCornerRadius = staticCompositionLocalOf<Dp> { 32.dp }

/**
 * Fractions of the user's radius used across the app.
 *
 * Each value reproduces the fixed literal the app used before the radius became a setting (at the
 * default radius of 32 dp), so a default install looks unchanged while every corner now follows
 * Settings > Colours.
 */
object CornerFraction {
    /** 4 dp - thumbnails and inline chips. */
    const val TINY = 0.125f

    /** 8 dp - colour swatches and other small tiles. */
    const val SMALLER = 0.25f

    /** 12 dp - compact cards and secondary buttons. */
    const val SMALL = 0.375f

    /** 16 dp - code blocks and medium containers. */
    const val MEDIUM = 0.5f

    /** 24 dp - large buttons and floating containers. */
    const val LARGE = 0.75f

    /** 32 dp - cards, bars, sheets and other full-size surfaces. */
    const val FULL = 1f
}

/**
 * A [RoundedCornerShape] carrying the user's radius, optionally scaled to [fraction].
 *
 * Use [fraction] below 1f for controls that are noticeably smaller than a card, so that a large
 * radius setting never turns a small element into a circle.
 */
@Composable
fun appCornerShape(fraction: Float = CornerFraction.FULL): RoundedCornerShape {
    val radius = LocalCornerRadius.current
    // RoundedCornerShape has structural equality, so the cached instance survives recomposition
    // and every card/code block stops allocating a fresh shape per frame.
    return remember(radius, fraction) { RoundedCornerShape(radius * fraction) }
}

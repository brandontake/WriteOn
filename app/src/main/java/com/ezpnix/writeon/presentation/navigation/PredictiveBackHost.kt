package com.ezpnix.writeon.presentation.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.CancellationException

/**
 * Wraps the navigation host with a Material style predictive back preview: while a system back
 * gesture is in progress the whole UI scales down and fades slightly, and completing the gesture
 * performs the actual back navigation. Cancelling the gesture restores the UI.
 *
 * The [PredictiveBackHandler] is composed *after* [content], so its callback is registered with
 * the [androidx.activity.OnBackPressedDispatcher] later than the one the navigation library adds
 * for the [androidx.navigation.compose.NavHost]. Later callbacks win, therefore this handler takes
 * over the back gesture while it is enabled and the navigation library's own predictive handling
 * is bypassed. Disabling the handler (at the root destination) hands control back to the default
 * behaviour, while [showPreview] only decides whether the scale/fade preview is rendered.
 */
@Composable
fun PredictiveBackHost(
    enabled: Boolean,
    showPreview: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val progress = remember { Animatable(0f) }
    // Any back gesture that dismisses a screen should also drop text field focus, otherwise a still
    // focused editor keeps drawing its blinking caret while the screen animates away.
    val focusManager = LocalFocusManager.current

    Box(
        modifier = modifier.graphicsLayer {
            val p = if (showPreview) progress.value else 0f
            scaleX = 1f - 0.08f * p
            scaleY = 1f - 0.08f * p
            alpha = 1f - 0.2f * p
        }
    ) {
        content()
    }

    if (enabled) {
        PredictiveBackHandler {
            focusManager.clearFocus(force = true)
            try {
                it.collect { event ->
                    if (showPreview) progress.snapTo(event.progress)
                }
                // The gesture completed: restore and navigate.
                onBack()
                progress.snapTo(0f)
            } catch (_: CancellationException) {
                // The gesture was cancelled: animate the UI back to rest.
                progress.animateTo(0f, tween(durationMillis = 150))
            }
        }
    }
}

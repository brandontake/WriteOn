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
 *
 * [isOverlayOpen] / [onCollapseOverlay] give a screen one more, purely local layer on top of that:
 * when a full screen overlay is up (the home quick action bar), a completed gesture closes the
 * overlay instead of navigating. A screen's own `BackHandler` cannot do this — it is never invoked
 * for a gesture — which is why an expanded bar used to send the swipe through to the navigation
 * host and finish the activity at the root destination.
 *
 * **Ordering caveat.** `PredictiveBackHandler` registers itself on the activity's
 * `OnBackPressedDispatcher` at the point of composition, and the dispatcher runs the *last*
 * registered enabled callback first. This composable is emitted *before* `content`, so the
 * navigation library's own handler — registered while `NavHost` composes — is registered after
 * this one and therefore wins. Relying on "last wins" only works if nothing else registers later.
 *
 * That is why the overlay case is additionally covered by [overlayBackHandler], which is composed
 * *inside* the screen that owns the overlay. Being the deepest composable to register, it is the
 * last one enabled and therefore the one that receives the gesture; the activity level handler stays
 * disabled while an overlay is open so the two can never both act on one swipe.
 */
@Composable
fun PredictiveBackHost(
    enabled: Boolean,
    showPreview: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isOverlayOpen: Boolean = false,
    onCollapseOverlay: (() -> Unit)? = null,
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

    // While an overlay owns the gesture, [overlayBackHandler] inside that screen is the registered
    // callback and must not be raced by this one.
    if (enabled || isOverlayOpen) {
        PredictiveBackHandler(enabled = !isOverlayOpen) {
            focusManager.clearFocus(force = true)
            try {
                it.collect { event ->
                    if (showPreview) progress.snapTo(event.progress)
                }
                // The gesture completed. Close the overlay first: it belongs to the current screen,
                // so leaving it up and navigating away would drop the user's place in the back stack.
                if (isOverlayOpen && onCollapseOverlay != null) {
                    onCollapseOverlay()
                } else {
                    onBack()
                }
                progress.snapTo(0f)
            } catch (_: CancellationException) {
                // The gesture was cancelled: animate the UI back to rest.
                progress.animateTo(0f, tween(durationMillis = 150))
            }
        }
    }
}

/**
 * Handles a completed system back *gesture* for a full screen overlay owned by the current screen.
 *
 * A screen's `BackHandler` is never invoked for a gesture, and the activity level
 * [PredictiveBackHost] registers *before* the navigation library's handler, so the swipe reaches
 * `NavHost` and finishes the activity at the root destination. Composing this inside the screen
 * that owns the overlay makes it the deepest, last-registered handler while enabled, so it is the
 * one that runs; the caller must keep [PredictiveBackHost.isOverlayOpen] in sync so the activity
 * level handler stays disabled at the same time.
 *
 * The gesture is consumed without a progress callback on purpose: the overlay has no shared
 * preview to animate, it simply collapses on completion. A cancelled gesture does nothing, which
 * matches how the overlay already behaves.
 */
@Composable
fun overlayBackHandler(
    enabled: Boolean,
    onCollapse: () -> Unit
) {
    PredictiveBackHandler(enabled = enabled) {
        onCollapse()
    }
}
package com.ezpnix.writeon.presentation.navigation

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
 * The dispatcher runs the *last* registered enabled callback first, and `PredictiveBackHandler`
 * registers from an effect that is applied in composition order. This composable emits `content`
 * first and its handler last, so its callback is applied — and therefore registered — after the one
 * the navigation library adds while `NavHost` composes. That is what makes this handler take over
 * the back gesture from the navigation host while it is enabled. Disabling it (at the root
 * destination) hands control back to the default behaviour, while [showPreview] only decides
 * whether the scale/fade preview is rendered.
 *
 * [isOverlayOpen] tells this host that another screen owns the back gesture right now (the home
 * quick action bar, or a set of selected notes). A screen's own `BackHandler` cannot claim a
 * gesture — it is never invoked for one — which is why an expanded bar used to send the swipe
 * through to the navigation host and finish the activity at the root destination.
 *
 * **Ordering, measured on device.** Composition *nesting* is not what decides the winner, only
 * composition *order* is. Anything composed inside `content` registers earlier, so a
 * `PredictiveBackHandler` declared inside `HomeView` loses to both the navigation host and this
 * host. At the root destination the navigation callback's `popBackStack()` then simply returns
 * false and the swipe does nothing at all — which is precisely the "back gesture does nothing"
 * symptom the overlay case used to have.
 *
 * That is why the overlay case is covered by [overlayBackHandler] instead of a
 * `PredictiveBackHandler`: it registers directly on the dispatcher when the overlay opens, i.e.
 * after every composition-time registration has happened, so it is last. This host keeps itself
 * disabled while an overlay is open so the two can never both act on one swipe.
 */
@Composable
fun PredictiveBackHost(
    enabled: Boolean,
    showPreview: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isOverlayOpen: Boolean = false,
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

    // While an overlay owns the gesture, [overlayBackHandler] is the registered callback and must not
    // be raced by this one. [isOverlayOpen] is only that signal: collapsing the overlay is that
    // handler's job, and doing it here as well would mean two owners for one swipe.
    if (enabled && !isOverlayOpen) {
        PredictiveBackHandler {
            focusManager.clearFocus(force = true)
            try {
                it.collect { event ->
                    if (showPreview) progress.snapTo(event.progress)
                }
                // The gesture completed: leave the current screen.
                onBack()
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
 * **Why this does not use `PredictiveBackHandler`.** A screen's `BackHandler` never sees a gesture,
 * and `PredictiveBackHandler` only wins the gesture by being the *last* callback registered on the
 * activity's `OnBackPressedDispatcher`. Composition nesting is not a reliable way to achieve that:
 * the navigation library installs its own back callback after the content of `NavHost` has been
 * composed, so a handler declared deeper inside `HomeView` still ends up *before* it. Measured on
 * device, that is exactly what happened — the navigation callback kept winning, and at the root
 * destination its `popBackStack()` simply returned false, so the swipe did nothing at all.
 *
 * Instead this registers directly on the dispatcher, keyed on [enabled]. When an overlay opens the
 * effect body runs at that moment — long after the navigation host composed — so the callback is
 * added last and is the one the dispatcher runs. Closing the overlay removes it again.
 *
 * The callback is intentionally not predictive: collapsing an overlay needs no shared transition
 * preview, and a plain callback still receives the whole system gesture, committing on release.
 */
@Composable
fun overlayBackHandler(
    enabled: Boolean,
    onCollapse: () -> Unit
) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    DisposableEffect(dispatcher, enabled) {
        if (!enabled || dispatcher == null) {
            onDispose { }
        } else {
            val callback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    onCollapse()
                }
            }
            dispatcher.addCallback(callback)
            // There is no public removeCallback(): activity 1.9 only offers addCallback plus
            // OnBackPressedCallback.isEnabled, and the dispatcher's contract is to run the last
            // *enabled* callback, so disabling is how a handler deregisters. (Callback.remove()
            // would not do: it only cancels its own cancellables and leaves itself registered.)
            onDispose { callback.isEnabled = false }
        }
    }
}
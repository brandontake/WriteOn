package com.ezpnix.writeon.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the home screen currently owns the back gesture: the expanded quick action bar with its
 * dimmed backdrop, and/or a set of selected notes.
 *
 * This lives outside the home composable because a system back *gesture* is not delivered to the
 * regular `BackHandler`s that [com.ezpnix.writeon.presentation.screens.home.HomeView] installs — it
 * is delivered to the activity's `OnBackPressedDispatcher`, which runs the *last* registered
 * enabled callback. At the home destination the navigation library owns that one, and its
 * `popBackStack()` returns false at the root, so a swipe with the bar expanded used to do nothing
 * at all.
 *
 * The flag has two readers, and they exist for different reasons:
 *  * [overlayBackHandler] actually collapses/clears the overlay. It registers when the overlay
 *    opens, which is by definition after every composition-time registration, so it wins the
 *    ordering on its own and does not need this flag.
 *  * [PredictiveBackHost] uses it to keep its own handler *disabled* while the home screen owns the
 *    gesture, so the two handlers can never both act on a single swipe.
 *
 * The name is kept from when this flag was the only mechanism, but it now means "back gesture is
 * owned by the home screen" rather than "the quick action bar is expanded".
 */
object HomeOverlayState {

    var expanded by mutableStateOf(false)
        private set

    fun publish(value: Boolean) {
        expanded = value
    }

    fun collapse() {
        expanded = false
    }
}
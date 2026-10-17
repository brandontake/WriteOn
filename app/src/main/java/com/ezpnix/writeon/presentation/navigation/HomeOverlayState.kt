package com.ezpnix.writeon.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the home screen currently has a full screen overlay open (the expanded quick action bar
 * and its dimmed backdrop).
 *
 * This lives outside the home composable because a system back *gesture* is not delivered to the
 * regular `BackHandler` that [com.ezpnix.writeon.presentation.screens.home.HomeView] installs. The
 * gesture goes to whichever `PredictiveBackHandler` was registered last, and at the home
 * destination the navigation library owns that one — so with the bar expanded, a swipe fell through
 * to the root destination and finished the activity instead of collapsing the overlay.
 *
 * Hoisting the flag lets PredictiveBackHost, which is composed after the navigation content and
 * therefore wins the gesture, collapse the overlay first and only then navigate back.
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
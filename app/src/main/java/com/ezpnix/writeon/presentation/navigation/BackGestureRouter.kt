package com.ezpnix.writeon.presentation.navigation

/**
 * Routes a completed system back *gesture* to the screen that owns the exit.
 *
 * [PredictiveBackHost] is the last callback on the activity's back dispatcher, so it - not the
 * screen's own `BackHandler` - receives the gesture. Its default action is a bare
 * `popBackStack()`, which skips the editor's save-before-exit path entirely: the gesture left the
 * destination without running `saveBeforeExit`, so the last paragraph was only written if the
 * ON_STOP save happened to win. A `BackHandler` cannot claim a gesture, and a second
 * `PredictiveBackHandler` would lose to the host, so the host asks this object first instead.
 *
 * The slot is a single handler because only the current destination may own the gesture. The
 * registering screen replaces it on every recomposition and clears it on dispose, and the host
 * falls back to its own pop when nothing (or no longer anything) is registered.
 */
object BackGestureRouter {
    /**
     * The current screen's back handler, or null when the host should handle the gesture itself.
     *
     * Volatile: the host reads it from the main thread, but the editor registers it from a
     * composition effect, so the write must be visible without any extra synchronisation.
     */
    @Volatile
    var handler: (() -> Unit)? = null

    /** Runs [handler] if one is registered and reports whether it consumed the gesture. */
    fun handle(): Boolean {
        val current = handler ?: return false
        current()
        return true
    }
}

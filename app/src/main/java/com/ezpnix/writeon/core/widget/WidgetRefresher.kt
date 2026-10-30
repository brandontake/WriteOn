package com.ezpnix.writeon.core.widget

/**
 * Asks the home-screen widgets to redraw.
 *
 * The real implementation renders Glance widgets, which is a presentation concern; the domain layer
 * only knows that "the note list changed, tell the widgets". A test substitutes a no-op (or a
 * counter), so observing notes no longer drags Glance into a unit test's classpath.
 */
interface WidgetRefresher {
    suspend fun refreshAll()
}

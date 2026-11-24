package com.ezpnix.writeon.presentation.navigation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-01: the screens that own an unsaved buffer publish their exit through this slot instead of
 * relying on a BackHandler, which never sees a predictive back gesture.
 */
class BackGestureRouterTest {

    @After
    fun clearHandler() {
        BackGestureRouter.handler = null
    }

    @Test
    fun `handle reports false and does nothing when no screen is registered`() {
        BackGestureRouter.handler = null

        assertFalse(BackGestureRouter.handle())
    }

    @Test
    fun `handle runs the registered handler and reports that it consumed the gesture`() {
        var invocations = 0
        BackGestureRouter.handler = { invocations++ }

        assertTrue(BackGestureRouter.handle())
        assertTrue(BackGestureRouter.handle())
        assertEquals(2, invocations)
    }
}

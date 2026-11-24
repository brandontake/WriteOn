package com.ezpnix.writeon.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * REG-02: a notification cold start must not leak its note id into later editors.
 *
 * The bug lived in the NavHost dispatch: the activity's launch `noteId` overrode every Edit
 * destination, so after opening note 5 from a pinned notification, tapping note 7 on Home still
 * loaded note 5. The rule now is "the route is the only source", and the parse it uses is pinned
 * here: a filled route carries its own id, and the start destination is just another filled route
 * built from the notification's id.
 */
class EditRouteArgsTest {

    @Test
    fun `each edit route resolves its own note id`() {
        assertEquals(7, editRouteNoteId("7"))
        assertEquals(0, editRouteNoteId("0"))
        assertEquals(0, editRouteNoteId(null))
        assertEquals(0, editRouteNoteId("not-a-number"))
    }

    @Test
    fun `the start destination carries the notification note id`() {
        val route = NavRoutes.Edit.createRoute(5, false)
        assertEquals("edit/5/false", route)
        assertEquals(5, editRouteNoteId(route.split("/")[1]))
    }

    @Test
    fun `a later editor opened from home keeps its own id`() {
        // This is the second tap in the REG-02 scenario: the cold start used note 5, and the route
        // the home grid pushes is note 7. The parse must return 7 - nothing from the launch intent
        // is in scope here at all.
        val later = NavRoutes.Edit.createRoute(7, true)
        assertEquals(7, editRouteNoteId(later.split("/")[1]))
    }
}

package com.ezpnix.writeon.presentation.screens.edit.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R6-01: a card/widget body committed while the editor waited for the save lock must survive when
 * the user has not edited the body in the editor.
 */
class EditBodyMergeTest {

    @Test
    fun `a stored card toggle survives when the editor has no local body edit`() {
        assertEquals(
            "line\n[x] card",
            mergeBodyForSave(
                storedBody = "line\n[x] card",
                lastSavedBody = "line\n[ ] card",
                editorBody = "line\n[ ] card",
            ),
        )
    }

    @Test
    fun `the editor text wins after a real local edit`() {
        assertEquals(
            "line\n[ ] card\nnew paragraph",
            mergeBodyForSave(
                storedBody = "line\n[x] card",
                lastSavedBody = "line\n[ ] card",
                editorBody = "line\n[ ] card\nnew paragraph",
            ),
        )
    }

    @Test
    fun `a missing stored or last body keeps the editor text`() {
        assertEquals("editor", mergeBodyForSave(null, "last", "editor"))
        assertEquals("editor", mergeBodyForSave("stored", null, "editor"))
    }

    @Test
    fun `a stored title change survives when the editor title is unchanged`() {
        assertEquals(
            "card title",
            mergeBodyForSave(
                storedBody = "card title",
                lastSavedBody = "original title",
                editorBody = "original title",
            ),
        )
    }
}

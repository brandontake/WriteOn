package com.ezpnix.writeon.domain.usecase

import com.ezpnix.writeon.domain.model.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * UX-06: the widget refresh has to follow a note's rendered content, not just its id set.
 *
 * The bug this pins: editing a title or ticking a checkbox changed no id, so the widget was left
 * showing the previous text indefinitely. The signature is plain data, so the decision is tested
 * without a database, a collector or Glance.
 */
class WidgetRefreshSignatureTest {

    @Test
    fun `a changed timestamp under the same ids refreshes`() {
        val before = WidgetRefreshSignature.of(listOf(note(updatedAt = 100)))
        val after = WidgetRefreshSignature.of(listOf(note(updatedAt = 101)))

        assertNotEquals("an editor save must invalidate the widget", before, after)
    }

    @Test
    fun `an identical list produces an identical signature`() {
        val first = WidgetRefreshSignature.of(
            listOf(note(id = 1, updatedAt = 5), note(id = 2, updatedAt = 7))
        )
        val second = WidgetRefreshSignature.of(
            listOf(note(id = 2, updatedAt = 7), note(id = 1, updatedAt = 5))
        )

        assertEquals(
            "a row reorder without a content change must not redraw the widget",
            first,
            second
        )
    }

    @Test
    fun `a body edit that does not advance updatedAt still refreshes`() {
        // The home card's inline edit and its checkbox write rebuild the row without stamping
        // updatedAt; the content hash is what catches those writes.
        val before = WidgetRefreshSignature.of(listOf(note(updatedAt = 5, body = "[ ] task")))
        val after = WidgetRefreshSignature.of(listOf(note(updatedAt = 5, body = "[x] task")))

        assertNotEquals(before, after)
    }

    @Test
    fun `a pin change refreshes even when the text is unchanged`() {
        val before = WidgetRefreshSignature.of(listOf(note(pinned = false)))
        val after = WidgetRefreshSignature.of(listOf(note(pinned = true)))

        assertNotEquals(before, after)
    }

    @Test
    fun `a tag-only change does not redraw the widget`() {
        // UX-06: the signature still describes exactly what a widget renders. Tags are not part of
        // it, so a tag write must not cost a launcher redraw (the note itself is still re-read by
        // the list through REG-03's cache invalidation).
        val before = WidgetRefreshSignature.of(listOf(note().copy(tags = emptyList())))
        val after = WidgetRefreshSignature.of(listOf(note().copy(tags = listOf("work"))))

        assertEquals(before, after)
    }

    @Test
    fun `a body edit that keeps its length still changes the hash`() {
        // The hash covers content, not length: a same-length edit ("task" -> "done") must refresh.
        val before = WidgetRefreshSignature.of(listOf(note(body = "[ ] task")))
        val after = WidgetRefreshSignature.of(listOf(note(body = "[x] done")))

        assertNotEquals(before, after)
    }

    private fun note(
        id: Int = 1,
        updatedAt: Long = 0,
        pinned: Boolean = false,
        body: String = "body",
    ) = Note(
        id = id,
        name = "title",
        description = body,
        pinned = pinned,
        updatedAt = updatedAt,
    )
}

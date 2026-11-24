package com.ezpnix.writeon.presentation.screens.home.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TST-05: the batch tag deletion decisions that used to be inline in `HomeViewModel.deleteTags`.
 *
 * The branches covered here are the ones that produced wrong or missing feedback in practice: a
 * single tag still used by another note, several tags at once, casing/whitespace duplicates and
 * deleting the tag the drawer is currently filtered by.
 */
class TagDeletionPolicyTest {

    @Test
    fun `one tag that no note uses is reported as deleted`() {
        val plan = planTagDeletion(
            requestedTags = listOf("work"),
            currentFilter = "all",
            remainingTags = listOf("personal"),
        )

        assertEquals(listOf("work"), plan.targets)
        assertEquals(TagDeletionFeedback.Single("work", stillInUse = false), plan.feedback)
        assertFalse("the filter does not match the deleted tag", plan.resetFilterToAll)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `one tag that other notes still carry is reported as still in use`() {
        val plan = planTagDeletion(
            requestedTags = listOf("Work"),
            currentFilter = "all",
            remainingTags = listOf("work", "personal"),
        )

        assertEquals(TagDeletionFeedback.Single("Work", stillInUse = true), plan.feedback)
    }

    @Test
    fun `several tags share the plural message`() {
        val plan = planTagDeletion(
            requestedTags = listOf("work", "personal"),
            currentFilter = "all",
            remainingTags = listOf("work"),
        )

        assertEquals(listOf("work", "personal"), plan.targets)
        assertEquals(TagDeletionFeedback.Multiple, plan.feedback)
    }

    @Test
    fun `trim blanks and exact duplicates are normalized before deleting`() {
        val plan = planTagDeletion(
            requestedTags = listOf("  Work  ", " Work ", "", "   ", "work"),
            currentFilter = "all",
            remainingTags = emptyList(),
        )

        // `distinct()` is case-sensitive on purpose, exactly like the production inline code: the
        // tag index is lowercased, but the user asked for two differently-cased names and both
        // are passed through to the delete.
        assertEquals(listOf("Work", "work"), plan.targets)
        assertEquals(TagDeletionFeedback.Multiple, plan.feedback)
    }

    @Test
    fun `a fully blank request is a no-op`() {
        val plan = planTagDeletion(
            requestedTags = listOf("", "  ", "\n"),
            currentFilter = "work",
            remainingTags = listOf("work"),
        )

        assertTrue(plan.isEmpty)
        assertEquals(TagDeletionFeedback.None, plan.feedback)
        assertFalse("an empty request must not change the filter", plan.resetFilterToAll)
    }

    @Test
    fun `deleting the active tag filter resets the drawer to all`() {
        val plan = planTagDeletion(
            requestedTags = listOf("Work"),
            currentFilter = "work",
            remainingTags = emptyList(),
        )

        assertTrue("the deleted tag was the active filter", plan.resetFilterToAll)
    }

    @Test
    fun `deleting another tag leaves the active filter alone`() {
        val plan = planTagDeletion(
            requestedTags = listOf("work"),
            currentFilter = "personal",
            remainingTags = listOf("personal"),
        )

        assertFalse(plan.resetFilterToAll)
    }

    @Test
    fun `a multi-tag request also resets the filter when one of them is active`() {
        val plan = planTagDeletion(
            requestedTags = listOf("work", "Personal"),
            currentFilter = "personal",
            remainingTags = emptyList(),
        )

        assertTrue(plan.resetFilterToAll)
    }

    @Test
    fun `the synthetic and pinned filters are never treated as a deleted tag`() {
        for (filter in listOf("all", "pinned", FILTER_UNTAGGED)) {
            val plan = planTagDeletion(
                requestedTags = listOf("work"),
                currentFilter = filter,
                remainingTags = emptyList(),
            )
            assertFalse("filter $filter must not reset for tag deletion", plan.resetFilterToAll)
        }
    }
}

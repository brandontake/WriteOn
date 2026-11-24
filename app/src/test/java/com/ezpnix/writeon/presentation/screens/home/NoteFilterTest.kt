package com.ezpnix.writeon.presentation.screens.home

import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.presentation.screens.home.viewmodel.FILTER_UNTAGGED
import com.ezpnix.writeon.presentation.screens.home.viewmodel.noteMatchesFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFilterTest {

    private fun note(tags: List<String> = emptyList(), pinned: Boolean = false): Note =
        Note(name = "note", description = "", pinned = pinned, tags = tags)

    @Test
    fun `all matches every note`() {
        assertTrue(noteMatchesFilter(note(), "all"))
        assertTrue(noteMatchesFilter(note(tags = listOf("work")), "all"))
    }

    @Test
    fun `pinned matches only pinned notes`() {
        assertTrue(noteMatchesFilter(note(pinned = true), "pinned"))
        assertFalse(noteMatchesFilter(note(pinned = false), "pinned"))
    }

    @Test
    fun `untagged matches only notes without tags`() {
        assertTrue(noteMatchesFilter(note(), FILTER_UNTAGGED))
        assertFalse(noteMatchesFilter(note(tags = listOf("work")), FILTER_UNTAGGED))
    }

    @Test
    fun `a tag filter matches only notes carrying that tag`() {
        assertTrue(noteMatchesFilter(note(tags = listOf("work", "home")), "work"))
        assertFalse(noteMatchesFilter(note(tags = listOf("home")), "work"))
    }

    @Test
    fun `tag matching ignores the casing of a migrated tag`() {
        assertTrue(noteMatchesFilter(note(tags = listOf(" Work ")), "work"))
    }
}

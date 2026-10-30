package com.ezpnix.writeon.presentation.screens.edit.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteImageExporterTest {

    @Test
    fun `content that fits one page is one page`() {
        assertEquals(1, exportPageCount(contentHeightPx = 900, pageHeightPx = 1000, bottomPaddingPx = 120))
    }

    @Test
    fun `content bottom ignores the bottom padding`() {
        // The column is 1100 px tall, but the last 100 px are the footer's bottom padding. Counting
        // that padding used to add a page that only contained background.
        assertEquals(2, exportPageCount(contentHeightPx = 1100, pageHeightPx = 500, bottomPaddingPx = 100))
        assertEquals(1000, exportContentBottomPx(contentHeightPx = 1100, bottomPaddingPx = 100))
    }

    @Test
    fun `content just past a page boundary starts another page`() {
        assertEquals(2, exportPageCount(contentHeightPx = 1100, pageHeightPx = 500, bottomPaddingPx = 100))
        assertEquals(3, exportPageCount(contentHeightPx = 1101, pageHeightPx = 500, bottomPaddingPx = 100))
    }

    @Test
    fun `page count is at least one for empty or invalid measurements`() {
        assertEquals(1, exportPageCount(contentHeightPx = 0, pageHeightPx = 500, bottomPaddingPx = 100))
        assertEquals(1, exportPageCount(contentHeightPx = 100, pageHeightPx = 0, bottomPaddingPx = 0))
        assertEquals(1, exportPageCount(contentHeightPx = 50, pageHeightPx = 500, bottomPaddingPx = 100))
    }

    @Test
    fun `bottom padding cannot make the content bottom negative`() {
        assertEquals(0, exportContentBottomPx(contentHeightPx = 20, bottomPaddingPx = 100))
    }

    @Test
    fun `a page starting after the content bottom has no content`() {
        assertTrue(exportPageHasContent(pageIndex = 0, pageHeightPx = 500, contentBottomPx = 1100))
        assertTrue(exportPageHasContent(pageIndex = 1, pageHeightPx = 500, contentBottomPx = 1100))
        assertTrue(exportPageHasContent(pageIndex = 2, pageHeightPx = 500, contentBottomPx = 1100))
        assertFalse(exportPageHasContent(pageIndex = 3, pageHeightPx = 500, contentBottomPx = 1100))
    }

    @Test
    fun `a single page always counts as content when the note is not empty`() {
        assertTrue(exportPageHasContent(pageIndex = 0, pageHeightPx = 0, contentBottomPx = 10))
        assertFalse(exportPageHasContent(pageIndex = 0, pageHeightPx = 0, contentBottomPx = 0))
    }
}

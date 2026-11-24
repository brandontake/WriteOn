package com.ezpnix.writeon.presentation.screens.edit.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteImageExporterTest {

    @Test
    fun `a picture inside the budget keeps the normal density`() {
        assertEquals(1f, exportCaptureScale(widthPx = 1080, heightPx = 2000)!!, 0.0001f)
        assertEquals(1f, exportCaptureScale(widthPx = 1000, heightPx = 12_000)!!, 0.0001f)
    }

    @Test
    fun `a long note is scaled down to the pixel budget`() {
        val scale = exportCaptureScale(
            widthPx = 1080,
            heightPx = 50_000,
            maxPixels = 20_000_000
        )!!
        assertEquals(0.6086f, scale, 0.001f)
        assertTrue("the width must stay readable", 1080 * scale >= MIN_EXPORT_WIDTH_PX)
        val scaledPixels = (1080 * scale).toLong() * (50_000 * scale).toLong()
        assertTrue("scaled picture must fit the budget", scaledPixels <= 20_000_000)
    }

    @Test
    fun `the device budget is a third of the heap and stops at the ceiling`() {
        assertEquals(5_592_405L, exportPixelBudgetForHeap(64))
        assertEquals(22_369_621L, exportPixelBudgetForHeap(256))
        assertEquals(MAX_EXPORT_PIXELS, exportPixelBudgetForHeap(512))
    }

    @Test
    fun `a note that would become unreadably narrow is rejected`() {
        // 1080 x 200 000 needs a scale of ~0.304 to fit 20 MP, leaving a 328 px wide picture.
        assertNull(exportCaptureScale(widthPx = 1080, heightPx = 200_000))
    }

    @Test
    fun `the dimension ceiling also limits the scale`() {
        val scale = exportCaptureScale(
            widthPx = 1000,
            heightPx = 40_000,
            maxPixels = Long.MAX_VALUE,
            maxDimensionPx = 30_000,
            minWidthPx = 100
        )!!
        assertEquals(0.75f, scale, 0.0001f)
    }

    @Test
    fun `invalid measurements keep the normal density`() {
        assertEquals(1f, exportCaptureScale(widthPx = 0, heightPx = 100)!!, 0.0001f)
        assertEquals(1f, exportCaptureScale(widthPx = 100, heightPx = 0)!!, 0.0001f)
    }
}

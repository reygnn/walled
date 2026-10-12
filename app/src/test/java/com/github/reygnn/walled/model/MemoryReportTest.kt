package com.github.reygnn.walled.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MemoryReportTest {

    private fun row(sample: Int, origW: Int, origH: Int, bytes: Long = 0) =
        MemoryRow(1, 1000, 500, sample, origW, origH, bytes, "HARDWARE")

    @Test
    fun `a row is downsampled only when the source is actually larger`() {
        assertTrue(row(sample = 2, origW = 2000, origH = 1000).isDownsampled)
        assertFalse(row(sample = 1, origW = 1000, origH = 500).isDownsampled)
        // Unknown source dims must not claim a downsample.
        assertFalse(row(sample = 2, origW = 0, origH = 0).isDownsampled)
    }

    @Test
    fun `the total sums the rows`() {
        assertEquals(30L, MemoryReport(listOf(row(1, 0, 0, 10), row(1, 0, 0, 20))).totalBytes)
    }

    @Test
    fun `megabytes are formatted per locale`() {
        assertEquals("12.5 MB", formatMegabytes(13_107_200, Locale.US))
        assertEquals("12,5 MB", formatMegabytes(13_107_200, Locale.GERMANY))
    }
}

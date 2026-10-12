package com.github.reygnn.walled.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decode-budget math, ported from the launcher's `BitmapDownsamplingTest`. */
class BitmapDecodingTest {

    private fun decodedPixels(w: Int, h: Int, sample: Int) = (w.toLong() / sample) * (h.toLong() / sample)

    @Test
    fun `a screen-sized image is not downsampled`() {
        assertEquals(1, calculateInSampleSize(1080, 2424))
    }

    @Test
    fun `a 16 MP photo is downsampled below the budget`() {
        val sample = calculateInSampleSize(4608, 3456)
        assertEquals(2, sample)
        assertTrue(decodedPixels(4608, 3456, sample) <= MAX_LAYER_PIXELS)
    }

    @Test
    fun `a 200 MP monster is still bounded`() {
        val sample = calculateInSampleSize(16384, 12288)
        assertTrue(decodedPixels(16384, 12288, sample) <= MAX_LAYER_PIXELS)
    }

    @Test
    fun `absurd dimensions do not overflow and stay bounded`() {
        val sample = calculateInSampleSize(50000, 50000)
        assertTrue(decodedPixels(50000, 50000, sample) <= MAX_LAYER_PIXELS)
    }

    @Test
    fun `the sample size is always a power of two`() {
        for ((w, h) in listOf(4000 to 3000, 12000 to 9000, 20000 to 525, 7777 to 3333)) {
            val s = calculateInSampleSize(w, h)
            assertEquals("power of two: $s", 0, s and (s - 1))
        }
    }

    @Test
    fun `invalid or unknown dimensions fall back to a full decode`() {
        assertEquals(1, calculateInSampleSize(-1, -1))
        assertEquals(1, calculateInSampleSize(0, 100))
        assertEquals(1, calculateInSampleSize(100, 100, maxPixels = 0))
    }

    @Test
    fun `the per-side cap downsamples a panorama the area budget lets through`() {
        // 20000×525 ≈ 10.5 MP passes the area budget at full resolution.
        assertEquals(1, calculateInSampleSize(20000, 525, maxSide = 0))
        val capped = calculateInSampleSize(20000, 525)
        assertTrue(20000 / capped <= MAX_LAYER_TEXTURE_SIDE)
    }
}

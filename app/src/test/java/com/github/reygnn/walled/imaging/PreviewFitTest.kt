package com.github.reygnn.walled.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewFitTest {

    @Test
    fun `a canvas the size of the frame maps one to one`() {
        assertEquals(PreviewFit(1f, 0f, 0f), PreviewFit.of(1080f, 2424f, 1080, 2424))
    }

    @Test
    fun `a smaller canvas fits the frame and centers it`() {
        // 540×1500 canvas, 1080×2424 frame: k = min(0.5, 0.6188…) = 0.5 → 1212 tall, 144 spare.
        assertEquals(PreviewFit(0.5f, 0f, 144f), PreviewFit.of(540f, 1500f, 1080, 2424))
    }

    @Test
    fun `the zoom enlarges around the canvas center`() {
        val fit = PreviewFit.of(1000f, 2000f, 1000, 2000, zoom = 1.1f)!!
        assertEquals(1.1f, fit.scale, 1e-6f)
        // The frame center stays at the canvas center ...
        assertEquals(500f, fit.toFrameX(500f), 1e-3f)
        assertEquals(1000f, fit.toFrameY(1000f), 1e-3f)
        // ... and the canvas edge now shows less of the frame.
        assertEquals(500f - 500f / 1.1f, fit.toFrameX(0f), 1e-3f)
    }

    @Test
    fun `a finger movement maps to the frame distance under the finger`() {
        val fit = PreviewFit.of(1000f, 2000f, 1000, 2000, zoom = 1.25f)!!
        assertEquals(80f, fit.toFrameX(600f) - fit.toFrameX(500f), 1e-3f)
    }

    @Test
    fun `the factor is clamped to the allowed range`() {
        assertEquals(1f, PreviewFit.clampZoom(0.5f))
        assertEquals(2f, PreviewFit.clampZoom(7f))
        assertEquals(1.12f, PreviewFit.clampZoom(1.12f))
        assertEquals(1f, PreviewFit.clampZoom(Float.NaN))
        assertEquals(1f, PreviewFit.clampZoom(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `no frame or no canvas yields no mapping`() {
        assertNull(PreviewFit.of(0f, 100f, 1080, 2424))
        assertNull(PreviewFit.of(100f, 100f, 0, 2424))
    }
}

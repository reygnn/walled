package com.github.reygnn.walled.imaging

import com.github.reygnn.walled.model.LayerTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LayerGeometry], ported from the launcher's `WallpaperLayerPlacementTest`. The
 * sample-size compensation cases are gone: walled stores the scale per original
 * pixel, so the decode sample size never reaches the placement.
 */
class LayerGeometryTest {

    private val frameW = 300
    private val frameH = 500

    private fun resolve(transform: LayerTransform?, imageW: Int, imageH: Int) =
        LayerGeometry.resolve(transform, imageW, imageH, frameW, frameH)

    @Test
    fun `an unplaced landscape layer covers the height and is centered horizontally`() {
        assertEquals(LayerTransform(2.5f, -350f, 0f), resolve(null, 400, 200))
    }

    @Test
    fun `an unplaced portrait layer covers the width and is centered vertically`() {
        assertEquals(LayerTransform(3f, 0f, -1250f), resolve(null, 100, 1000))
    }

    @Test
    fun `a stored transform inside the zoom range is kept`() {
        assertEquals(LayerTransform(1.5f, 10f, -20f), resolve(LayerTransform(1.5f, 10f, -20f), 300, 500))
    }

    @Test
    fun `a scale below the range is clamped to the floor`() {
        // Base 5 (100 px image in a 300x500 frame) → floor min(0.1, 5 × 0.05) = 0.1.
        assertEquals(0.1f, resolve(LayerTransform(0.001f, 0f, 0f), 100, 100).scale)
    }

    @Test
    fun `a scale above the range is clamped to the ceiling relative to the base`() {
        // Base 5 → ceiling max(10, 5 × 3) = 15.
        assertEquals(15f, resolve(LayerTransform(80f, 0f, 0f), 100, 100).scale)
        // Base 0.1 (huge image) → ceiling max(10, 0.3) = 10.
        assertEquals(10f, resolve(LayerTransform(50f, 0f, 0f), 3000, 5000).scale)
    }

    @Test
    fun `corrupt values are sanitized`() {
        assertEquals(
            LayerTransform(1f, 0f, 0f),
            resolve(LayerTransform(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN), 300, 500),
        )
        assertEquals(1f, resolve(LayerTransform(-2f, 0f, 0f), 300, 500).scale)
    }

    @Test
    fun `the scale range has its floor and ceiling from the base scale`() {
        val small = LayerGeometry.scaleRange(100, 100, frameW, frameH)
        assertEquals(0.1f, small.start)
        assertEquals(15f, small.endInclusive)
        val huge = LayerGeometry.scaleRange(3000, 5000, frameW, frameH)
        assertEquals(0.005f, huge.start, 1e-7f) // 0.1f × 0.05f is not exactly 0.005f
        assertEquals(10f, huge.endInclusive)
    }

    @Test
    fun `clamped applies exactly the scale range`() {
        val range = LayerGeometry.scaleRange(100, 100, frameW, frameH)
        for (scale in listOf(0.0025f, 0.1f, 1.7f, 15f, 80f)) {
            assertEquals(
                scale.coerceIn(range.start, range.endInclusive),
                LayerGeometry.clamped(LayerTransform(scale, 0f, 0f), 100, 100, frameW, frameH).scale,
            )
        }
    }

    @Test
    fun `a pinch inside the range applies exactly the factor around the focus`() {
        val range = LayerGeometry.scaleRange(100, 100, frameW, frameH) // 0.1..15
        val next = LayerGeometry.pinch(LayerTransform(2f, 10f, 20f), 1.5f, 150f, 250f, range)
        assertEquals(3f, next.scale)
        assertEquals(150f - (150f - 10f) * 1.5f, next.translateX)
        assertEquals(250f - (250f - 20f) * 1.5f, next.translateY)
    }

    @Test
    fun `a pinch beyond the range stops exactly at the bound`() {
        val range = LayerGeometry.scaleRange(100, 100, frameW, frameH)
        assertEquals(range.start, LayerGeometry.pinch(LayerTransform(2f, 0f, 0f), 0.001f, 0f, 0f, range).scale)
        assertEquals(range.endInclusive, LayerGeometry.pinch(LayerTransform(2f, 0f, 0f), 1000f, 0f, 0f, range).scale)
    }

    @Test
    fun `a pinch never clamps against the gesture`() {
        val range = LayerGeometry.scaleRange(100, 100, frameW, frameH)
        val below = LayerTransform(0.05f, 7f, 9f)
        // Further out: unchanged, not pulled up to the floor.
        assertSame(below, LayerGeometry.pinch(below, 0.5f, 150f, 250f, range))
        // Back toward the range: exactly the factor, no jump to the floor.
        assertEquals(0.05f * 1.5f, LayerGeometry.pinch(below, 1.5f, 150f, 250f, range).scale)
    }

    @Test
    fun `fit width fits the frame width and only an extreme panorama stops at the floor`() {
        assertEquals(
            LayerTransform(0.5f, 0f, (500f - 400f * 0.5f) / 2f),
            LayerGeometry.fitWidth(600, 400, frameW, frameH),
        )
        // 4000x100: fit 0.075 is below the floor 0.1 (base 5) → the floor.
        assertEquals(
            LayerTransform(0.1f, 0f, (500f - 100f * 0.1f) / 2f),
            LayerGeometry.fitWidth(4000, 100, frameW, frameH),
        )
    }

    @Test
    fun `original size draws one source pixel per frame pixel, centered`() {
        assertEquals(LayerTransform(1f, -50f, 50f), LayerGeometry.originalSize(400, 400, frameW, frameH))
    }

    @Test
    fun `the base scale is one when the frame or the image has no size`() {
        assertEquals(1f, LayerGeometry.baseScale(100, 100, 0, 500))
        assertEquals(1f, LayerGeometry.baseScale(0, 100, 300, 500))
        assertEquals(5f, LayerGeometry.baseScale(100, 100, 300, 500))
    }

    @Test
    fun `contains hits inside the placed bounds only`() {
        val t = LayerTransform(2f, 10f, 20f) // 100x50 image → 10..210 × 20..120
        assertTrue(LayerGeometry.contains(t, 100, 50, 10f, 20f))
        assertTrue(LayerGeometry.contains(t, 100, 50, 210f, 120f))
        assertFalse(LayerGeometry.contains(t, 100, 50, 211f, 60f))
        assertFalse(LayerGeometry.contains(t, 100, 50, 50f, 19f))
    }
}

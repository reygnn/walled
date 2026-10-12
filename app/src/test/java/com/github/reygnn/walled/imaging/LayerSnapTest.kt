package com.github.reygnn.walled.imaging

import com.github.reygnn.walled.imaging.LayerSnap.Edge
import com.github.reygnn.walled.model.LayerTransform
import com.github.reygnn.walled.model.SnapMode
import com.github.reygnn.walled.model.SnapSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LayerSnap]: the edge resistance and snap-back the launcher kept inside
 * `ZoomableImageView`, now pure. Frame 300×500 throughout.
 */
class LayerSnapTest {

    private val frameW = 300
    private val frameH = 500
    private val edge = SnapSettings(mode = SnapMode.EDGE)
    private val center = SnapSettings(mode = SnapMode.CENTER)

    private fun resist(t: LayerTransform, dx: Float, dy: Float, w: Int, h: Int, s: SnapSettings = edge) =
        LayerSnap.resist(t, dx, dy, w, h, frameW, frameH, s)

    private fun snap(t: LayerTransform, w: Int, h: Int, s: SnapSettings = edge) =
        LayerSnap.snapTarget(t, w, h, frameW, frameH, s)

    @Test
    fun `without snapping a drag passes through and nothing snaps`() {
        val off = SnapSettings(enabled = false)
        val t = LayerTransform(1f, 500f, 500f)
        assertEquals(LayerSnap.Resisted(40f, -30f, emptySet()), resist(t, 40f, -30f, 100, 100, off))
        assertNull(snap(t, 100, 100, off))
    }

    @Test
    fun `a drag inside the allowed band is not damped`() {
        // 100×100 layer at (50, 50): allowed x 0..200, y 0..400.
        val r = resist(LayerTransform(1f, 50f, 50f), 20f, 20f, 100, 100)
        assertEquals(LayerSnap.Resisted(20f, 20f, emptySet()), r)
    }

    @Test
    fun `a drag past an edge is damped and reports the edge`() {
        // At the right limit (x = 200): pushing further right is damped.
        val r = resist(LayerTransform(1f, 200f, 50f), 50f, 0f, 100, 100)
        assertEquals(50f * LayerSnap.rubberBandFactor(50f), r.dx)
        assertTrue(r.dx < 50f)
        assertEquals(setOf(Edge.RIGHT), r.edges)
    }

    @Test
    fun `the vertical axis can be switched off independently`() {
        val r = resist(LayerTransform(1f, 50f, 400f), 0f, 80f, 100, 100, edge.copy(vertical = false))
        assertEquals(80f, r.dy)
        assertTrue(r.edges.isEmpty())
    }

    @Test
    fun `center mode damps by the distance from the center`() {
        // 100×100 layer: center x = 100. Dragging from 100 to 140 → distance 40.
        val r = resist(LayerTransform(1f, 100f, 200f), 40f, 0f, 100, 100, center)
        assertEquals(40f * LayerSnap.rubberBandFactor(40f), r.dx)
    }

    @Test
    fun `an overshoot snaps back to the edge`() {
        // x returns to its limit 200; y (smaller than the frame) settles on the nearer edge 0.
        assertEquals(LayerTransform(1f, 200f, 0f), snap(LayerTransform(1f, 260f, 50f), 100, 100))
    }

    @Test
    fun `a small layer snaps to the nearer edge`() {
        // x 80 is nearer 0 than 200; y 350 is nearer 400 than 0.
        assertEquals(LayerTransform(1f, 0f, 400f), snap(LayerTransform(1f, 80f, 350f), 100, 100))
    }

    @Test
    fun `a layer larger than the frame may pan freely but not reveal a gap`() {
        // 600×1000 at scale 1 in 300×500: x allowed -300..0, y -500..0.
        assertNull(snap(LayerTransform(1f, -120f, -200f), 600, 1000))
        assertEquals(LayerTransform(1f, 0f, -500f), snap(LayerTransform(1f, 30f, -700f), 600, 1000))
    }

    @Test
    fun `center mode snaps to the frame center`() {
        assertEquals(LayerTransform(1f, 100f, 200f), snap(LayerTransform(1f, 10f, 20f), 100, 100, center))
    }

    @Test
    fun `a layer already in place needs no snap`() {
        assertNull(snap(LayerTransform(1f, 0f, 0f), 100, 100))
    }
}

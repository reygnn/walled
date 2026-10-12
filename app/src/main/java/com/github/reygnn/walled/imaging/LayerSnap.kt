package com.github.reygnn.walled.imaging

import com.github.reygnn.walled.model.LayerTransform
import com.github.reygnn.walled.model.SnapMode
import com.github.reygnn.walled.model.SnapSettings
import kotlin.math.abs

/**
 * Edge resistance while dragging and the snap-back target on release — pure
 * math, ported from the launcher's `ZoomableImageView` (where it lived inside the
 * View and was untestable). Sizes are ORIGINAL image sizes (see [LayerTransform]).
 */
object LayerSnap {

    /** Strength of the rubber band: the further past the edge, the less a drag moves. */
    const val EDGE_RESISTANCE_STRENGTH = 0.01f

    /** The frame edge a drag pushes the layer against; drives the edge haptic. */
    enum class Edge { LEFT, RIGHT, TOP, BOTTOM }

    /** The damped drag delta plus the edges the drag currently pushes against. */
    data class Resisted(val dx: Float, val dy: Float, val edges: Set<Edge>)

    fun rubberBandFactor(overshoot: Float): Float = 1f / (1f + overshoot * EDGE_RESISTANCE_STRENGTH)

    /**
     * Damps a drag of ([dx], [dy]) on a layer at [t]. Without snapping the delta
     * passes through unchanged.
     */
    fun resist(
        t: LayerTransform,
        dx: Float,
        dy: Float,
        imageW: Int,
        imageH: Int,
        frameW: Int,
        frameH: Int,
        settings: SnapSettings,
    ): Resisted {
        if (!settings.enabled) return Resisted(dx, dy, emptySet())
        val scaledW = imageW * t.scale
        val scaledH = imageH * t.scale
        var resDx = dx
        var resDy = dy
        val edges = mutableSetOf<Edge>()

        when (settings.mode) {
            SnapMode.EDGE -> {
                if (settings.horizontal) {
                    val minX = minOf(0f, frameW - scaledW)
                    val maxX = maxOf(0f, frameW - scaledW)
                    val overRight = maxOf(0f, t.translateX - maxX)
                    val overLeft = maxOf(0f, minX - t.translateX)
                    if (dx > 0 && (overRight > 0 || t.translateX + dx > maxX)) {
                        resDx = dx * rubberBandFactor(maxOf(overRight, t.translateX + dx - maxX))
                        edges += Edge.RIGHT
                    } else if (dx < 0 && (overLeft > 0 || t.translateX + dx < minX)) {
                        resDx = dx * rubberBandFactor(maxOf(overLeft, minX - (t.translateX + dx)))
                        edges += Edge.LEFT
                    }
                }
                if (settings.vertical) {
                    val minY = minOf(0f, frameH - scaledH)
                    val maxY = maxOf(0f, frameH - scaledH)
                    val overBottom = maxOf(0f, t.translateY - maxY)
                    val overTop = maxOf(0f, minY - t.translateY)
                    if (dy > 0 && (overBottom > 0 || t.translateY + dy > maxY)) {
                        resDy = dy * rubberBandFactor(maxOf(overBottom, t.translateY + dy - maxY))
                        edges += Edge.BOTTOM
                    } else if (dy < 0 && (overTop > 0 || t.translateY + dy < minY)) {
                        resDy = dy * rubberBandFactor(maxOf(overTop, minY - (t.translateY + dy)))
                        edges += Edge.TOP
                    }
                }
            }
            SnapMode.CENTER -> {
                val cX = (frameW - scaledW) / 2f
                val cY = (frameH - scaledH) / 2f
                if (settings.horizontal) resDx = dx * rubberBandFactor(abs(t.translateX + dx - cX))
                if (settings.vertical) resDy = dy * rubberBandFactor(abs(t.translateY + dy - cY))
            }
        }
        return Resisted(resDx, resDy, edges)
    }

    /**
     * Where a released layer at [t] should settle, or `null` when it already sits
     * where snapping wants it (or snapping is off). Only the translate moves.
     */
    fun snapTarget(
        t: LayerTransform,
        imageW: Int,
        imageH: Int,
        frameW: Int,
        frameH: Int,
        settings: SnapSettings,
    ): LayerTransform? {
        if (!settings.enabled) return null
        val scaledW = imageW * t.scale
        val scaledH = imageH * t.scale
        var tX = t.translateX
        var tY = t.translateY

        when (settings.mode) {
            SnapMode.EDGE -> {
                if (settings.horizontal) tX = edgeSnap(t.translateX, scaledW, frameW)
                if (settings.vertical) tY = edgeSnap(t.translateY, scaledH, frameH)
            }
            SnapMode.CENTER -> {
                if (settings.horizontal) tX = (frameW - scaledW) / 2f
                if (settings.vertical) tY = (frameH - scaledH) / 2f
            }
        }
        if (tX == t.translateX && tY == t.translateY) return null
        return t.copy(translateX = tX, translateY = tY)
    }

    /**
     * One axis of the EDGE snap: an overshoot returns to the nearest allowed
     * position; a layer smaller than the frame snaps to the nearer edge.
     */
    private fun edgeSnap(translate: Float, scaledSize: Float, frameSize: Int): Float {
        val min = minOf(0f, frameSize - scaledSize)
        val max = maxOf(0f, frameSize - scaledSize)
        return when {
            translate < min -> min
            translate > max -> max
            scaledSize < frameSize -> if (translate <= max - translate) 0f else max
            else -> translate
        }
    }
}

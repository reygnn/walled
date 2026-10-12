package com.github.reygnn.walled.imaging

import kotlin.math.min

/**
 * Maps the output frame into the preview canvas — pure, JVM-tested.
 *
 * The frame is fitted into the canvas (uniform [scale], centered), then
 * enlarged around the canvas center by the preview zoom factor. The factor
 * simulates how the home screen enlarges a wallpaper after it is set (Android's
 * wallpaper zoom/parallax), so the preview shows what will actually be visible.
 * It is display-only: transforms, gestures and the export stay in frame pixels.
 */
data class PreviewFit(val scale: Float, val offsetX: Float, val offsetY: Float) {

    fun toFrameX(canvasX: Float) = (canvasX - offsetX) / scale
    fun toFrameY(canvasY: Float) = (canvasY - offsetY) / scale

    companion object {
        /** No simulation; also the floor — the home screen never shows LESS than the frame. */
        const val ZOOM_MIN = 1.0f
        const val ZOOM_MAX = 2.0f

        /** A typed factor clamped to [ZOOM_MIN]..[ZOOM_MAX]; NaN/∞ fall back to [ZOOM_MIN]. */
        fun clampZoom(value: Float): Float =
            if (!value.isFinite()) ZOOM_MIN else value.coerceIn(ZOOM_MIN, ZOOM_MAX)

        fun of(canvasW: Float, canvasH: Float, frameW: Int, frameH: Int, zoom: Float = ZOOM_MIN): PreviewFit? {
            if (frameW <= 0 || frameH <= 0 || canvasW <= 0f || canvasH <= 0f) return null
            val k = min(canvasW / frameW, canvasH / frameH) * clampZoom(zoom)
            return PreviewFit(k, (canvasW - frameW * k) / 2f, (canvasH - frameH * k) / 2f)
        }
    }
}

package com.github.reygnn.walled.imaging

import com.github.reygnn.walled.model.LayerTransform
import kotlin.math.max

/**
 * Where a layer sits in the output frame — pure math, no Android types.
 *
 * Ported from the launcher's `WallpaperLayerPlacement`. Every size here is the
 * layer's ORIGINAL image size (see [LayerTransform]), so the decode sample size
 * never enters the math. The live canvas and the export ([WallpaperFlattener])
 * place with these same functions, so the preview is what gets set.
 */
object LayerGeometry {

    /** Absolute floor and ceiling of a layer scale; widened per layer by its base scale. */
    const val MIN_SCALE = 0.1f
    const val MAX_SCALE = 10.0f

    /** Zoom range relative to the layer's base (center-crop) scale. */
    const val ZOOM_IN_MULTIPLIER = 3.0f
    const val ZOOM_OUT_MULTIPLIER = 0.05f

    /** Replaces a non-finite or non-positive scale (corrupt input). */
    const val DEFAULT_SCALE = 1.0f

    /** The stored transform, or center-crop for a layer that was never placed. */
    fun resolve(transform: LayerTransform?, imageW: Int, imageH: Int, frameW: Int, frameH: Int): LayerTransform =
        if (transform == null) centerCrop(imageW, imageH, frameW, frameH)
        else clamped(transform, imageW, imageH, frameW, frameH)

    /** Covers the frame and centers the image. */
    fun centerCrop(imageW: Int, imageH: Int, frameW: Int, frameH: Int): LayerTransform {
        val scale = baseScale(imageW, imageH, frameW, frameH)
        return centered(scale, imageW, imageH, frameW, frameH)
    }

    /** Fits the frame width, centered vertically, inside the zoom range. */
    fun fitWidth(imageW: Int, imageH: Int, frameW: Int, frameH: Int): LayerTransform {
        if (imageW <= 0) return centerCrop(imageW, imageH, frameW, frameH)
        val range = scaleRange(imageW, imageH, frameW, frameH)
        val scale = (frameW.toFloat() / imageW).coerceIn(range.start, range.endInclusive)
        return LayerTransform(scale, translateX = 0f, translateY = (frameH - imageH * scale) / 2f)
    }

    /** One original pixel per frame pixel, centered (inside the zoom range). */
    fun originalSize(imageW: Int, imageH: Int, frameW: Int, frameH: Int): LayerTransform {
        val range = scaleRange(imageW, imageH, frameW, frameH)
        return centered(1f.coerceIn(range.start, range.endInclusive), imageW, imageH, frameW, frameH)
    }

    private fun centered(scale: Float, imageW: Int, imageH: Int, frameW: Int, frameH: Int) = LayerTransform(
        scale = scale,
        translateX = (frameW - imageW * scale) / 2f,
        translateY = (frameH - imageH * scale) / 2f,
    )

    /**
     * A stored transform clamped to the layer's zoom range and sanitized: a
     * non-finite or non-positive scale becomes [DEFAULT_SCALE] (then clamped), a
     * non-finite translate becomes 0.
     */
    fun clamped(transform: LayerTransform, imageW: Int, imageH: Int, frameW: Int, frameH: Int): LayerTransform {
        val range = scaleRange(imageW, imageH, frameW, frameH)
        val safeScale = transform.scale.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_SCALE
        return LayerTransform(
            scale = safeScale.coerceIn(range.start, range.endInclusive),
            translateX = transform.translateX.takeIf { it.isFinite() } ?: 0f,
            translateY = transform.translateY.takeIf { it.isFinite() } ?: 0f,
        )
    }

    /**
     * The zoom range of one layer: `min(MIN_SCALE, base × ZOOM_OUT_MULTIPLIER)` to
     * `max(MAX_SCALE, base × ZOOM_IN_MULTIPLIER)`. The one source of the bounds: the
     * restore clamps to it ([clamped]) and the pinch stops at it ([pinch]).
     */
    fun scaleRange(imageW: Int, imageH: Int, frameW: Int, frameH: Int): ClosedFloatingPointRange<Float> {
        val base = baseScale(imageW, imageH, frameW, frameH)
        return minOf(MIN_SCALE, base * ZOOM_OUT_MULTIPLIER)..maxOf(MAX_SCALE, base * ZOOM_IN_MULTIPLIER)
    }

    /** The center-crop scale; 1 when the frame or the image has no size. */
    fun baseScale(imageW: Int, imageH: Int, frameW: Int, frameH: Int): Float {
        if (frameW <= 0 || frameH <= 0 || imageW <= 0 || imageH <= 0) return 1f
        return max(frameW.toFloat() / imageW, frameH.toFloat() / imageH)
    }

    /**
     * One pinch step from [current] by [factor] around ([focusX], [focusY]). The
     * scale stops at [range]; it never clamps against the gesture: a scale already
     * outside [range] is not pulled across it — a step further outside returns
     * [current] unchanged, a step back moves by exactly [factor]. The translate
     * follows the factor actually applied, keeping the focus point fixed.
     */
    fun pinch(
        current: LayerTransform,
        factor: Float,
        focusX: Float,
        focusY: Float,
        range: ClosedFloatingPointRange<Float>,
    ): LayerTransform {
        val lower = minOf(range.start, current.scale)
        val upper = maxOf(range.endInclusive, current.scale)
        val target = (current.scale * factor).coerceIn(lower, upper)
        if (target == current.scale) return current
        val sf = target / current.scale
        return LayerTransform(
            scale = target,
            translateX = focusX - (focusX - current.translateX) * sf,
            translateY = focusY - (focusY - current.translateY) * sf,
        )
    }

    /** True when ([x], [y]) lies inside the layer's placed bounds. */
    fun contains(transform: LayerTransform, imageW: Int, imageH: Int, x: Float, y: Float): Boolean {
        val right = transform.translateX + imageW * transform.scale
        val bottom = transform.translateY + imageH * transform.scale
        return x >= transform.translateX && x <= right && y >= transform.translateY && y <= bottom
    }
}

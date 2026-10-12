package com.github.reygnn.walled.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/**
 * Decode budget for one layer bitmap, as a pixel AREA. Ported from the launcher's
 * render budget (≈ 4× a 1080×2424 screen): sharp enough for a 3× zoom, small
 * enough that gestures stay smooth and the ~100 MB Canvas per-bitmap draw limit
 * is never hit (huge camera photos would otherwise crash the draw).
 */
const val MAX_LAYER_PIXELS = 10_500_000

/**
 * Per-side cap: an area budget alone lets an extreme panorama through with one
 * side beyond the GPU max-texture size (→ blank layer). 8192 is the minimum
 * `GL_MAX_TEXTURE_SIDE` across the Android-16 device class.
 */
const val MAX_LAYER_TEXTURE_SIDE = 8192

/**
 * The power-of-two `inSampleSize` that brings a [srcWidth]×[srcHeight] image to at
 * most [maxPixels] decoded pixels and at most [maxSide] per side (0 = no side
 * cap). Returns 1 for images within budget and for unknown dimensions (≤ 0).
 */
fun calculateInSampleSize(
    srcWidth: Int,
    srcHeight: Int,
    maxPixels: Int = MAX_LAYER_PIXELS,
    maxSide: Int = MAX_LAYER_TEXTURE_SIDE,
): Int {
    if (srcWidth <= 0 || srcHeight <= 0 || maxPixels <= 0) return 1
    var sample = 1
    // Long arithmetic: srcWidth * srcHeight overflows Int for large images.
    while (true) {
        val w = srcWidth.toLong() / sample
        val h = srcHeight.toLong() / sample
        val overArea = w * h > maxPixels
        val overSide = maxSide > 0 && (w > maxSide || h > maxSide)
        if (!overArea && !overSide) break
        sample *= 2
    }
    return sample
}

/**
 * A bounded decode: the (downsampled) [bitmap], the [sampleSize] used, and the
 * full-resolution source size — the size every [com.github.reygnn.walled.model.LayerTransform]
 * is expressed in.
 */
data class DecodedLayer(
    val bitmap: Bitmap,
    val sampleSize: Int,
    val originalWidth: Int,
    val originalHeight: Int,
)

/**
 * Reads the bounds, then decodes with [calculateInSampleSize]. The preview
 * decodes HARDWARE (pixels off the Java heap, drawn on the hardware canvas,
 * ARGB_8888 fallback); the export passes [software] = true because it composes
 * on a software Canvas, which cannot draw HARDWARE bitmaps.
 *
 * [openStream] must return a FRESH stream per call (bounds pass + each attempt).
 * Returns `null` when no stream opens or the image cannot be decoded.
 */
fun decodeLayer(software: Boolean, openStream: () -> InputStream?): DecodedLayer? {
    // In bounds mode decodeStream returns null by design; only a null STREAM fails.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    (openStream() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
    val bitmap = if (software) {
        decodeWith(sample, Bitmap.Config.ARGB_8888, openStream)
    } else {
        decodeWith(sample, Bitmap.Config.HARDWARE, openStream)
            ?: decodeWith(sample, Bitmap.Config.ARGB_8888, openStream)
    } ?: return null
    return DecodedLayer(bitmap, sample, bounds.outWidth, bounds.outHeight)
}

private fun decodeWith(sample: Int, config: Bitmap.Config, openStream: () -> InputStream?): Bitmap? {
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = config
    }
    return openStream()?.use { BitmapFactory.decodeStream(it, null, opts) }
}

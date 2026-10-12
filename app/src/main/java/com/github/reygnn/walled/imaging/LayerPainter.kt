package com.github.reygnn.walled.imaging

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.github.reygnn.walled.model.LayerTransform

/** A decoded layer and where it sits in the frame — what one draw pass needs. */
data class PlacedLayer(
    val id: String,
    val decoded: DecodedLayer,
    val transform: LayerTransform,
)

/**
 * The one compositing loop: the live preview draws with it on every frame and
 * the export ([WallpaperFlattener]) draws with it once, so the preview is the
 * result. Draws in frame pixels, bottom-most layer first.
 */
object LayerPainter {

    fun newLayerPaint(): Paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    /**
     * The bitmap matrix of [layer]: the stored scale is per ORIGINAL pixel, and the
     * bitmap is downsampled by `sampleSize`, so it draws at `scale × sampleSize`.
     */
    fun buildMatrixInto(layer: PlacedLayer, out: Matrix) {
        val s = layer.transform.scale * layer.decoded.sampleSize
        out.reset()
        out.postScale(s, s)
        out.postTranslate(layer.transform.translateX, layer.transform.translateY)
    }

    /**
     * Draws [layers] in list order. The caller passes [paint] and [matrix] so a
     * per-frame caller can reuse its own instances. Recycled bitmaps are skipped.
     */
    fun draw(canvas: Canvas, layers: List<PlacedLayer>, paint: Paint, matrix: Matrix) {
        for (layer in layers) {
            val bmp = layer.decoded.bitmap
            if (bmp.isRecycled) continue
            buildMatrixInto(layer, matrix)
            canvas.drawBitmap(bmp, matrix, paint)
        }
    }
}

package com.github.reygnn.walled.imaging

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.core.graphics.createBitmap
import com.github.reygnn.walled.model.WallpaperState
import com.github.reygnn.walled.model.WallpaperTarget
import java.io.InputStream

/**
 * Renders the final wallpaper and hands it to the system.
 *
 * [flatten] reproduces the preview at the frame's native resolution with the
 * same placement ([LayerGeometry.resolve]) and the same draw loop
 * ([LayerPainter]). Unlike the launcher's parallel flatten it decodes, draws and
 * frees ONE layer at a time — peak memory is the frame plus one layer, whatever
 * the layer count. It stays all-or-nothing: a layer that cannot be decoded
 * yields `null`, never a wallpaper with a hole in it.
 */
object WallpaperFlattener {

    /**
     * Flattens [state] onto an opaque [frameWidth]×[frameHeight] bitmap. The
     * frame is pre-filled black: a system wallpaper has no alpha, so areas no
     * layer covers (or covers only transparently) become a clean black backdrop.
     * [openImage] opens a FRESH stream for a layer's image file.
     */
    fun flatten(
        state: WallpaperState,
        frameWidth: Int,
        frameHeight: Int,
        openImage: (fileName: String) -> InputStream?,
    ): Bitmap? {
        if (!state.hasWallpaper || frameWidth <= 0 || frameHeight <= 0) return null
        val out = createBitmap(frameWidth, frameHeight)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = LayerPainter.newLayerPaint()
        val matrix = Matrix()
        for (layer in state.layers) {
            val decoded = decodeLayer(software = true) { openImage(layer.imageFile) }
            if (decoded == null) {
                out.recycle()
                return null
            }
            try {
                val transform = LayerGeometry.resolve(
                    layer.transform, decoded.originalWidth, decoded.originalHeight, frameWidth, frameHeight,
                )
                LayerPainter.draw(canvas, listOf(PlacedLayer(layer.id, decoded, transform)), paint, matrix)
            } finally {
                decoded.bitmap.recycle()
            }
        }
        return out
    }

    /**
     * Sets [bitmap] as the system wallpaper of [target], bypassing the system
     * picker so no crop/zoom is applied. `allowBackup = false`: the image is
     * this device's frame size and makes no sense restored elsewhere.
     */
    fun setWallpaper(context: Context, bitmap: Bitmap, target: WallpaperTarget) {
        val which = when (target) {
            WallpaperTarget.HOME -> WallpaperManager.FLAG_SYSTEM
            WallpaperTarget.LOCK -> WallpaperManager.FLAG_LOCK
            WallpaperTarget.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        WallpaperManager.getInstance(context).setBitmap(bitmap, null, false, which)
    }
}

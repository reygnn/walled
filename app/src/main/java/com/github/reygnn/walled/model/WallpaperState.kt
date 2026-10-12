package com.github.reygnn.walled.model

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

/**
 * One layer ("sheet") of the wallpaper: an image file in app storage plus its
 * placement in the output frame.
 *
 * The transform lives in **frame pixels per ORIGINAL image pixel**: [scale] = 1
 * draws the source at 1:1, independent of how far the bitmap was downsampled for
 * decoding. That keeps the stored transform valid across decode-budget changes —
 * the launcher had to tag every transform with its capture sample size and
 * compensate on restore; walled stores resolution-independent values instead.
 * Matrix order: scale, then translate ([translateX]/[translateY] = frame px).
 *
 * Immutable — every change produces a new instance.
 */
@Serializable
data class WallpaperLayerState(
    val id: String = newId(),
    /** File name inside the app's layer directory (see `LayerImageStore`). */
    val imageFile: String,
    /** `null` = never placed: the layer is center-cropped when first shown. */
    val transform: LayerTransform? = null,
) {
    companion object {
        // Thread-safe: layers can be created from different threads.
        private val counter = AtomicLong(0)

        fun newId(): String = "layer_${System.currentTimeMillis()}_${counter.getAndIncrement()}"
    }
}

/** A layer placement: frame px per original image px, then a translate in frame px. */
@Serializable
data class LayerTransform(
    val scale: Float,
    val translateX: Float,
    val translateY: Float,
)

/**
 * The whole wallpaper: [layers] bottom-most first. An empty list is [NONE].
 * Immutable — every helper returns a new instance and leaves an unknown index
 * or ID a no-op.
 */
data class WallpaperState(
    val layers: List<WallpaperLayerState> = emptyList(),
) {
    companion object {
        val NONE = WallpaperState()
    }

    val layerCount: Int get() = layers.size

    val hasWallpaper: Boolean get() = layers.isNotEmpty()

    /** All files this state references; everything else in the layer dir is an orphan. */
    val referencedFiles: Set<String> get() = layers.mapTo(mutableSetOf()) { it.imageFile }

    fun indexOf(layerId: String): Int? = layers.indexOfFirst { it.id == layerId }.takeIf { it >= 0 }

    fun withAddedLayer(layer: WallpaperLayerState): WallpaperState = copy(layers = layers + layer)

    fun withRemovedLayers(layerIds: Collection<String>): WallpaperState =
        copy(layers = layers.filterNot { it.id in layerIds })

    fun withTransform(layerId: String, transform: LayerTransform): WallpaperState {
        val index = indexOf(layerId) ?: return this
        val updated = layers.toMutableList()
        updated[index] = updated[index].copy(transform = transform)
        return copy(layers = updated)
    }

    /** Moves the layer [layerId] by [delta] positions (+1 = up / towards the top); clamped. */
    fun withMovedLayer(layerId: String, delta: Int): WallpaperState {
        val from = indexOf(layerId) ?: return this
        val to = (from + delta).coerceIn(0, layers.lastIndex)
        if (to == from) return this
        val updated = layers.toMutableList()
        updated.add(to, updated.removeAt(from))
        return copy(layers = updated)
    }
}

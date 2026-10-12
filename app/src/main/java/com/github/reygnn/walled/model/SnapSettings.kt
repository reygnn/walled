package com.github.reygnn.walled.model

/**
 * Magnetic snapping while dragging a layer.
 *
 * - [EDGE]: a layer smaller than the frame snaps to the nearer frame edge, a
 *   larger one may not reveal a gap at an edge.
 * - [CENTER]: the layer snaps to the frame center.
 */
enum class SnapMode { EDGE, CENTER }

data class SnapSettings(
    val enabled: Boolean = true,
    val mode: SnapMode = SnapMode.EDGE,
    val horizontal: Boolean = true,
    val vertical: Boolean = true,
)

/** Which system wallpaper slot(s) to set. */
enum class WallpaperTarget { HOME, LOCK, BOTH }

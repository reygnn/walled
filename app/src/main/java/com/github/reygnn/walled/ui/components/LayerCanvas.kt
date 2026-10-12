package com.github.reygnn.walled.ui.components

import android.graphics.Matrix
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.withSave
import com.github.reygnn.walled.imaging.LayerPainter
import com.github.reygnn.walled.imaging.PlacedLayer
import kotlin.math.min

/** Maps the output frame into the canvas: uniform [scale], centered by [offsetX]/[offsetY]. */
private data class FrameFit(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun toFrame(p: Offset) = Offset((p.x - offsetX) / scale, (p.y - offsetY) / scale)

    companion object {
        fun of(canvasW: Float, canvasH: Float, frameW: Int, frameH: Int): FrameFit? {
            if (frameW <= 0 || frameH <= 0 || canvasW <= 0f || canvasH <= 0f) return null
            val k = min(canvasW / frameW, canvasH / frameH)
            return FrameFit(k, (canvasW - frameW * k) / 2f, (canvasH - frameH * k) / 2f)
        }
    }
}

private const val TAP_MAX_DURATION_MS = 300L
private const val SELECTION_BORDER_DP = 1.5f
private const val SELECTION_BORDER_COLOR = 0x99FFFFFF.toInt()

/**
 * The full-bleed WYSIWYG preview: draws the layers with the export's own draw
 * loop ([LayerPainter]) in frame coordinates, and turns touches into frame-pixel
 * gestures for the active layer — one finger drags, two fingers pinch, a quick
 * tap selects the topmost layer under the finger.
 *
 * The immersive canvas normally IS the frame (scale 1); [FrameFit] covers the
 * case where it is not, so gestures and drawing stay in frame pixels either way.
 */
@Composable
fun LayerCanvas(
    layers: List<PlacedLayer>,
    activeLayerId: String?,
    frameWidth: Int,
    frameHeight: Int,
    onTap: (Offset) -> Unit,
    onGestureStart: () -> Unit,
    /** Returns true when the drag just reached a new edge (haptic tick). */
    onDrag: (Offset) -> Boolean,
    onPinch: (factor: Float, focus: Offset) -> Unit,
    onGestureEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val paint = remember { LayerPainter.newLayerPaint() }
    val matrix = remember { Matrix() }
    val borderPaint = remember {
        android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            color = SELECTION_BORDER_COLOR
            isAntiAlias = true
        }
    }
    val bounds = remember { RectF() }
    // The gesture loop outlives recompositions; read the callbacks fresh.
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onGestureStart)
    val drag by rememberUpdatedState(onDrag)
    val pinch by rememberUpdatedState(onPinch)
    val end by rememberUpdatedState(onGestureEnd)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(frameWidth, frameHeight) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val fit = FrameFit.of(size.width.toFloat(), size.height.toFloat(), frameWidth, frameHeight)
                        ?: return@awaitEachGesture
                    start()
                    val slop = viewConfiguration.touchSlop
                    var travelled = 0f
                    var pastSlop = false
                    var multiTouch = false
                    var lastUptime = down.uptimeMillis
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            multiTouch = true
                            pastSlop = true
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) pinch(zoom, fit.toFrame(event.calculateCentroid(useCurrent = true)))
                        } else if (pressed == 1) {
                            val pan = event.calculatePan()
                            if (!pastSlop) {
                                travelled += pan.getDistance()
                                // The slop itself is swallowed, as in the launcher (no jump on pickup).
                                if (travelled > slop) pastSlop = true
                            } else if (pan != Offset.Zero) {
                                if (drag(pan / fit.scale)) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                        event.changes.forEach { change ->
                            lastUptime = change.uptimeMillis
                            if (change.positionChanged()) change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    if (!pastSlop && !multiTouch && lastUptime - down.uptimeMillis < TAP_MAX_DURATION_MS) {
                        tap(fit.toFrame(down.position))
                    }
                    end()
                }
            },
    ) {
        drawRect(Color.Black)
        val fit = FrameFit.of(size.width, size.height, frameWidth, frameHeight) ?: return@Canvas
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.withSave {
                translate(fit.offsetX, fit.offsetY)
                scale(fit.scale, fit.scale)
                clipRect(0f, 0f, frameWidth.toFloat(), frameHeight.toFloat())
                LayerPainter.draw(this, layers, paint, matrix)
                // Selection outline only when there is something to choose between.
                val active = layers.firstOrNull { it.id == activeLayerId }
                if (active != null && layers.size > 1) {
                    val t = active.transform
                    bounds.set(
                        t.translateX,
                        t.translateY,
                        t.translateX + active.decoded.originalWidth * t.scale,
                        t.translateY + active.decoded.originalHeight * t.scale,
                    )
                    borderPaint.strokeWidth = SELECTION_BORDER_DP * density / fit.scale
                    drawRect(bounds, borderPaint)
                }
            }
        }
    }
}

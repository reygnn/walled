package com.github.reygnn.walled.imaging

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.reygnn.walled.model.LayerTransform
import com.github.reygnn.walled.model.WallpaperLayerState
import com.github.reygnn.walled.model.WallpaperState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Real-pixel coverage for [WallpaperFlattener.flatten]: PNG decode, placement,
 * z-order, alpha and the black backdrop. NATIVE graphics mode is mandatory —
 * in legacy-shadow mode `Canvas` draws paint nothing and `getPixel` returns 0.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperFlattenerRobolectricTest {

    private val images = mutableMapOf<String, ByteArray>()

    private fun open(name: String): InputStream? = images[name]?.inputStream()

    private fun png(name: String, bitmap: Bitmap) {
        images[name] = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun solid(w: Int, h: Int, color: Int) = createBitmap(w, h).apply { eraseColor(color) }

    private fun layer(id: String, transform: LayerTransform?) = WallpaperLayerState(id = id, imageFile = id, transform = transform)

    private fun assertColor(expected: Int, actual: Int) =
        assertEquals("#%08X vs #%08X".format(expected, actual), expected, actual)

    @Test
    fun `the output is the frame size and uncovered areas are black`() {
        png("red", solid(50, 50, Color.RED))
        val out = WallpaperFlattener.flatten(
            WallpaperState(listOf(layer("red", LayerTransform(1f, 0f, 0f)))), 200, 400, ::open,
        )!!

        assertEquals(200, out.width)
        assertEquals(400, out.height)
        assertColor(Color.RED, out.getPixel(25, 25))
        assertColor(Color.BLACK, out.getPixel(100, 200))
    }

    @Test
    fun `the upper layer covers the lower one, and its transparent part shows it`() {
        png("blue", solid(100, 100, Color.BLUE))
        // Left half opaque green, right half fully transparent.
        val half = createBitmap(100, 100).apply {
            eraseColor(Color.TRANSPARENT)
            for (x in 0 until 50) for (y in 0 until 100) setPixel(x, y, Color.GREEN)
        }
        png("half", half)

        val out = WallpaperFlattener.flatten(
            WallpaperState(listOf(layer("blue", LayerTransform(1f, 0f, 0f)), layer("half", LayerTransform(1f, 0f, 0f)))),
            100, 100, ::open,
        )!!

        assertColor(Color.GREEN, out.getPixel(20, 50))
        assertColor(Color.BLUE, out.getPixel(80, 50))
    }

    @Test
    fun `the transform scales per original pixel and translates in frame pixels`() {
        png("red", solid(10, 10, Color.RED))
        // Scale 2 at (20, 30): covers 20..40 × 30..50.
        val out = WallpaperFlattener.flatten(
            WallpaperState(listOf(layer("red", LayerTransform(2f, 20f, 30f)))), 100, 100, ::open,
        )!!

        assertColor(Color.RED, out.getPixel(21, 31))
        assertColor(Color.RED, out.getPixel(38, 48))
        assertColor(Color.BLACK, out.getPixel(19, 40))
        assertColor(Color.BLACK, out.getPixel(30, 51))
    }

    @Test
    fun `an unplaced layer is center-cropped like in the preview`() {
        // 100×50 in a 100×100 frame: center-crop scale 2 → 200×100 at x -50.
        val split = createBitmap(100, 50).apply {
            for (x in 0 until 100) for (y in 0 until 50) setPixel(x, y, if (x < 50) Color.RED else Color.BLUE)
        }
        png("split", split)
        val out = WallpaperFlattener.flatten(WallpaperState(listOf(layer("split", null))), 100, 100, ::open)!!

        assertColor(Color.RED, out.getPixel(10, 50))
        assertColor(Color.BLUE, out.getPixel(90, 50))
    }

    @Test
    fun `one undecodable layer yields no wallpaper at all`() {
        png("red", solid(10, 10, Color.RED))
        images["broken"] = "not an image".toByteArray()
        val state = WallpaperState(listOf(layer("red", null), layer("broken", null)))

        assertNull(WallpaperFlattener.flatten(state, 100, 100, ::open))
        assertNull(WallpaperFlattener.flatten(WallpaperState(listOf(layer("missing", null))), 100, 100, ::open))
    }

    @Test
    fun `no layers or no frame yields nothing`() {
        assertNull(WallpaperFlattener.flatten(WallpaperState.NONE, 100, 100, ::open))
        png("red", solid(10, 10, Color.RED))
        assertNull(WallpaperFlattener.flatten(WallpaperState(listOf(layer("red", null))), 0, 100, ::open))
    }
}

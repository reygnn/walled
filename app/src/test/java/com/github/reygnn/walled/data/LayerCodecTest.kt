package com.github.reygnn.walled.data

import com.github.reygnn.walled.model.LayerTransform
import com.github.reygnn.walled.model.WallpaperLayerState
import com.github.reygnn.walled.model.WallpaperState
import org.junit.Assert.assertEquals
import org.junit.Test

class LayerCodecTest {

    @Test
    fun `layers survive a round trip, placed and unplaced`() {
        val state = WallpaperState(
            listOf(
                WallpaperLayerState(id = "a", imageFile = "layer_1", transform = LayerTransform(1.5f, -10f, 20.25f)),
                WallpaperLayerState(id = "b", imageFile = "layer_2", transform = null),
            ),
        )
        assertEquals(state, LayerCodec.decode(LayerCodec.encode(state)))
    }

    @Test
    fun `missing or corrupt payloads read as no layers`() {
        assertEquals(WallpaperState.NONE, LayerCodec.decode(null))
        assertEquals(WallpaperState.NONE, LayerCodec.decode(""))
        assertEquals(WallpaperState.NONE, LayerCodec.decode("{not json"))
        assertEquals(WallpaperState.NONE, LayerCodec.decode("""[{"id":"a"}]""")) // imageFile missing
    }

    @Test
    fun `unknown fields from a newer version are ignored`() {
        val raw = """[{"id":"a","imageFile":"f","transform":null,"future":42}]"""
        assertEquals(WallpaperState(listOf(WallpaperLayerState(id = "a", imageFile = "f"))), LayerCodec.decode(raw))
    }
}

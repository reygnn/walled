package com.github.reygnn.walled.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WallpaperStateTest {

    private val a = WallpaperLayerState(id = "a", imageFile = "fa")
    private val b = WallpaperLayerState(id = "b", imageFile = "fb")
    private val c = WallpaperLayerState(id = "c", imageFile = "fc")
    private val abc = WallpaperState(listOf(a, b, c))

    @Test
    fun `a layer moves up and down by one and stops at the ends`() {
        assertEquals(listOf("b", "a", "c"), abc.withMovedLayer("a", +1).layers.map { it.id })
        assertEquals(listOf("a", "c", "b"), abc.withMovedLayer("c", -1).layers.map { it.id })
        assertSame(abc, abc.withMovedLayer("c", +1))
        assertSame(abc, abc.withMovedLayer("a", -1))
    }

    @Test
    fun `unknown ids change nothing`() {
        assertSame(abc, abc.withMovedLayer("x", +1))
        assertSame(abc, abc.withTransform("x", LayerTransform(1f, 0f, 0f)))
        assertEquals(abc, abc.withRemovedLayers(listOf("x")))
        assertNull(abc.indexOf("x"))
    }

    @Test
    fun `remove and transform address layers by id`() {
        assertEquals(listOf("a", "c"), abc.withRemovedLayers(setOf("b")).layers.map { it.id })
        val t = LayerTransform(2f, 3f, 4f)
        assertEquals(t, abc.withTransform("b", t).layers[1].transform)
    }

    @Test
    fun `referenced files are the image files of all layers`() {
        assertEquals(setOf("fa", "fb", "fc"), abc.referencedFiles)
        assertEquals(emptySet<String>(), WallpaperState.NONE.referencedFiles)
    }
}

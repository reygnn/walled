package com.github.reygnn.walled.viewmodel

import android.graphics.Bitmap
import com.github.reygnn.walled.data.LayerImageStore
import com.github.reygnn.walled.data.PersistedEditor
import com.github.reygnn.walled.data.WallpaperRepository
import com.github.reygnn.walled.imaging.DecodedLayer
import com.github.reygnn.walled.imaging.LayerGeometry
import com.github.reygnn.walled.model.LayerTransform
import com.github.reygnn.walled.model.SnapMode
import com.github.reygnn.walled.model.SnapSettings
import com.github.reygnn.walled.model.WallpaperLayerState
import com.github.reygnn.walled.model.WallpaperState
import com.github.reygnn.walled.model.WallpaperTarget
import com.github.reygnn.walled.testing.MainDispatcherRule
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EditorViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRepository(var persisted: PersistedEditor = PersistedEditor()) : WallpaperRepository {
        val savedLayers = mutableListOf<WallpaperState>()
        val savedSnaps = mutableListOf<SnapSettings>()
        override suspend fun load() = persisted
        override suspend fun saveLayers(state: WallpaperState) {
            savedLayers += state
        }
        override suspend fun saveSnap(snap: SnapSettings) {
            savedSnaps += snap
        }
    }

    private data class Export(val state: WallpaperState, val w: Int, val h: Int, val target: WallpaperTarget)

    private val frameW = 300
    private val frameH = 500

    private val repository = FakeRepository()
    private val store by lazy { LayerImageStore(File(tmp.root, "layers")) }
    /** Original image size per stored file name; a missing entry fails the decode. */
    private val imageSizes = mutableMapOf<String, Pair<Int, Int>>()
    private var nextAddSize: Pair<Int, Int>? = 600 to 400
    private val exports = mutableListOf<Export>()
    private var exportResult: () -> Boolean = { true }

    private fun decoded(size: Pair<Int, Int>) =
        DecodedLayer(mockk<Bitmap>(relaxed = true), sampleSize = 1, originalWidth = size.first, originalHeight = size.second)

    private fun createViewModel(): EditorViewModel {
        val vm = EditorViewModel(
            repository = repository,
            imageStore = store,
            decoder = { name ->
                // A file copied in by addLayer takes the size queued for the next add.
                val size = imageSizes[name] ?: nextAddSize?.also { imageSizes[name] = it }
                size?.let { decoded(it) }
            },
            exporter = { state, w, h, target ->
                exports += Export(state, w, h, target)
                exportResult()
            },
            ioDispatcher = mainRule.testDispatcher,
        )
        vm.setFrameSize(frameW, frameH)
        return vm
    }

    /** Persists [layers] with a real file each, sized per [sizes] (null = undecodable). */
    private fun persist(vararg layers: Pair<WallpaperLayerState, Pair<Int, Int>?>) {
        File(tmp.root, "layers").mkdirs()
        layers.forEach { (layer, size) ->
            file(layer.imageFile).writeText("x")
            if (size != null) imageSizes[layer.imageFile] = size
        }
        repository.persisted = PersistedEditor(WallpaperState(layers.map { it.first }))
        nextAddSize = null
    }

    private fun file(name: String) = File(File(tmp.root, "layers"), name)

    @Test
    fun `start restores the layers and snap settings, activates the top layer and decodes all`() =
        runTest(mainRule.testDispatcher) {
            val snap = SnapSettings(mode = SnapMode.CENTER, vertical = false)
            persist(
                WallpaperLayerState(id = "a", imageFile = "fa") to (100 to 100),
                WallpaperLayerState(id = "b", imageFile = "fb") to (200 to 200),
            )
            repository.persisted = repository.persisted.copy(snap = snap)

            val vm = createViewModel()
            advanceUntilIdle()

            val s = vm.state.value
            assertTrue(s.loaded)
            assertEquals(listOf("a", "b"), s.wallpaper.layers.map { it.id })
            assertEquals(snap, s.snap)
            assertEquals("b", s.activeLayerId)
            assertEquals(setOf("a", "b"), s.decoded.keys)
            assertTrue(s.canApply)
        }

    @Test
    fun `an unplaced layer is center-cropped for display`() = runTest(mainRule.testDispatcher) {
        persist(WallpaperLayerState(id = "a", imageFile = "fa") to (100 to 100))
        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(
            LayerGeometry.centerCrop(100, 100, frameW, frameH),
            vm.state.value.placedLayers.single().transform,
        )
    }

    @Test
    fun `an unloadable layer is reported, blocks apply, and can be removed`() = runTest(mainRule.testDispatcher) {
        persist(
            WallpaperLayerState(id = "a", imageFile = "fa") to (100 to 100),
            WallpaperLayerState(id = "broken", imageFile = "fbroken") to null,
        )
        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(EditorMessage.LayersUnloadable(1), vm.state.value.message)
        assertEquals(setOf("broken"), vm.state.value.unloadable)
        assertFalse(vm.state.value.canApply)
        vm.apply(WallpaperTarget.HOME)
        advanceUntilIdle()
        assertTrue(exports.isEmpty())

        vm.removeUnloadableLayers()
        advanceUntilIdle()

        assertEquals(listOf("a"), repository.savedLayers.last().layers.map { it.id })
        assertFalse(file("fbroken").exists())
        assertTrue(vm.state.value.canApply)
    }

    @Test
    fun `an added layer is copied in, fitted to the width, activated and saved`() = runTest(mainRule.testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.addLayer { "image".byteInputStream() }
        advanceUntilIdle()

        val s = vm.state.value
        val layer = s.wallpaper.layers.single()
        assertEquals(layer.id, s.activeLayerId)
        assertEquals(LayerGeometry.fitWidth(600, 400, frameW, frameH), layer.transform)
        assertTrue(file(layer.imageFile).exists())
        assertEquals(s.wallpaper, repository.savedLayers.last())
        assertFalse(s.isBusy)
    }

    @Test
    fun `an image that cannot be decoded is not added and its copy is deleted`() = runTest(mainRule.testDispatcher) {
        nextAddSize = null
        val vm = createViewModel()
        advanceUntilIdle()

        vm.addLayer { "garbage".byteInputStream() }
        advanceUntilIdle()

        assertEquals(EditorMessage.AddFailed, vm.state.value.message)
        assertFalse(vm.state.value.wallpaper.hasWallpaper)
        assertEquals(0, File(tmp.root, "layers").listFiles()!!.size)
    }

    @Test
    fun `removing the active layer saves first, deletes its file and activates the top layer`() =
        runTest(mainRule.testDispatcher) {
            persist(
                WallpaperLayerState(id = "a", imageFile = "fa") to (100 to 100),
                WallpaperLayerState(id = "b", imageFile = "fb") to (100 to 100),
            )
            val vm = createViewModel()
            advanceUntilIdle()

            vm.removeActiveLayer() // "b"
            advanceUntilIdle()

            assertEquals(listOf("a"), repository.savedLayers.last().layers.map { it.id })
            assertFalse(file("fb").exists())
            assertTrue(file("fa").exists())
            assertEquals("a", vm.state.value.activeLayerId)
        }

    @Test
    fun `a tap selects the topmost layer under the finger`() = runTest(mainRule.testDispatcher) {
        persist(
            WallpaperLayerState(id = "big", imageFile = "f1", transform = LayerTransform(1f, 0f, 0f)) to (300 to 500),
            WallpaperLayerState(id = "small", imageFile = "f2", transform = LayerTransform(1f, 0f, 0f)) to (100 to 100),
        )
        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.tapAt(200f, 300f)) // only "big" is there
        assertEquals("big", vm.state.value.activeLayerId)
        assertTrue(vm.tapAt(50f, 50f)) // both; "small" is on top
        assertEquals("small", vm.state.value.activeLayerId)
        assertFalse(vm.tapAt(50f, 50f)) // unchanged
    }

    @Test
    fun `a drag past the edge ticks once per edge, then snaps back and saves`() = runTest(mainRule.testDispatcher) {
        persist(WallpaperLayerState(id = "a", imageFile = "f", transform = LayerTransform(1f, 0f, 0f)) to (100 to 100))
        val vm = createViewModel()
        advanceUntilIdle()

        vm.gestureStart()
        assertTrue(vm.dragBy(-30f, 0f)) // pushes against the left edge: tick
        assertFalse(vm.dragBy(-30f, 0f)) // still the same edge: no second tick
        assertTrue(vm.state.value.wallpaper.layers.single().transform!!.translateX < 0f)
        vm.gestureEnd()
        advanceUntilIdle()

        assertEquals(LayerTransform(1f, 0f, 0f), vm.state.value.wallpaper.layers.single().transform)
        assertEquals(LayerTransform(1f, 0f, 0f), repository.savedLayers.last().layers.single().transform)
    }

    @Test
    fun `a new gesture cancels a running snap-back`() = runTest(mainRule.testDispatcher) {
        persist(WallpaperLayerState(id = "a", imageFile = "f", transform = LayerTransform(1f, 0f, 0f)) to (100 to 100))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.gestureStart()
        vm.dragBy(-50f, 0f)
        vm.gestureEnd()

        vm.gestureStart() // before the animation ran
        advanceUntilIdle()

        assertTrue(vm.state.value.wallpaper.layers.single().transform!!.translateX < 0f)
    }

    @Test
    fun `a bare tap saves nothing`() = runTest(mainRule.testDispatcher) {
        persist(WallpaperLayerState(id = "a", imageFile = "f", transform = LayerTransform(1f, 0f, 0f)) to (100 to 100))
        val vm = createViewModel()
        advanceUntilIdle()

        vm.gestureStart()
        vm.tapAt(50f, 50f)
        vm.gestureEnd()
        advanceUntilIdle()

        assertTrue(repository.savedLayers.isEmpty())
    }

    @Test
    fun `a snap-back cut short by a tap is still saved`() = runTest(mainRule.testDispatcher) {
        persist(WallpaperLayerState(id = "a", imageFile = "f", transform = LayerTransform(1f, 0f, 0f)) to (100 to 100))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.gestureStart()
        vm.dragBy(-50f, 0f)
        vm.gestureEnd()

        vm.gestureStart() // a tap interrupts the animation
        vm.gestureEnd()
        advanceUntilIdle()

        assertEquals(LayerTransform(1f, 0f, 0f), repository.savedLayers.last().layers.single().transform)
    }

    @Test
    fun `a pinch stops at the zoom range`() = runTest(mainRule.testDispatcher) {
        persist(WallpaperLayerState(id = "a", imageFile = "f", transform = LayerTransform(1f, 0f, 0f)) to (100 to 100))
        val vm = createViewModel()
        advanceUntilIdle()

        vm.pinchBy(1000f, 0f, 0f)

        val max = LayerGeometry.scaleRange(100, 100, frameW, frameH).endInclusive
        assertEquals(max, vm.state.value.wallpaper.layers.single().transform!!.scale)
    }

    @Test
    fun `moving the active layer reorders and saves`() = runTest(mainRule.testDispatcher) {
        persist(
            WallpaperLayerState(id = "a", imageFile = "fa") to (100 to 100),
            WallpaperLayerState(id = "b", imageFile = "fb") to (100 to 100),
        )
        val vm = createViewModel()
        advanceUntilIdle()

        vm.moveActiveLayer(-1) // "b" down
        advanceUntilIdle()

        assertEquals(listOf("b", "a"), repository.savedLayers.last().layers.map { it.id })
        assertEquals("b", vm.state.value.activeLayerId)
    }

    @Test
    fun `apply exports the current layers at frame size and reports the target`() = runTest(mainRule.testDispatcher) {
        persist(WallpaperLayerState(id = "a", imageFile = "f") to (100 to 100))
        val vm = createViewModel()
        advanceUntilIdle()

        vm.apply(WallpaperTarget.BOTH)
        advanceUntilIdle()

        assertEquals(Export(vm.state.value.wallpaper, frameW, frameH, WallpaperTarget.BOTH), exports.single())
        assertEquals(EditorMessage.Applied(WallpaperTarget.BOTH), vm.state.value.message)
        vm.clearMessage()
        assertNull(vm.state.value.message)
    }

    @Test
    fun `a failed or throwing export reports a failure`() = runTest(mainRule.testDispatcher) {
        persist(WallpaperLayerState(id = "a", imageFile = "f") to (100 to 100))
        val vm = createViewModel()
        advanceUntilIdle()

        exportResult = { false }
        vm.apply(WallpaperTarget.HOME)
        advanceUntilIdle()
        assertEquals(EditorMessage.ApplyFailed, vm.state.value.message)

        exportResult = { throw OutOfMemoryError("too big") }
        vm.apply(WallpaperTarget.HOME)
        advanceUntilIdle()
        assertEquals(EditorMessage.ApplyFailed, vm.state.value.message)
        assertFalse(vm.state.value.isBusy)
    }

    @Test
    fun `snap toggles are saved`() = runTest(mainRule.testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleSnap()
        vm.toggleSnapMode()
        advanceUntilIdle()

        assertEquals(SnapSettings(enabled = false, mode = SnapMode.CENTER), repository.savedSnaps.last())
    }

    @Test
    fun `remove all deletes every layer and file`() = runTest(mainRule.testDispatcher) {
        persist(
            WallpaperLayerState(id = "a", imageFile = "fa") to (100 to 100),
            WallpaperLayerState(id = "b", imageFile = "fb") to (100 to 100),
        )
        val vm = createViewModel()
        advanceUntilIdle()

        vm.removeAllLayers()
        advanceUntilIdle()

        assertEquals(WallpaperState.NONE, repository.savedLayers.last())
        assertEquals(0, File(tmp.root, "layers").listFiles()!!.size)
        assertNull(vm.state.value.activeLayerId)
    }
}

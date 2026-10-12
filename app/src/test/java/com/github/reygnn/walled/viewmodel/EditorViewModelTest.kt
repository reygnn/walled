package com.github.reygnn.walled.viewmodel

import android.graphics.Bitmap
import com.github.reygnn.walled.data.LayerImageStore
import com.github.reygnn.walled.data.PersistedEditor
import com.github.reygnn.walled.data.ProjectFolder
import com.github.reygnn.walled.data.ProjectRef
import com.github.reygnn.walled.data.WallpaperRepository
import com.github.reygnn.walled.imaging.DecodedLayer
import com.github.reygnn.walled.imaging.LayerGeometry
import com.github.reygnn.walled.model.LayerTransform
import com.github.reygnn.walled.model.ProjectCodec
import com.github.reygnn.walled.model.ProjectDecode
import com.github.reygnn.walled.model.ProjectFile
import com.github.reygnn.walled.model.ProjectFrame
import com.github.reygnn.walled.model.ProjectLayer
import com.github.reygnn.walled.model.ProjectProblem
import com.github.reygnn.walled.model.SnapMode
import com.github.reygnn.walled.model.SnapSettings
import com.github.reygnn.walled.model.WallpaperLayerState
import com.github.reygnn.walled.model.WallpaperState
import com.github.reygnn.walled.model.WallpaperTarget
import com.github.reygnn.walled.testing.MainDispatcherRule
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
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
import java.io.InputStream

class EditorViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRepository(var persisted: PersistedEditor = PersistedEditor()) : WallpaperRepository {
        val savedLayers = mutableListOf<WallpaperState>()
        val savedSnaps = mutableListOf<SnapSettings>()
        val savedProjects = mutableListOf<ProjectRef>()
        val savedLocks = mutableListOf<Boolean>()
        override suspend fun load() = persisted
        override suspend fun saveLayers(state: WallpaperState) {
            savedLayers += state
        }
        override suspend fun saveSnap(snap: SnapSettings) {
            savedSnaps += snap
        }
        override suspend fun savePositionLocked(locked: Boolean) {
            savedLocks += locked
        }
        override suspend fun saveProject(project: ProjectRef) {
            savedProjects += project
        }
    }

    /** An in-memory project folder; [failWrites] makes every write fail. */
    private class FakeFolder(override val displayName: String = "walled-layers") : ProjectFolder {
        val files = mutableMapOf<String, ByteArray>()
        var failWrites = false
        override fun listProjects() = files.keys.filter { it.endsWith(".json") }.sorted()
        override fun readText(name: String) = files[name]?.decodeToString()
        override fun openFile(name: String) = files[name]?.inputStream()
        override fun length(name: String) = files[name]?.size?.toLong()
        override fun writeText(name: String, text: String) = writeFile(name) { text.byteInputStream() }
        override fun writeFile(name: String, source: () -> InputStream?): Boolean {
            if (failWrites) return false
            files[name] = source()?.use { it.readBytes() } ?: return false
            return true
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
    private val folder = FakeFolder()
    /** Tree URI → folder; a missing entry is a folder whose grant is gone. */
    private val folders = mutableMapOf("content://tree/walled" to folder)

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
            folderOpener = { uri -> folders[uri] },
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

        vm.addLayer("ducks.png") { "image".byteInputStream() }
        advanceUntilIdle()

        val s = vm.state.value
        val layer = s.wallpaper.layers.single()
        assertEquals(layer.id, s.activeLayerId)
        assertEquals(LayerGeometry.fitWidth(600, 400, frameW, frameH), layer.transform)
        assertTrue(file(layer.imageFile).exists())
        assertEquals(s.wallpaper, repository.savedLayers.last())
        assertEquals("ducks.png", layer.sourceName)
        assertFalse(s.isBusy)
    }

    @Test
    fun `an image that cannot be decoded is not added and its copy is deleted`() = runTest(mainRule.testDispatcher) {
        nextAddSize = null
        val vm = createViewModel()
        advanceUntilIdle()

        vm.addLayer(null) { "garbage".byteInputStream() }
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

    // ---- position lock ----

    @Test
    fun `a fixed layer ignores drag and pinch, and the lock is saved`() = runTest(mainRule.testDispatcher) {
        val start = LayerTransform(1f, 50f, 50f)
        persist(WallpaperLayerState(id = "a", imageFile = "f", transform = start) to (100 to 100))
        val vm = createViewModel()
        advanceUntilIdle()

        vm.togglePositionLock()
        advanceUntilIdle()
        vm.gestureStart()
        assertFalse(vm.dragBy(-500f, 0f))
        vm.pinchBy(3f, 0f, 0f)
        vm.gestureEnd()
        advanceUntilIdle()

        assertTrue(vm.state.value.positionLocked)
        assertEquals(start, vm.state.value.wallpaper.layers.single().transform)
        assertTrue(repository.savedLayers.isEmpty())
        assertEquals(listOf(true), repository.savedLocks)
    }

    @Test
    fun `buttons and selection still work while fixed`() = runTest(mainRule.testDispatcher) {
        persist(
            WallpaperLayerState(id = "a", imageFile = "fa", transform = LayerTransform(1f, 0f, 0f)) to (300 to 500),
            WallpaperLayerState(id = "b", imageFile = "fb", transform = LayerTransform(1f, 0f, 0f)) to (100 to 100),
        )
        repository.persisted = repository.persisted.copy(positionLocked = true)
        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.positionLocked)
        assertTrue(vm.tapAt(200f, 300f))
        assertEquals("a", vm.state.value.activeLayerId)
        vm.fitActiveToWidth()
        advanceUntilIdle()
        assertEquals(LayerGeometry.fitWidth(300, 500, frameW, frameH), repository.savedLayers.last().layers[0].transform)
    }

    @Test
    fun `adding a layer releases the lock`() = runTest(mainRule.testDispatcher) {
        repository.persisted = repository.persisted.copy(positionLocked = true)
        val vm = createViewModel()
        advanceUntilIdle()

        vm.addLayer("new.png") { "image".byteInputStream() }
        advanceUntilIdle()

        assertFalse(vm.state.value.positionLocked)
        assertEquals(listOf(false), repository.savedLocks)
    }

    @Test
    fun `fixing during a snap-back lets it settle and save`() = runTest(mainRule.testDispatcher) {
        persist(WallpaperLayerState(id = "a", imageFile = "f", transform = LayerTransform(1f, 0f, 0f)) to (100 to 100))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.gestureStart()
        vm.dragBy(-50f, 0f)
        vm.gestureEnd()

        vm.togglePositionLock()
        advanceUntilIdle()

        assertEquals(LayerTransform(1f, 0f, 0f), repository.savedLayers.last().layers.single().transform)
    }

    // ---- projects ----

    private fun projectJson(vararg layers: ProjectLayer, frame: ProjectFrame? = ProjectFrame(frameW, frameH)) =
        ProjectCodec.encode(ProjectFile(frame = frame, layers = layers.toList())).toByteArray()

    /** An image in the project folder; it decodes at the size queued in `nextAddSize`. */
    private fun folderImage(name: String, content: String) {
        folder.files[name] = content.toByteArray()
    }

    private fun TestScope.vmWithFolder(): EditorViewModel {
        nextAddSize = 100 to 100
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setProjectFolder("content://tree/walled")
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `choosing a folder lists its projects and remembers it`() = runTest(mainRule.testDispatcher) {
        folder.files["b.json"] = projectJson()
        folder.files["a.json"] = projectJson()
        folderImage("ducks.png", "d")

        val vm = vmWithFolder()

        assertEquals("walled-layers", vm.state.value.projectFolderName)
        assertEquals(listOf("a.json", "b.json"), vm.state.value.projects)
        assertEquals(ProjectRef("content://tree/walled", null), repository.savedProjects.last())
    }

    @Test
    fun `a remembered folder is reopened at start, a lost one is forgotten`() = runTest(mainRule.testDispatcher) {
        repository.persisted = PersistedEditor(project = ProjectRef("content://tree/walled", "k.json"))
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals("walled-layers", vm.state.value.projectFolderName)
        assertEquals("k.json", vm.state.value.projectName)

        repository.persisted = PersistedEditor(project = ProjectRef("content://tree/gone", "k.json"))
        val lost = createViewModel()
        advanceUntilIdle()
        assertNull(lost.state.value.projectFolderName)
        assertEquals(ProjectRef(), repository.savedProjects.last())
    }

    @Test
    fun `opening a project replaces the layers with its images and placement`() = runTest(mainRule.testDispatcher) {
        persist(WallpaperLayerState(id = "old", imageFile = "fold") to (100 to 100))
        folderImage("ducks.png", "d")
        folderImage("seal.png", "s")
        folder.files["k.json"] = projectJson(
            ProjectLayer("ducks.png", 1.5f, 0f, 58f),
            ProjectLayer("seal.png"),
        )
        val vm = vmWithFolder()

        vm.openProject("k.json")
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(listOf("ducks.png", "seal.png"), s.wallpaper.layers.map { it.sourceName })
        assertEquals(LayerTransform(1.5f, 0f, 58f), s.wallpaper.layers[0].transform)
        assertNull(s.wallpaper.layers[1].transform)
        assertEquals(s.wallpaper.layers[1].id, s.activeLayerId)
        assertEquals(EditorMessage.ProjectOpened("k.json"), s.message)
        assertEquals("k.json", s.projectName)
        // The new copies are saved, the old layer's file is gone.
        assertEquals(s.wallpaper, repository.savedLayers.last())
        assertFalse(file("fold").exists())
        assertEquals("d", file(s.wallpaper.layers[0].imageFile).readText())
    }

    @Test
    fun `a project with a missing image changes nothing and leaves no copies`() = runTest(mainRule.testDispatcher) {
        persist(WallpaperLayerState(id = "old", imageFile = "fold") to (100 to 100))
        folderImage("ducks.png", "d")
        folder.files["k.json"] = projectJson(ProjectLayer("ducks.png"), ProjectLayer("gone.png"))
        val vm = vmWithFolder()

        vm.openProject("k.json")
        advanceUntilIdle()

        assertEquals(EditorMessage.ProjectFilesMissing(listOf("gone.png")), vm.state.value.message)
        assertEquals(listOf("old"), vm.state.value.wallpaper.layers.map { it.id })
        assertEquals(listOf("fold"), File(tmp.root, "layers").list()!!.toList())
        assertFalse(vm.state.value.isBusy)
    }

    @Test
    fun `an invalid project file is reported`() = runTest(mainRule.testDispatcher) {
        folder.files["x.json"] = """{ "format": "something-else" }""".toByteArray()
        val vm = vmWithFolder()

        vm.openProject("x.json")
        advanceUntilIdle()

        assertEquals(EditorMessage.ProjectInvalid(ProjectProblem.NOT_A_PROJECT), vm.state.value.message)
    }

    @Test
    fun `saving writes the json and copies images the folder does not have`() = runTest(mainRule.testDispatcher) {
        persist(
            WallpaperLayerState(id = "a", imageFile = "fa", transform = LayerTransform(2f, 3f, 4f), sourceName = "ducks.png") to (100 to 100),
            WallpaperLayerState(id = "b", imageFile = "fb", transform = LayerTransform(1f, 0f, 0f)) to (100 to 100),
        )
        file("fb").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val vm = vmWithFolder()

        vm.saveProject("kolibri")
        advanceUntilIdle()

        val project = (ProjectCodec.decode(folder.readText("kolibri.json")!!) as ProjectDecode.Ok).project
        assertEquals(ProjectFrame(frameW, frameH), project.frame)
        assertEquals(
            listOf(ProjectLayer("ducks.png", 2f, 3f, 4f), ProjectLayer("layer_2.png", 1f, 0f, 0f)),
            project.layers,
        )
        assertEquals("x", folder.files["ducks.png"]!!.decodeToString())
        assertEquals(EditorMessage.ProjectSaved("kolibri.json"), vm.state.value.message)
        assertEquals(listOf("kolibri.json"), vm.state.value.projects)
        // The layer without a source name now remembers the name it got.
        assertEquals("layer_2.png", vm.state.value.wallpaper.layers[1].sourceName)
    }

    @Test
    fun `saving reuses a same-size file and renames around a different one`() = runTest(mainRule.testDispatcher) {
        persist(
            WallpaperLayerState(id = "a", imageFile = "fa", sourceName = "same.png") to (100 to 100),
            WallpaperLayerState(id = "b", imageFile = "fb", sourceName = "clash.png") to (100 to 100),
        )
        folder.files["same.png"] = "x".toByteArray() // same size as the layer's file
        folder.files["clash.png"] = "something else".toByteArray()
        val vm = vmWithFolder()

        vm.saveProject("p")
        advanceUntilIdle()

        val project = (ProjectCodec.decode(folder.readText("p.json")!!) as ProjectDecode.Ok).project
        assertEquals(listOf("same.png", "clash (2).png"), project.layers.map { it.file })
        assertEquals("something else", folder.files["clash.png"]!!.decodeToString())
    }

    @Test
    fun `a failed write is reported and nothing is remembered`() = runTest(mainRule.testDispatcher) {
        persist(WallpaperLayerState(id = "a", imageFile = "fa", sourceName = "a.png") to (100 to 100))
        val vm = vmWithFolder()
        folder.failWrites = true

        vm.saveProject("p")
        advanceUntilIdle()

        assertEquals(EditorMessage.ProjectSaveFailed, vm.state.value.message)
        assertNull(vm.state.value.projectName)
        assertFalse(vm.state.value.isBusy)
    }
}

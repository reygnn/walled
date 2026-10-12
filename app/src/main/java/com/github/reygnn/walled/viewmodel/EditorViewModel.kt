package com.github.reygnn.walled.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.reygnn.walled.WalledApplication
import com.github.reygnn.walled.data.LayerImageStore
import com.github.reygnn.walled.data.ProjectFolder
import com.github.reygnn.walled.data.ProjectRef
import com.github.reygnn.walled.data.SafProjectFolder
import com.github.reygnn.walled.data.WallpaperRepository
import com.github.reygnn.walled.imaging.DecodedLayer
import com.github.reygnn.walled.imaging.LayerGeometry
import com.github.reygnn.walled.imaging.LayerSnap
import com.github.reygnn.walled.imaging.PlacedLayer
import com.github.reygnn.walled.imaging.WallpaperFlattener
import com.github.reygnn.walled.imaging.decodeLayer
import com.github.reygnn.walled.model.LayerTransform
import com.github.reygnn.walled.model.MemoryReport
import com.github.reygnn.walled.model.MemoryRow
import com.github.reygnn.walled.model.ProjectCodec
import com.github.reygnn.walled.model.ProjectDecode
import com.github.reygnn.walled.model.ProjectFile
import com.github.reygnn.walled.model.ProjectFrame
import com.github.reygnn.walled.model.ProjectMapping
import com.github.reygnn.walled.model.ProjectProblem
import com.github.reygnn.walled.model.SnapMode
import com.github.reygnn.walled.model.SnapSettings
import com.github.reygnn.walled.model.WallpaperLayerState
import com.github.reygnn.walled.model.WallpaperState
import com.github.reygnn.walled.model.WallpaperTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.pow

/** One-shot feedback the screen shows as a toast, then clears. */
sealed interface EditorMessage {
    data class Applied(val target: WallpaperTarget) : EditorMessage
    data object ApplyFailed : EditorMessage
    data object AddFailed : EditorMessage
    data class LayersUnloadable(val count: Int) : EditorMessage
    data class ProjectOpened(val name: String) : EditorMessage
    data class ProjectSaved(val name: String) : EditorMessage
    data class ProjectInvalid(val problem: ProjectProblem) : EditorMessage
    /** The project names images the folder does not have (or that cannot be decoded). */
    data class ProjectFilesMissing(val files: List<String>) : EditorMessage
    data object ProjectSaveFailed : EditorMessage
    /** The chosen project folder is no longer accessible; choose it again. */
    data object FolderUnavailable : EditorMessage
}

data class EditorUiState(
    /** False until the persisted editor is loaded. */
    val loaded: Boolean = false,
    /** The output frame: the device screen in portrait, in px. */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val wallpaper: WallpaperState = WallpaperState.NONE,
    /** Decoded preview bitmaps by layer ID; a layer without an entry is loading or unloadable. */
    val decoded: Map<String, DecodedLayer> = emptyMap(),
    /** Layers whose image could not be decoded; they block [WallpaperFlattener.flatten]. */
    val unloadable: Set<String> = emptySet(),
    val activeLayerId: String? = null,
    val snap: SnapSettings = SnapSettings(),
    val isBusy: Boolean = false,
    val message: EditorMessage? = null,
    /** Name of the chosen project folder, or null when none is set. */
    val projectFolderName: String? = null,
    /** Project files in the folder. */
    val projects: List<String> = emptyList(),
    /** The project last opened or saved — the default name for the next save. */
    val projectName: String? = null,
) {
    val hasFrame: Boolean get() = frameWidth > 0 && frameHeight > 0

    val activeIndex: Int? get() = activeLayerId?.let { wallpaper.indexOf(it) }

    val canApply: Boolean get() = hasFrame && wallpaper.hasWallpaper && unloadable.isEmpty() && !isBusy

    /** The decoded layers, placed in the frame, bottom-most first — what the canvas draws. */
    val placedLayers: List<PlacedLayer>
        get() = if (!hasFrame) emptyList() else wallpaper.layers.mapNotNull { layer ->
            val d = decoded[layer.id] ?: return@mapNotNull null
            PlacedLayer(
                id = layer.id,
                decoded = d,
                transform = LayerGeometry.resolve(
                    layer.transform, d.originalWidth, d.originalHeight, frameWidth, frameHeight,
                ),
            )
        }
}

/**
 * The editor: the layer stack, the active layer's gestures, snapping, and the
 * final "set as wallpaper".
 *
 * Unlike the launcher there is no edit session with commit/cancel — the app IS
 * the editor. Every finished change (a gesture end, a layer op) is persisted at
 * once, so nothing is lost to process death; files of removed layers are deleted
 * only after the state without them is saved ([persistLayers]), and anything a
 * kill left behind is caught by the orphan GC at the next start.
 *
 * Gestures arrive in FRAME pixels — the canvas maps its own coordinates first.
 */
class EditorViewModel(
    private val repository: WallpaperRepository,
    private val imageStore: LayerImageStore,
    private val decoder: LayerDecoder,
    private val exporter: WallpaperExporter,
    /** Opens a persisted project folder by its tree URI; null when access is gone. */
    private val folderOpener: (String) -> ProjectFolder?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** Decodes a stored layer image for the preview. */
    fun interface LayerDecoder {
        fun decode(fileName: String): DecodedLayer?
    }

    /** Renders [state] at frame size and sets it; false when it could not be rendered. */
    fun interface WallpaperExporter {
        fun export(state: WallpaperState, frameWidth: Int, frameHeight: Int, target: WallpaperTarget): Boolean
    }

    companion object {
        private const val TAG = "EditorViewModel"
        const val MAX_PARALLEL_DECODES = 2
        const val SNAP_BACK_DURATION_MS = 250L
        const val SNAP_BACK_FRAME_MS = 16L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WalledApplication
                EditorViewModel(
                    repository = app.repository,
                    imageStore = app.imageStore,
                    decoder = { name -> decodeLayer(software = false) { app.imageStore.open(name) } },
                    exporter = { state, w, h, target -> exportToSystem(app, app.imageStore, state, w, h, target) },
                    folderOpener = { uri -> SafProjectFolder.open(app, uri) },
                )
            }
        }

        private fun exportToSystem(
            context: Context,
            store: LayerImageStore,
            state: WallpaperState,
            w: Int,
            h: Int,
            target: WallpaperTarget,
        ): Boolean {
            val bitmap: Bitmap = WallpaperFlattener.flatten(state, w, h, store::open) ?: return false
            try {
                WallpaperFlattener.setWallpaper(context, bitmap, target)
            } finally {
                bitmap.recycle()
            }
            return true
        }
    }

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** Serializes the writes so they land in call order. */
    private val writeLock = Mutex()
    private var snapJob: Job? = null
    /** Edges the running drag already pushes against — the haptic fires once per edge. */
    private val engagedEdges = mutableSetOf<LayerSnap.Edge>()
    /** The running gesture moved or zoomed something — only then does its end save. */
    private var gestureChanged = false
    private var folder: ProjectFolder? = null
    private var folderUri: String? = null

    init {
        viewModelScope.launch {
            val persisted = withContext(ioDispatcher) {
                val p = repository.load()
                imageStore.deleteOrphans(p.state.referencedFiles)
                p
            }
            _state.update {
                it.copy(
                    loaded = true,
                    wallpaper = persisted.state,
                    snap = persisted.snap,
                    activeLayerId = persisted.state.layers.lastOrNull()?.id,
                    projectName = persisted.project.name,
                )
            }
            persisted.project.folderUri?.let { restoreFolder(it) }
            decodeAll(persisted.state.layers)
        }
    }

    // ---- frame ----

    fun setFrameSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        _state.update { it.copy(frameWidth = width, frameHeight = height) }
    }

    // ---- layers ----

    /**
     * Copies a picked image in and adds it on top, fitted to the frame width (as
     * the launcher's editor does), and makes it the active layer.
     */
    fun addLayer(sourceName: String?, source: () -> InputStream?) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                val name = imageStore.copyIn(source) ?: return@withContext null
                val decoded = safeDecode(name)
                if (decoded == null) {
                    imageStore.delete(listOf(name))
                    null
                } else {
                    name to decoded
                }
            }
            if (result == null) {
                _state.update { it.copy(isBusy = false, message = EditorMessage.AddFailed) }
                return@launch
            }
            val (name, decoded) = result
            val current = _state.value
            val transform = if (current.hasFrame) {
                LayerGeometry.fitWidth(decoded.originalWidth, decoded.originalHeight, current.frameWidth, current.frameHeight)
            } else {
                null
            }
            val layer = WallpaperLayerState(
                imageFile = name,
                transform = transform,
                sourceName = sourceName?.let(ProjectCodec::sanitizeFileName)?.takeIf(ProjectCodec::isPlainFileName),
            )
            _state.update {
                it.copy(
                    isBusy = false,
                    wallpaper = it.wallpaper.withAddedLayer(layer),
                    decoded = it.decoded + (layer.id to decoded),
                    activeLayerId = layer.id,
                )
            }
            persistLayers()
        }
    }

    fun removeActiveLayer() {
        val id = _state.value.activeLayerId ?: return
        removeLayers(setOf(id))
    }

    /** Removes the layers whose image could not be decoded, so the wallpaper can be applied again. */
    fun removeUnloadableLayers() = removeLayers(_state.value.unloadable)

    fun removeAllLayers() = removeLayers(_state.value.wallpaper.layers.mapTo(mutableSetOf()) { it.id })

    private fun removeLayers(ids: Set<String>) {
        if (ids.isEmpty()) return
        cancelSnapBack()
        val before = _state.value.wallpaper
        val removedFiles = before.layers.filter { it.id in ids }.map { it.imageFile }
        if (removedFiles.isEmpty()) return
        _state.update { s ->
            val next = s.wallpaper.withRemovedLayers(ids)
            s.copy(
                wallpaper = next,
                decoded = s.decoded - ids,
                unloadable = s.unloadable - ids,
                activeLayerId = s.activeLayerId?.takeIf { it !in ids } ?: next.layers.lastOrNull()?.id,
            )
        }
        persistLayers(deleteAfterSave = removedFiles)
    }

    /** Moves the active layer up (+1, towards the top) or down (-1). */
    fun moveActiveLayer(delta: Int) {
        val id = _state.value.activeLayerId ?: return
        val before = _state.value.wallpaper
        val after = before.withMovedLayer(id, delta)
        if (after == before) return
        _state.update { it.copy(wallpaper = after) }
        persistLayers()
    }

    fun fitActiveToWidth() = placeActive(LayerGeometry::fitWidth)

    fun showActiveAtOriginalSize() = placeActive(LayerGeometry::originalSize)

    fun centerCropActive() = placeActive(LayerGeometry::centerCrop)

    private fun placeActive(place: (Int, Int, Int, Int) -> LayerTransform) {
        val s = _state.value
        val id = s.activeLayerId ?: return
        val d = s.decoded[id] ?: return
        if (!s.hasFrame) return
        cancelSnapBack()
        setTransform(id, place(d.originalWidth, d.originalHeight, s.frameWidth, s.frameHeight))
        persistLayers()
    }

    // ---- gestures (frame px) ----

    /** Selects the topmost layer under ([x], [y]); true when the selection changed. */
    fun tapAt(x: Float, y: Float): Boolean {
        val s = _state.value
        val hit = s.placedLayers.lastOrNull { p ->
            LayerGeometry.contains(p.transform, p.decoded.originalWidth, p.decoded.originalHeight, x, y)
        } ?: return false
        if (hit.id == s.activeLayerId) return false
        _state.update { it.copy(activeLayerId = hit.id) }
        return true
    }

    fun gestureStart() {
        // A snap-back cut short leaves an unsaved position: the new gesture's end saves it.
        gestureChanged = snapJob?.isActive == true
        cancelSnapBack()
        engagedEdges.clear()
    }

    /**
     * Drags the active layer by ([dx], [dy]), damped at the edges while snapping
     * is on. Returns true when the drag just reached a new edge (haptic tick).
     */
    fun dragBy(dx: Float, dy: Float): Boolean {
        val s = _state.value
        val active = s.placedLayers.firstOrNull { it.id == s.activeLayerId } ?: return false
        val t = active.transform
        val r = LayerSnap.resist(
            t, dx, dy, active.decoded.originalWidth, active.decoded.originalHeight,
            s.frameWidth, s.frameHeight, s.snap,
        )
        setTransform(active.id, t.copy(translateX = t.translateX + r.dx, translateY = t.translateY + r.dy))
        gestureChanged = true
        val newEdges = r.edges - engagedEdges
        // An edge the drag left again may tick again on its next contact.
        engagedEdges.retainAll(r.edges)
        engagedEdges.addAll(r.edges)
        return newEdges.isNotEmpty()
    }

    /** One pinch step on the active layer around ([focusX], [focusY]); stops at its zoom range. */
    fun pinchBy(factor: Float, focusX: Float, focusY: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        val s = _state.value
        val active = s.placedLayers.firstOrNull { it.id == s.activeLayerId } ?: return
        val d = active.decoded
        val range = LayerGeometry.scaleRange(d.originalWidth, d.originalHeight, s.frameWidth, s.frameHeight)
        val next = LayerGeometry.pinch(active.transform, factor, focusX, focusY, range)
        if (next != active.transform) {
            setTransform(active.id, next)
            gestureChanged = true
        }
    }

    /** The gesture ended: snap back if snapping wants it, then persist. A bare tap saves nothing. */
    fun gestureEnd() {
        engagedEdges.clear()
        if (!gestureChanged) return
        gestureChanged = false
        val s = _state.value
        val active = s.placedLayers.firstOrNull { it.id == s.activeLayerId }
        val target = active?.let {
            LayerSnap.snapTarget(
                it.transform, it.decoded.originalWidth, it.decoded.originalHeight,
                s.frameWidth, s.frameHeight, s.snap,
            )
        }
        if (active == null || target == null) {
            persistLayers()
            return
        }
        val start = active.transform
        snapJob = viewModelScope.launch {
            val frames = (SNAP_BACK_DURATION_MS / SNAP_BACK_FRAME_MS).toInt()
            for (i in 1..frames) {
                delay(SNAP_BACK_FRAME_MS)
                // DecelerateInterpolator(2f): 1 - (1 - t)^4.
                val p = 1f - (1f - i.toFloat() / frames).pow(4)
                setTransform(
                    active.id,
                    start.copy(
                        translateX = start.translateX + (target.translateX - start.translateX) * p,
                        translateY = start.translateY + (target.translateY - start.translateY) * p,
                    ),
                )
            }
            setTransform(active.id, target)
            persistLayers()
        }
    }

    private fun cancelSnapBack() {
        snapJob?.cancel()
        snapJob = null
    }

    private fun setTransform(layerId: String, transform: LayerTransform) {
        _state.update { it.copy(wallpaper = it.wallpaper.withTransform(layerId, transform)) }
    }

    // ---- snap settings ----

    fun toggleSnap() = updateSnap { it.copy(enabled = !it.enabled) }

    fun toggleSnapMode() = updateSnap {
        it.copy(mode = if (it.mode == SnapMode.EDGE) SnapMode.CENTER else SnapMode.EDGE)
    }

    fun toggleHorizontalSnap() = updateSnap { it.copy(horizontal = !it.horizontal) }

    fun toggleVerticalSnap() = updateSnap { it.copy(vertical = !it.vertical) }

    private fun updateSnap(change: (SnapSettings) -> SnapSettings) {
        _state.update { it.copy(snap = change(it.snap)) }
        val snap = _state.value.snap
        viewModelScope.launch {
            writeLock.withLock { withContext(ioDispatcher) { repository.saveSnap(snap) } }
        }
    }

    // ---- output ----

    fun apply(target: WallpaperTarget) {
        val s = _state.value
        if (!s.canApply) return
        cancelSnapBack()
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val ok = try {
                withContext(ioDispatcher) { exporter.export(s.wallpaper, s.frameWidth, s.frameHeight, target) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // An allocation boundary (OOM is an Error) plus WallpaperManager I/O.
                Log.e(TAG, "Setting the wallpaper failed", e)
                false
            }
            _state.update {
                it.copy(
                    isBusy = false,
                    message = if (ok) EditorMessage.Applied(target) else EditorMessage.ApplyFailed,
                )
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun memoryReport(): MemoryReport {
        val s = _state.value
        val rows = s.wallpaper.layers.mapIndexedNotNull { index, layer ->
            val d = s.decoded[layer.id] ?: return@mapIndexedNotNull null
            MemoryRow(
                position = index + 1,
                decodedWidth = d.bitmap.width,
                decodedHeight = d.bitmap.height,
                sampleSize = d.sampleSize,
                originalWidth = d.originalWidth,
                originalHeight = d.originalHeight,
                bytes = d.bitmap.allocationByteCount.toLong(),
                config = d.bitmap.config?.name ?: "?",
            )
        }
        return MemoryReport(rows)
    }

    // ---- projects ----

    /** The user picked a project folder (its grant is already persisted). */
    fun setProjectFolder(treeUri: String) {
        viewModelScope.launch {
            val opened = withContext(ioDispatcher) { folderOpener(treeUri)?.let { it to it.listProjects() } }
            if (opened == null) {
                _state.update { it.copy(message = EditorMessage.FolderUnavailable) }
                return@launch
            }
            folder = opened.first
            folderUri = treeUri
            _state.update {
                it.copy(projectFolderName = opened.first.displayName, projects = opened.second, projectName = null)
            }
            saveProjectRef()
        }
    }

    private suspend fun restoreFolder(treeUri: String) {
        val opened = withContext(ioDispatcher) { folderOpener(treeUri)?.let { it to it.listProjects() } }
        if (opened == null) {
            // The grant is gone: forget the folder so the menu asks for it again.
            _state.update { it.copy(projectName = null) }
            writeLock.withLock { withContext(ioDispatcher) { repository.saveProject(ProjectRef()) } }
            return
        }
        folder = opened.first
        folderUri = treeUri
        _state.update { it.copy(projectFolderName = opened.first.displayName, projects = opened.second) }
    }

    fun refreshProjects() {
        val f = folder ?: return
        viewModelScope.launch {
            val list = withContext(ioDispatcher) { f.listProjects() }
            _state.update { it.copy(projects = list) }
        }
    }

    /**
     * Replaces all layers with the project [name]. All or nothing: every image is
     * copied in and decoded first; if one is missing or broken, nothing changes.
     */
    fun openProject(name: String) {
        val f = folder ?: return
        val s = _state.value
        if (s.isBusy) return
        cancelSnapBack()
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { loadProject(f, name, s.frameWidth, s.frameHeight) }
            when (result) {
                is ProjectLoad.Failed -> _state.update { it.copy(isBusy = false, message = result.message) }
                is ProjectLoad.Loaded -> {
                    val oldFiles = _state.value.wallpaper.layers.map { it.imageFile }
                    val next = WallpaperState(result.layers.map { it.first })
                    _state.update {
                        it.copy(
                            isBusy = false,
                            wallpaper = next,
                            decoded = result.layers.associate { (layer, d) -> layer.id to d },
                            unloadable = emptySet(),
                            activeLayerId = next.layers.lastOrNull()?.id,
                            projectName = name,
                            message = EditorMessage.ProjectOpened(name),
                        )
                    }
                    persistLayers(deleteAfterSave = oldFiles)
                    saveProjectRef()
                }
            }
        }
    }

    private sealed interface ProjectLoad {
        data class Loaded(val layers: List<Pair<WallpaperLayerState, DecodedLayer>>) : ProjectLoad
        data class Failed(val message: EditorMessage) : ProjectLoad
    }

    /** Blocking: reads, copies in and decodes a project. Cleans up its copies on failure. */
    private fun loadProject(f: ProjectFolder, name: String, frameW: Int, frameH: Int): ProjectLoad {
        val raw = f.readText(name) ?: return ProjectLoad.Failed(EditorMessage.ProjectFilesMissing(listOf(name)))
        val project = when (val d = ProjectCodec.decode(raw)) {
            is ProjectDecode.Invalid -> return ProjectLoad.Failed(EditorMessage.ProjectInvalid(d.problem))
            is ProjectDecode.Ok -> d.project
        }
        val loaded = mutableListOf<Pair<WallpaperLayerState, DecodedLayer>>()
        val missing = mutableListOf<String>()
        for (entry in project.layers) {
            val copied = imageStore.copyIn { f.openFile(entry.file) }
            val decoded = copied?.let { safeDecode(it) }
            if (copied == null || decoded == null) {
                copied?.let { imageStore.delete(listOf(it)) }
                missing += entry.file
                continue
            }
            val layer = WallpaperLayerState(
                imageFile = copied,
                transform = ProjectMapping.transformFor(entry, project.frame, frameW, frameH),
                sourceName = entry.file,
            )
            loaded += layer to decoded
        }
        if (missing.isNotEmpty()) {
            imageStore.delete(loaded.map { it.first.imageFile })
            return ProjectLoad.Failed(EditorMessage.ProjectFilesMissing(missing.distinct()))
        }
        return ProjectLoad.Loaded(loaded)
    }

    /**
     * Writes the current layers as project [input] into the folder. Each image is
     * saved under its source name; one already there with the same size is reused,
     * a different file of that name gets an alternative (`name (2).png`).
     */
    fun saveProject(input: String) {
        val f = folder ?: return
        val fileName = ProjectCodec.projectFileName(input) ?: return
        val s = _state.value
        if (s.isBusy || !s.hasFrame) return
        cancelSnapBack()
        _state.update { it.copy(isBusy = true) }
        val placed = s.placedLayers.associateBy { it.id }
        viewModelScope.launch {
            val written = withContext(ioDispatcher) {
                try {
                    writeProject(f, fileName, s, placed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Saving project $fileName failed", e)
                    null
                }
            }
            if (written == null) {
                _state.update { it.copy(isBusy = false, message = EditorMessage.ProjectSaveFailed) }
                return@launch
            }
            val list = withContext(ioDispatcher) { f.listProjects() }
            _state.update { st ->
                st.copy(
                    isBusy = false,
                    // Remember the names the images now have in the folder, so the next save reuses them.
                    wallpaper = st.wallpaper.copy(
                        layers = st.wallpaper.layers.map { l -> written[l.id]?.let { l.copy(sourceName = it) } ?: l },
                    ),
                    projects = list,
                    projectName = fileName,
                    message = EditorMessage.ProjectSaved(fileName),
                )
            }
            persistLayers()
            saveProjectRef()
        }
    }

    /** Blocking. Returns layer ID → file name in the folder, or null on a failed write. */
    private fun writeProject(
        f: ProjectFolder,
        fileName: String,
        s: EditorUiState,
        placed: Map<String, PlacedLayer>,
    ): Map<String, String>? {
        val namesById = mutableMapOf<String, String>()
        // Name → size of the image a layer of this save already put there.
        val taken = mutableMapOf<String, Long>()
        val layers = s.wallpaper.layers.mapIndexed { index, layer ->
            val size = imageStore.length(layer.imageFile) ?: return null
            val wanted = layer.sourceName
                ?: ("layer_${index + 1}" + ProjectCodec.imageExtension(imageStore.header(layer.imageFile) ?: ByteArray(0)))
            var n = 1
            var name = wanted
            while (true) {
                // The same image (by size) again: share the file. Otherwise take a free name,
                // or one whose existing file has this size (an earlier save of the same image).
                if (taken[name] == size) break
                if (name !in taken) {
                    val existing = f.length(name)
                    if (existing == null || existing == size) break
                }
                n++
                name = ProjectCodec.alternativeName(wanted, n)
            }
            if (f.length(name) != size && !f.writeFile(name) { imageStore.open(layer.imageFile) }) return null
            taken[name] = size
            namesById[layer.id] = name
            ProjectMapping.toProjectLayer(name, placed[layer.id]?.transform ?: layer.transform)
        }
        val project = ProjectFile(frame = ProjectFrame(s.frameWidth, s.frameHeight), layers = layers)
        if (!f.writeText(fileName, ProjectCodec.encode(project))) return null
        return namesById
    }

    private fun saveProjectRef() {
        val ref = ProjectRef(folderUri = folderUri, name = _state.value.projectName)
        viewModelScope.launch {
            writeLock.withLock { withContext(ioDispatcher) { repository.saveProject(ref) } }
        }
    }

    // ---- internals ----

    private suspend fun decodeAll(layers: List<WallpaperLayerState>) {
        if (layers.isEmpty()) return
        val gate = Semaphore(MAX_PARALLEL_DECODES)
        val results = withContext(ioDispatcher) {
            layers.map { layer -> async { gate.withPermit { layer.id to safeDecode(layer.imageFile) } } }.awaitAll()
        }
        val decoded = results.mapNotNull { (id, d) -> d?.let { id to it } }.toMap()
        val failed = results.filter { it.second == null }.mapTo(mutableSetOf()) { it.first }
        _state.update {
            it.copy(
                decoded = it.decoded + decoded,
                unloadable = it.unloadable + failed,
                message = if (failed.isEmpty()) it.message else EditorMessage.LayersUnloadable(failed.size),
            )
        }
    }

    private fun safeDecode(fileName: String): DecodedLayer? = try {
        decoder.decode(fileName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // A decode is an allocation boundary (OOM extends Error).
        Log.w(TAG, "Decoding $fileName failed", e)
        null
    }

    /**
     * Saves the current layers, then deletes [deleteAfterSave] — never before the
     * state without them is on disk. Writes are serialized in call order.
     */
    private fun persistLayers(deleteAfterSave: List<String> = emptyList()) {
        val snapshot = _state.value.wallpaper
        viewModelScope.launch {
            writeLock.withLock {
                withContext(ioDispatcher) {
                    try {
                        repository.saveLayers(snapshot)
                        imageStore.delete(deleteAfterSave)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Files stay; the orphan GC collects them once a later save lands.
                        Log.e(TAG, "Saving the layers failed", e)
                    }
                }
            }
        }
    }
}

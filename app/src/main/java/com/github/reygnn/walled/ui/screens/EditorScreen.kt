package com.github.reygnn.walled.ui.screens

import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.view.HapticFeedbackConstants
import com.github.reygnn.walled.R
import com.github.reygnn.walled.model.MemoryReport
import com.github.reygnn.walled.model.WallpaperTarget
import com.github.reygnn.walled.ui.components.CommandsPanel
import com.github.reygnn.walled.ui.components.ConfirmRemoveAllDialog
import com.github.reygnn.walled.ui.components.EditorFab
import com.github.reygnn.walled.ui.components.LayerCanvas
import com.github.reygnn.walled.ui.components.MemoryDialog
import com.github.reygnn.walled.viewmodel.EditorMessage
import com.github.reygnn.walled.viewmodel.EditorViewModel
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel = viewModel(factory = EditorViewModel.Factory),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var commandsOpen by remember { mutableStateOf(false) }
    var memoryReport by remember { mutableStateOf<MemoryReport?>(null) }
    var confirmRemoveAll by remember { mutableStateOf(false) }

    // The output frame is the whole display in portrait — what the system
    // wallpaper covers. maximumWindowMetrics ignores the hidden system bars.
    LaunchedEffect(Unit) {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        viewModel.setFrameSize(min(bounds.width(), bounds.height()), max(bounds.width(), bounds.height()))
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.addLayer { context.contentResolver.openInputStream(uri) }
    }

    val homeSet = stringResource(R.string.msg_home_set)
    val lockSet = stringResource(R.string.msg_lock_set)
    val bothSet = stringResource(R.string.msg_both_set)
    val applyFailed = stringResource(R.string.msg_apply_failed)
    val addFailed = stringResource(R.string.msg_add_failed)
    val unloadableText = state.message.let { msg ->
        if (msg is EditorMessage.LayersUnloadable) {
            pluralStringResource(R.plurals.msg_layers_unloadable, msg.count, msg.count)
        } else {
            ""
        }
    }
    LaunchedEffect(state.message) {
        val (text, long) = when (val msg = state.message) {
            is EditorMessage.Applied -> when (msg.target) {
                WallpaperTarget.HOME -> homeSet
                WallpaperTarget.LOCK -> lockSet
                WallpaperTarget.BOTH -> bothSet
            } to false
            EditorMessage.ApplyFailed -> applyFailed to true
            EditorMessage.AddFailed -> addFailed to true
            is EditorMessage.LayersUnloadable -> unloadableText to true
            null -> return@LaunchedEffect
        }
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        viewModel.clearMessage()
    }

    // Zero content insets: the bars are hidden, the preview fills the screen.
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LayerCanvas(
                layers = state.placedLayers,
                activeLayerId = state.activeLayerId,
                frameWidth = state.frameWidth,
                frameHeight = state.frameHeight,
                onTap = { p ->
                    if (viewModel.tapAt(p.x, p.y)) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                },
                onGestureStart = viewModel::gestureStart,
                onDrag = { d -> viewModel.dragBy(d.x, d.y) },
                onPinch = { factor, focus -> viewModel.pinchBy(factor, focus.x, focus.y) },
                onGestureEnd = viewModel::gestureEnd,
            )

            if (state.loaded && !state.wallpaper.hasWallpaper) {
                Text(
                    text = stringResource(R.string.editor_empty_state),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            if (state.isBusy) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            if (commandsOpen) {
                CommandsPanel(
                    activePosition = state.activeIndex?.plus(1),
                    layerCount = state.wallpaper.layerCount,
                    unloadableCount = state.unloadable.size,
                    snap = state.snap,
                    onMoveUp = { viewModel.moveActiveLayer(+1) },
                    onMoveDown = { viewModel.moveActiveLayer(-1) },
                    onRemoveLayer = viewModel::removeActiveLayer,
                    onRemoveUnloadable = viewModel::removeUnloadableLayers,
                    onToggleSnap = viewModel::toggleSnap,
                    onToggleSnapMode = viewModel::toggleSnapMode,
                    onToggleHorizontalSnap = viewModel::toggleHorizontalSnap,
                    onToggleVerticalSnap = viewModel::toggleVerticalSnap,
                    onShowMemory = { memoryReport = viewModel.memoryReport() },
                    onRemoveAll = { confirmRemoveAll = true },
                    onClose = { commandsOpen = false },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )
            }

            EditorFab(
                hasActiveLayer = state.activeLayerId != null && state.activeLayerId in state.decoded,
                canApply = state.canApply,
                isBusy = state.isBusy,
                onAddLayer = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onFitWidth = viewModel::fitActiveToWidth,
                onOriginalSize = viewModel::showActiveAtOriginalSize,
                onCenterCrop = viewModel::centerCropActive,
                onApply = viewModel::apply,
                onOpenCommands = { commandsOpen = true },
                // Lifted by the navigation-bar inset (ignoring visibility, the bars
                // are hidden) so the FAB stays out of the gesture area.
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(16.dp),
            )
        }
    }

    memoryReport?.let { report ->
        MemoryDialog(report = report, onDismiss = { memoryReport = null })
    }
    if (confirmRemoveAll) {
        ConfirmRemoveAllDialog(
            onConfirm = {
                confirmRemoveAll = false
                viewModel.removeAllLayers()
            },
            onDismiss = { confirmRemoveAll = false },
        )
    }
}

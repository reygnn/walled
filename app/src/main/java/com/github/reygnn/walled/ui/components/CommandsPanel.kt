package com.github.reygnn.walled.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.github.reygnn.walled.R
import com.github.reygnn.walled.model.SnapMode
import com.github.reygnn.walled.model.SnapSettings
import kotlin.math.roundToInt

/**
 * Floating card with the less frequent commands, opened from the dial's ☰ —
 * the Compose counterpart of the launcher's `CommandsPanel`: the layer stack
 * (indicator, up/down, delete), the snap toggles, unloadable layers, the memory
 * info and "remove all". The title row is the drag handle.
 */
@Composable
fun CommandsPanel(
    /** 1-based position of the active layer, or null without one. */
    activePosition: Int?,
    layerCount: Int,
    unloadableCount: Int,
    snap: SnapSettings,
    projectFolderName: String?,
    projectName: String?,
    onChooseFolder: () -> Unit,
    onOpenProject: () -> Unit,
    onSaveProject: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemoveLayer: () -> Unit,
    onRemoveUnloadable: () -> Unit,
    onToggleSnap: () -> Unit,
    onToggleSnapMode: () -> Unit,
    onToggleHorizontalSnap: () -> Unit,
    onToggleVerticalSnap: () -> Unit,
    onShowMemory: () -> Unit,
    onRemoveAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }

    Card(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .widthIn(min = 280.dp, max = 360.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            offsetX += drag.x
                            offsetY += drag.y
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.cp_title), style = MaterialTheme.typography.titleMedium)
                val closeDesc = stringResource(R.string.cd_close_panel)
                TextButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = closeDesc }) {
                    Text("✕")
                }
            }

            // ---- projects ----
            Text(stringResource(R.string.cp_section_projects), style = MaterialTheme.typography.labelMedium)
            Text(
                text = when {
                    projectFolderName == null -> stringResource(R.string.cp_no_folder)
                    projectName == null -> stringResource(R.string.cp_folder, projectFolderName)
                    else -> stringResource(R.string.cp_folder_project, projectFolderName, projectName)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenProject,
                    enabled = projectFolderName != null,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.cp_btn_open_project)) }
                OutlinedButton(
                    onClick = onSaveProject,
                    enabled = projectFolderName != null && layerCount > 0,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.cp_btn_save_project)) }
            }
            OutlinedButton(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cp_btn_choose_folder))
            }

            HorizontalDivider()

            // ---- layer stack ----
            Text(
                text = if (activePosition != null) {
                    stringResource(R.string.cp_layer_indicator, activePosition, layerCount)
                } else {
                    stringResource(R.string.cp_no_layer)
                },
                style = MaterialTheme.typography.labelMedium,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onMoveUp,
                    enabled = activePosition != null && activePosition < layerCount,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.cp_btn_layer_up)) }
                OutlinedButton(
                    onClick = onMoveDown,
                    enabled = activePosition != null && activePosition > 1,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.cp_btn_layer_down)) }
            }
            OutlinedButton(
                onClick = onRemoveLayer,
                enabled = activePosition != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.cp_btn_remove_layer)) }

            if (unloadableCount > 0) {
                Text(
                    text = pluralStringResource(R.plurals.cp_unloadable, unloadableCount, unloadableCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onRemoveUnloadable, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cp_btn_remove_unloadable))
                }
            }

            HorizontalDivider()

            // ---- snapping ----
            Text(stringResource(R.string.cp_section_snap), style = MaterialTheme.typography.labelMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = snap.enabled,
                    onClick = onToggleSnap,
                    label = { Text(stringResource(R.string.cp_chip_snap)) },
                )
                FilterChip(
                    selected = snap.mode == SnapMode.CENTER,
                    onClick = onToggleSnapMode,
                    enabled = snap.enabled,
                    label = {
                        Text(
                            stringResource(
                                if (snap.mode == SnapMode.EDGE) R.string.cp_chip_mode_edge else R.string.cp_chip_mode_center,
                            ),
                        )
                    },
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = snap.horizontal,
                    onClick = onToggleHorizontalSnap,
                    enabled = snap.enabled,
                    label = { Text(stringResource(R.string.cp_chip_horizontal)) },
                )
                FilterChip(
                    selected = snap.vertical,
                    onClick = onToggleVerticalSnap,
                    enabled = snap.enabled,
                    label = { Text(stringResource(R.string.cp_chip_vertical)) },
                )
            }

            HorizontalDivider()

            OutlinedButton(
                onClick = onShowMemory,
                enabled = layerCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.cp_btn_memory)) }
            OutlinedButton(
                onClick = onRemoveAll,
                enabled = layerCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.cp_btn_remove_all)) }
        }
    }
}

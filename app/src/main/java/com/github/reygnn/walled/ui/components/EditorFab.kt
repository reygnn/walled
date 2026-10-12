package com.github.reygnn.walled.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.reygnn.walled.R
import com.github.reygnn.walled.model.WallpaperTarget
import kotlin.math.roundToInt

/**
 * Draggable speed-dial FAB — the sibling apps' control idiom (wallpan,
 * chiaroscuro), replacing the launcher's View-based `SpeedDialFabCluster`.
 *
 * The dial holds the frequent commands: ☰ opens the [CommandsPanel], the
 * set-wallpaper row (🏠 home, 🔒 lock, both), the placement row for the active
 * layer (↔ fit width, 1:1 original size, ⛶ fill), 📌 fix the layers against
 * stray gestures, and ➕ add layer. The FAB position is local UI state.
 */
@Composable
fun EditorFab(
    hasActiveLayer: Boolean,
    canApply: Boolean,
    isBusy: Boolean,
    positionLocked: Boolean,
    onTogglePositionLock: () -> Unit,
    onAddLayer: () -> Unit,
    onFitWidth: () -> Unit,
    onOriginalSize: () -> Unit,
    onCenterCrop: () -> Unit,
    onApply: (WallpaperTarget) -> Unit,
    onOpenCommands: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    offset += drag
                }
            },
    ) {
        Column(
            // End-aligned so the main FAB does not jump sideways when the wider
            // rows of the dial appear.
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(visible = expanded) {
                Column(
                    // Half the 56→40 dp size difference: minis sit centered under the main FAB.
                    modifier = Modifier.padding(end = 8.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MiniFab(
                        symbol = "☰",
                        contentDescription = stringResource(R.string.cd_open_commands),
                        enabled = true,
                        onClick = { expanded = false; onOpenCommands() },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniFab(
                            symbol = "🏠",
                            contentDescription = stringResource(R.string.cd_set_home),
                            enabled = canApply,
                            onClick = { expanded = false; onApply(WallpaperTarget.HOME) },
                        )
                        MiniFab(
                            symbol = "🔒",
                            contentDescription = stringResource(R.string.cd_set_lock),
                            enabled = canApply,
                            onClick = { expanded = false; onApply(WallpaperTarget.LOCK) },
                        )
                        MiniFab(
                            symbol = "📱",
                            contentDescription = stringResource(R.string.cd_set_both),
                            enabled = canApply,
                            onClick = { expanded = false; onApply(WallpaperTarget.BOTH) },
                        )
                    }
                    // Placement presets stay open across taps: they are tried in turn.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniFab(
                            symbol = "↔",
                            contentDescription = stringResource(R.string.cd_fit_width),
                            enabled = hasActiveLayer,
                            onClick = onFitWidth,
                        )
                        MiniFab(
                            symbol = "1:1",
                            contentDescription = stringResource(R.string.cd_original_size),
                            enabled = hasActiveLayer,
                            onClick = onOriginalSize,
                        )
                        MiniFab(
                            symbol = "⛶",
                            contentDescription = stringResource(R.string.cd_center_crop),
                            enabled = hasActiveLayer,
                            onClick = onCenterCrop,
                        )
                    }
                    // Fix / release the layers. The symbol shows the current state
                    // (📌 fixed, ✋ free to move). Not a padlock: 🔒 already means lock screen.
                    MiniFab(
                        symbol = if (positionLocked) "📌" else "✋",
                        contentDescription = stringResource(
                            if (positionLocked) R.string.cd_unlock_position else R.string.cd_lock_position,
                        ),
                        enabled = true,
                        onClick = { expanded = false; onTogglePositionLock() },
                    )
                    MiniFab(
                        symbol = "➕",
                        contentDescription = stringResource(R.string.cd_add_layer),
                        enabled = !isBusy,
                        onClick = { expanded = false; onAddLayer() },
                    )
                }
            }

            val mainFabDesc = stringResource(R.string.cd_editor_fab)
            FloatingActionButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { contentDescription = mainFabDesc },
            ) {
                Text(if (expanded) "✕" else "🛠", fontSize = 24.sp)
            }
        }
    }
}

@Composable
private fun MiniFab(
    symbol: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // SmallFloatingActionButton has no `enabled`: dim it and expose the state to accessibility.
    SmallFloatingActionButton(
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.38f)
            .semantics {
                this.contentDescription = contentDescription
                if (!enabled) disabled()
            },
    ) {
        Text(symbol, fontSize = if (symbol.length > 2) 14.sp else 20.sp)
    }
}

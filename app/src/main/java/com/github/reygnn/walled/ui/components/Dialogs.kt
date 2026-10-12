package com.github.reygnn.walled.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.reygnn.walled.R
import com.github.reygnn.walled.model.MemoryReport
import com.github.reygnn.walled.model.ProjectCodec
import com.github.reygnn.walled.model.formatMegabytes
import java.util.Locale

/** Per-layer decoded size, source size and retained memory (launcher's memory dialog). */
@Composable
fun MemoryDialog(report: MemoryReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                report.rows.forEach { row ->
                    val source = if (row.isDownsampled) {
                        stringResource(R.string.memory_row_source, row.originalWidth, row.originalHeight, row.sampleSize)
                    } else {
                        ""
                    }
                    Text(
                        stringResource(
                            R.string.memory_row,
                            row.position,
                            row.decodedWidth,
                            row.decodedHeight,
                            formatMegabytes(row.bytes),
                            row.config,
                        ) + source,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    stringResource(R.string.memory_total, formatMegabytes(report.totalBytes)),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

@Composable
fun ConfirmRemoveAllDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_all_title)) },
        text = { Text(stringResource(R.string.remove_all_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.remove_all_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** The project files of the folder; picking one opens it. */
@Composable
fun OpenProjectDialog(projects: List<String>, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.open_project_title)) },
        text = {
            if (projects.isEmpty()) {
                Text(stringResource(R.string.open_project_empty))
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    projects.forEach { name ->
                        TextButton(onClick = { onOpen(name) }, modifier = Modifier.fillMaxWidth()) {
                            Text(name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** Asks for the project name; `.json` is added when missing. */
@Composable
fun SaveProjectDialog(initialName: String, exists: (String) -> Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    val fileName = ProjectCodec.projectFileName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_project_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.save_project_label)) },
                )
                if (fileName != null && exists(fileName)) {
                    Text(
                        stringResource(R.string.save_project_overwrite, fileName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { fileName?.let(onSave) }, enabled = fileName != null) {
                Text(stringResource(R.string.save_project_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** Opening a project replaces the current layers. */
@Composable
fun ConfirmReplaceDialog(projectName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.replace_title, projectName)) },
        text = { Text(stringResource(R.string.replace_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.replace_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * Typed input for the preview factor (from wallpan). A German decimal comma is
 * accepted; the ViewModel clamps the value. No slider on purpose: the factor is
 * a per-device constant typed once.
 */
@Composable
fun PreviewZoomDialog(current: Float, onConfirm: (Float) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(String.format(Locale.US, "%.2f", current)) }
    val parsed = text.trim().replace(',', '.').toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preview_zoom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.preview_zoom_message))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.preview_zoom_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) ?: onDismiss() }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

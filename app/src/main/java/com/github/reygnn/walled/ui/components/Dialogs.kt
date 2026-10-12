package com.github.reygnn.walled.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.reygnn.walled.R
import com.github.reygnn.walled.model.MemoryReport
import com.github.reygnn.walled.model.formatMegabytes

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

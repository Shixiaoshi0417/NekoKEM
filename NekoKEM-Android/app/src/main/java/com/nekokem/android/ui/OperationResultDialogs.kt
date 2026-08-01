package com.nekokem.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekokem.android.R
import com.nekokem.android.progress.OperationProgressSnapshot

@Composable
fun OperationCompletedDialog(
    @StringRes operationResource: Int,
    fileName: String,
    progress: OperationProgressSnapshot,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.completion_title)) },
        text = {
            Column {
                ResultLine(
                    label = stringResource(R.string.completion_operation),
                    value = stringResource(operationResource),
                )
                ResultLine(
                    label = stringResource(R.string.completion_file),
                    value = fileName,
                )
                ResultLine(
                    label = stringResource(R.string.completion_elapsed),
                    value = formatDuration(context, progress.elapsedMillis),
                )
                ResultLine(
                    label = stringResource(R.string.completion_average_speed),
                    value = stringResource(
                        R.string.speed_per_second,
                        formatByteCount(
                            context,
                            averageBytesPerSecond(progress).toLong(),
                        ),
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
fun OperationErrorDialog(
    @StringRes operationResource: Int,
    reason: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.error_dialog_title)) },
        text = {
            Column {
                ResultLine(
                    label = stringResource(R.string.completion_operation),
                    value = stringResource(operationResource),
                )
                ResultLine(
                    label = stringResource(R.string.error_dialog_reason),
                    value = reason,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun ResultLine(label: String, value: String) {
    Text(
        modifier = Modifier.padding(vertical = 4.dp),
        text = stringResource(R.string.result_line, label, value),
    )
}

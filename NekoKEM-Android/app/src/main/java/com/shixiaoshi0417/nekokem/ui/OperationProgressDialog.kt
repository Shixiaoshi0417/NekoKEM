package com.shixiaoshi0417.nekokem.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.nekokem.R
import com.shixiaoshi0417.nekokem.progress.OperationProgressSnapshot
import java.util.Locale

@Composable
fun OperationProgressDialog(
    @StringRes operationResource: Int,
    progress: OperationProgressSnapshot,
    cancelRequested: Boolean,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val operation = stringResource(operationResource)
    val processed = formatByteCount(context, progress.processedBytes)
    val total = formatByteCount(context, progress.totalBytes)
    val speed = formatByteCount(context, progress.bytesPerSecond.toLong())

    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.progress_title, operation)) },
        text = {
            Column {
                if (progress.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    modifier = Modifier.padding(top = 12.dp),
                    text = stringResource(
                        R.string.progress_percent,
                        (progress.fraction * 100f).toInt(),
                    ),
                )
                Text(stringResource(R.string.progress_bytes, processed, total))
                Text(stringResource(R.string.progress_speed, speed))
                Text(
                    stringResource(
                        R.string.progress_eta,
                        progress.etaMillis?.let {
                            formatDuration(context, it)
                        } ?: stringResource(R.string.progress_estimating),
                    ),
                )
                if (cancelRequested) {
                    Text(
                        modifier = Modifier.padding(top = 8.dp),
                        text = stringResource(R.string.progress_cancel_requested),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !cancelRequested,
                onClick = onCancel,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

fun averageBytesPerSecond(progress: OperationProgressSnapshot): Double =
    if (progress.elapsedMillis > 0L) {
        progress.processedBytes.toDouble() * 1000.0 /
            progress.elapsedMillis.toDouble()
    } else {
        0.0
    }

fun formatByteCount(context: Context, byteCount: Long): String {
    val bytes = byteCount.coerceAtLeast(0L).toDouble()
    return when {
        bytes >= GIBIBYTE -> context.getString(
            R.string.size_gib,
            formatDecimal(bytes / GIBIBYTE),
        )

        bytes >= MEBIBYTE -> context.getString(
            R.string.size_mib,
            formatDecimal(bytes / MEBIBYTE),
        )

        bytes >= KIBIBYTE -> context.getString(
            R.string.size_kib,
            formatDecimal(bytes / KIBIBYTE),
        )

        else -> context.getString(R.string.size_bytes, byteCount.coerceAtLeast(0L))
    }
}

fun formatDuration(context: Context, millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1000L
    return if (seconds < SECONDS_PER_MINUTE) {
        context.getString(R.string.duration_seconds, seconds)
    } else {
        context.getString(
            R.string.duration_minutes_seconds,
            seconds / SECONDS_PER_MINUTE,
            seconds % SECONDS_PER_MINUTE,
        )
    }
}

private fun formatDecimal(value: Double): String =
    String.format(Locale.getDefault(), "%.1f", value)

private const val KIBIBYTE = 1024.0
private const val MEBIBYTE = KIBIBYTE * 1024.0
private const val GIBIBYTE = MEBIBYTE * 1024.0
private const val SECONDS_PER_MINUTE = 60L

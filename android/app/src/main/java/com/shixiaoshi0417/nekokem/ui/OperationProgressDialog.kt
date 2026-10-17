package com.shixiaoshi0417.nekokem.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.nekokem.R
import com.shixiaoshi0417.nekokem.progress.OperationProgressSnapshot
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
        icon = { LoadingIndicator() },
        title = { Text(stringResource(R.string.progress_title, operation)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (progress.totalBytes > 0L) {
                    LinearWavyProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
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
            DialogButton(
                text = stringResource(R.string.action_cancel),
                enabled = !cancelRequested,
                onClick = onCancel,
            )
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
            formatDecimal(context, bytes / GIBIBYTE),
        )

        bytes >= MEBIBYTE -> context.getString(
            R.string.size_mib,
            formatDecimal(context, bytes / MEBIBYTE),
        )

        bytes >= KIBIBYTE -> context.getString(
            R.string.size_kib,
            formatDecimal(context, bytes / KIBIBYTE),
        )

        else -> context.getString(R.string.size_bytes, byteCount.coerceAtLeast(0L))
    }
}

fun formatDuration(context: Context, millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1000L
    return if (seconds < SECONDS_PER_MINUTE) {
        context.resources.getQuantityString(
            R.plurals.duration_seconds, seconds.toInt(), seconds,
        )
    } else {
        context.getString(
            R.string.duration_minutes_seconds,
            seconds / SECONDS_PER_MINUTE,
            seconds % SECONDS_PER_MINUTE,
        )
    }
}

private fun formatDecimal(context: Context, value: Double): String =
    NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(value)

private const val KIBIBYTE = 1024.0
private const val MEBIBYTE = KIBIBYTE * 1024.0
private const val GIBIBYTE = MEBIBYTE * 1024.0
private const val SECONDS_PER_MINUTE = 60L

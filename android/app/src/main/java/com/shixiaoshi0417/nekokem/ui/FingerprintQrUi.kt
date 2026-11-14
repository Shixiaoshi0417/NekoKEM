package com.shixiaoshi0417.nekokem.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import android.content.Intent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.PlanarYUVLuminanceSource
import com.shixiaoshi0417.nekokem.R
import com.shixiaoshi0417.nekokem.keys.PublicKeyContact
import com.shixiaoshi0417.nekokem.verify.FingerprintQr
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * This device's fingerprint as a QR code, for a contact to scan. The code is
 * drawn black on white in every theme so that cameras read it reliably.
 */
@Composable
fun FingerprintQrDialog(fingerprint: String, onDismiss: () -> Unit) {
    val matrix = remember(fingerprint) { FingerprintQr.encode(fingerprint) }
    val description = stringResource(R.string.fingerprint_qr_description)
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fingerprint_qr_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .widthIn(max = 280.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(16.dp)
                        .semantics { contentDescription = description },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val cell = size.width / matrix.width
                        for (y in 0 until matrix.height) {
                            for (x in 0 until matrix.width) {
                                if (matrix[x, y]) {
                                    // Overlap by a fraction of a pixel so no seams show between modules.
                                    drawRect(
                                        Color.Black,
                                        topLeft = Offset(x * cell, y * cell),
                                        size = Size(cell + 0.5f, cell + 0.5f),
                                    )
                                }
                            }
                        }
                    }
                }
                Text(fingerprint, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.fingerprint_qr_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.action_close), onClick = onDismiss) },
    )
}

/**
 * A contact's fingerprint laid out like Signal's safety number page: a
 * narrow coloured card with the share button in its corner, the code on white
 * (tap it to scan the owner's device), the fingerprint in four columns, and
 * one button that marks the contact verified or clears it. The share button
 * sends the fingerprint text.
 */
@Composable
fun SafetyNumberCard(
    fingerprint: String,
    verified: Boolean,
    enabled: Boolean,
    container: Color,
    content: Color,
    onScan: () -> Unit,
    onToggleVerified: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val matrix = remember(fingerprint) { FingerprintQr.encode(fingerprint) }
    val shareLabel = stringResource(R.string.action_share_fingerprint)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(container)
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Share,
            contentDescription = shareLabel,
            tint = content,
            modifier = Modifier.align(Alignment.End).clip(CircleShape).clickable {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, fingerprint)
                context.startActivity(Intent.createChooser(send, shareLabel))
            }.padding(8.dp).size(24.dp),
        )
        Column(
            Modifier.widthIn(max = 176.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
                .clickable(enabled = enabled, onClick = onScan).padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                val cell = size.width / matrix.width
                for (y in 0 until matrix.height) {
                    for (x in 0 until matrix.width) {
                        if (matrix[x, y]) drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
            Text(stringResource(R.string.action_scan_fingerprint), color = Color(0xFF3C3C43), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
        }
        FingerprintGrid(fingerprint, Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 40.dp, bottom = 32.dp), color = content)
        Box(
            Modifier.clip(CircleShape).background(Color.White).clickable(enabled = enabled, onClick = onToggleVerified)
                .padding(horizontal = 28.dp, vertical = 16.dp),
        ) {
            Text(
                stringResource(if (verified) R.string.action_unmark_verified else R.string.action_mark_verified),
                color = Color(0xFF1C1C1E),
                fontSize = 16.sp,
            )
        }
    }
}

/** What scanning a contact's code found. */
sealed interface FingerprintScanOutcome {
    data object Match : FingerprintScanOutcome
    data class Mismatch(val scanned: String) : FingerprintScanOutcome
    data object NotNekoKEM : FingerprintScanOutcome
}

fun fingerprintScanOutcome(text: String, contact: PublicKeyContact): FingerprintScanOutcome {
    val scanned = FingerprintQr.parse(text) ?: return FingerprintScanOutcome.NotNekoKEM
    return if (FingerprintQr.matches(scanned, contact.fingerprint)) {
        FingerprintScanOutcome.Match
    } else {
        FingerprintScanOutcome.Mismatch(scanned)
    }
}

/**
 * A full-screen camera view that reads one QR code and returns its text.
 * The camera permission is asked for only here, when the user starts a scan;
 * frames are analysed in memory and never stored.
 */
@Composable
fun FingerprintScanner(contact: PublicKeyContact, onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        denied = !result
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    if (denied || unavailable) {
        AppAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.fingerprint_scan_title)) },
            text = {
                Text(
                    stringResource(
                        if (denied) R.string.fingerprint_scan_permission_denied else R.string.fingerprint_scan_camera_unavailable,
                    ),
                )
            },
            confirmButton = { DialogButton(stringResource(R.string.action_close), onClick = onDismiss) },
        )
        return
    }
    if (!granted) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraPreview(onResult, onUnavailable = { unavailable = true })
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(240.dp)
                    .border(3.dp, Color.White, RoundedCornerShape(24.dp)),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding()
                    .background(Color.Black.copy(alpha = 0.6f)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.fingerprint_scan_hint, contact.label),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                DialogButton(stringResource(R.string.action_cancel), onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val previewView = remember { PreviewView(context) }
    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val done = AtomicBoolean(false)
        // Set on the main thread before the listener below can run there again.
        var disposed = false
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { useCase ->
                useCase.setAnalyzer(executor) { image ->
                    val text = image.use { decodeLuminance(it) }
                    if (text != null && done.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(context).execute { currentOnResult(text) }
                    }
                }
            }
        providerFuture.addListener({
            // A scanner closed while CameraX was starting never opens the camera.
            if (disposed) return@addListener
            // No usable camera, or CameraX failed to start: report it instead of crashing.
            val bound = runCatching {
                val ready = providerFuture.get()
                provider = ready
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                ready.unbindAll()
                ready.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
            if (bound.isFailure) currentOnUnavailable()
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            done.set(true)
            analysis.clearAnalyzer()
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** The luminance (Y) plane is enough for QR codes; colour is never read. */
private fun decodeLuminance(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val width = image.width
    val height = image.height
    if (plane.pixelStride != 1 || rowStride < width) return null
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    if (bytes.size < rowStride * (height - 1) + width) return null
    val source = PlanarYUVLuminanceSource(bytes, rowStride, height, 0, 0, width, height, false)
    return FingerprintQr.decode(source)
}

/** The result of a scan, with the verification it recorded. */
@Composable
fun FingerprintScanResultDialog(
    contact: PublicKeyContact,
    outcome: FingerprintScanOutcome,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val danger = if (isMiuixStyle()) MiuixTheme.colorScheme.error else MaterialTheme.colorScheme.error
    val good = if (isMiuixStyle()) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (outcome) {
                        FingerprintScanOutcome.Match -> R.string.fingerprint_scan_match_title
                        is FingerprintScanOutcome.Mismatch -> R.string.fingerprint_scan_mismatch_title
                        FingerprintScanOutcome.NotNekoKEM -> R.string.fingerprint_scan_invalid_title
                    },
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (outcome) {
                    FingerprintScanOutcome.Match -> {
                        Text(stringResource(R.string.fingerprint_scan_match, contact.label), color = good)
                        Text(contact.fingerprint, style = MaterialTheme.typography.bodySmall)
                    }
                    is FingerprintScanOutcome.Mismatch -> {
                        Text(stringResource(R.string.fingerprint_scan_mismatch, contact.label), color = danger)
                        Text(stringResource(R.string.fingerprint_scan_saved_line, contact.fingerprint), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.fingerprint_scan_scanned_line, outcome.scanned), style = MaterialTheme.typography.bodySmall)
                    }
                    FingerprintScanOutcome.NotNekoKEM -> Text(stringResource(R.string.fingerprint_scan_invalid))
                }
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.action_close), onClick = onDismiss) },
        dismissButton = if (outcome == FingerprintScanOutcome.Match) null else {
            { DialogButton(stringResource(R.string.action_scan_again), onClick = onRetry) }
        },
    )
}

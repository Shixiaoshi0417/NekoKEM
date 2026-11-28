package com.shixiaoshi0417.nekokem.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** The interface style of the surrounding screen; dialogs follow it. */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.MATERIAL }

/** Whether the screen is drawn in the MIUIX style. */
@Composable
internal fun isMiuixStyle(): Boolean = LocalUiStyle.current == UiStyle.MIUIX

/** Set by [AppAlertDialog] on the button slots it lays out in the MIUIX style. */
internal class MiuixDialogSlot(val primary: Boolean)

internal val LocalMiuixDialogSlot = compositionLocalOf<MiuixDialogSlot?> { null }

/**
 * An alert dialog drawn in the current interface style. The Material style
 * shows a Material 3 [AlertDialog]; the MIUIX style shows a MIUIX
 * [WindowDialog], bottom-attached on phones and centered on large screens,
 * with a centered title and its actions side by side at equal width.
 *
 * The parameters match [AlertDialog]. In the MIUIX style [properties] keeps
 * its secure policy: a dialog with [SecureFlagPolicy.SecureOn] opens with
 * FLAG_SECURE, as the Material dialog does.
 */
@Composable
internal fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    if (!isMiuixStyle()) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            properties = properties,
        )
        return
    }
    val dismissible = properties.dismissOnBackPress || properties.dismissOnClickOutside
    // WindowDialog's window inherits the secure flag from the screen and resets
    // it on every update, so the screen itself is secured first, before the
    // dialog window is created.
    if (properties.securePolicy == SecureFlagPolicy.SecureOn) SecureHostWindow()
    // Where the system blurs behind windows (Android 12 and newer, when the
    // device allows it), the dialog turns to frosted glass over the page.
    val blur = rememberCrossWindowBlur()
    val background = DialogDefaults.backgroundColor()
    WindowDialog(
        show = true,
        modifier = modifier,
        backgroundColor = if (blur) background.copy(alpha = 0.8f) else background,
        onDismissRequest = if (dismissible) onDismissRequest else null,
    ) {
        if (blur) BlurBehindDialog()
        val titleColor = DialogDefaults.titleColor()
        val summaryColor = DialogDefaults.summaryColor()
        Column(Modifier.fillMaxWidth()) {
            icon?.let {
                Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) { it() }
            }
            title?.let {
                CompositionLocalProvider(
                    LocalContentColor provides titleColor,
                    LocalTextStyle provides TextStyle(
                        fontSize = MiuixTheme.textStyles.title4.fontSize,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                ) {
                    Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) { it() }
                }
            }
            text?.let {
                CompositionLocalProvider(
                    LocalContentColor provides summaryColor,
                    LocalTextStyle provides TextStyle(fontSize = MiuixTheme.textStyles.body1.fontSize),
                ) {
                    // Weighted without filling: a body taller than the window
                    // scrolls in what is left, and the actions keep their height.
                    Box(Modifier.fillMaxWidth().weight(1f, fill = false).padding(bottom = 12.dp)) { it() }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                dismissButton?.let {
                    Box(Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalMiuixDialogSlot provides MiuixDialogSlot(primary = false), content = it)
                    }
                }
                Box(Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalMiuixDialogSlot provides MiuixDialogSlot(primary = true), content = confirmButton)
                }
            }
        }
    }
}

/** Whether windows can blur what lies behind them on this device now. */
@Composable
private fun rememberCrossWindowBlur(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val context = LocalContext.current
    return remember(context) { context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true }
}

/** Blurs the screen behind the dialog window that hosts this composable. */
@Composable
private fun BlurBehindDialog() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    val radius = with(LocalDensity.current) { 24.dp.roundToPx() }
    DisposableEffect(window, radius) {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = radius }
        onDispose {}
    }
}

/**
 * Sets FLAG_SECURE on the screen's window while composed, and clears it again
 * when it set it: a WindowDialog with the default inherit policy then opens
 * secure, and the screen behind the password stays out of screenshots too.
 */
@Composable
private fun SecureHostWindow() {
    val window = LocalView.current.context.findActivity()?.window
    DisposableEffect(window) {
        val secure = WindowManager.LayoutParams.FLAG_SECURE
        val added = window != null && window.attributes.flags and secure == 0
        if (added) window?.addFlags(secure)
        onDispose { if (added) window?.clearFlags(secure) }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

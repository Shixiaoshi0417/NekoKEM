package com.shixiaoshi0417.nekokem.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.nekokem.R
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Signing policy of this device. Files are signed (NKEM v5) unless the user
 * turned on unsigned encryption; [requireSignature] makes Core refuse
 * unsigned files on decryption.
 */
data class SignatureSettings(
    val unsignedEncryption: Boolean = false,
    val requireSignature: Boolean = false,
)

/** Stores [SignatureSettings] in this device's private preferences. */
object SignatureSettingsStore {
    private const val PREFERENCES = "signature_settings"
    private const val UNSIGNED = "unsigned_encryption"
    private const val REQUIRE = "require_signature"

    fun read(context: Context): SignatureSettings = try {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        SignatureSettings(
            unsignedEncryption = preferences.getBoolean(UNSIGNED, false),
            requireSignature = preferences.getBoolean(REQUIRE, false),
        )
    } catch (_: ClassCastException) {
        // A damaged value never turns signing off.
        SignatureSettings()
    }

    /** Returns false when the settings could not be written; the old ones then stay. */
    fun write(context: Context, settings: SignatureSettings): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(UNSIGNED, settings.unsignedEncryption)
            .putBoolean(REQUIRE, settings.requireSignature)
            .commit()
}

/** The theme's error color: Material colorScheme.error, or MIUIX red. */
@Composable
internal fun signatureWarningColor(): Color =
    if (isMiuixStyle()) MiuixTheme.colorScheme.error else MaterialTheme.colorScheme.error

/** The red line shown wherever unsigned encryption is on. */
@Composable
internal fun UnsignedWarningText(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.unsigned_warning),
        modifier = modifier,
        color = signatureWarningColor(),
        fontWeight = FontWeight.Medium,
    )
}

/**
 * Confirms turning signing off. The warning and the confirm action are red,
 * and Cancel holds the focus, so a hurried Enter keeps signing on.
 */
@Composable
internal fun UnsignedEncryptionWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            cancelFocus.requestFocus()
        } catch (_: IllegalStateException) {
            // The dialog window is not attached yet; Cancel is still the safe default.
        }
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unsigned_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                UnsignedWarningText()
                Text(
                    stringResource(R.string.unsigned_confirm_message),
                    color = signatureWarningColor(),
                )
            }
        },
        confirmButton = {
            DialogButton(
                text = stringResource(R.string.action_turn_off_signing),
                onClick = onConfirm,
                destructive = true,
            )
        },
        dismissButton = {
            DialogButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocus),
            )
        },
    )
}

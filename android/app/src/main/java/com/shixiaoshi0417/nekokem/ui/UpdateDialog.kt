package com.shixiaoshi0417.nekokem.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.nekokem.R
import com.shixiaoshi0417.nekokem.update.ReleaseInfo
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Tells the user a newer release exists. Nothing is downloaded here: the
 * release page opens in the browser, where the APK and its checksums are.
 */
@Composable
internal fun UpdateAvailableDialog(
    release: ReleaseInfo,
    currentVersion: String,
    onOpen: () -> Unit,
    onIgnore: () -> Unit,
    onLater: () -> Unit,
) {
    val linkColor = if (isMiuixStyle()) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    AppAlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.update_available_title, release.version)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.update_available_message, currentVersion))
                Text(
                    text = stringResource(R.string.action_ignore_version),
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onIgnore)
                        .padding(vertical = 4.dp),
                    color = linkColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        confirmButton = {
            DialogButton(text = stringResource(R.string.action_open_release), onClick = onOpen)
        },
        dismissButton = {
            DialogButton(text = stringResource(R.string.action_later), onClick = onLater)
        },
    )
}

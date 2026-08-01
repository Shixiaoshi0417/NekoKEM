package com.shixiaoshi0417.nekokem.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.nekokem.R
import kotlinx.coroutines.launch

data class NekoKEMUiState(
    val nativeConnected: Boolean,
    val nativeVersion: String?,
    val privateKeyExists: Boolean,
    val fingerprint: String?,
    val selectedFileName: String?,
    val publicKeyTemporary: Boolean,
    val publicKeyFileName: String,
    val publicKeyFingerprint: String?,
    val privateKeyTemporary: Boolean,
    val privateKeyFileName: String,
    val privateKeyFingerprint: String?,
    val running: Boolean,
)

data class NekoKEMActions(
    val onGenerate: () -> Unit,
    val onCheckPassword: () -> Unit,
    val onExportPublicKey: () -> Unit,
    val onExportPrivateKey: () -> Unit,
    val onImportPublicKey: () -> Unit,
    val onImportPrivateKey: () -> Unit,
    val onDeletePublicKey: () -> Unit,
    val onDeletePrivateKey: () -> Unit,
    val onDeleteKeypair: () -> Unit,
    val onSelectFile: () -> Unit,
    val onClearSelectedFile: () -> Unit,
    val onSelectTemporaryPublicKey: () -> Unit,
    val onRestoreDefaultPublicKey: () -> Unit,
    val onSelectTemporaryPrivateKey: () -> Unit,
    val onRestoreDefaultPrivateKey: () -> Unit,
    val onEncrypt: () -> Unit,
    val onDecrypt: () -> Unit,
)

private enum class AppDestination(@StringRes val titleResource: Int) {
    FILES(R.string.navigation_files),
    KEYS(R.string.navigation_keys),
    SETTINGS(R.string.navigation_settings),
    ABOUT(R.string.navigation_about),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NekoKEMAppShell(
    state: NekoKEMUiState,
    actions: NekoKEMActions,
    snackbarHostState: SnackbarHostState,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(AppDestination.FILES) }
    val menuDescription = stringResource(R.string.navigation_open_menu)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !state.running,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    modifier = Modifier.padding(24.dp),
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Column(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .selectableGroup(),
                ) {
                    AppDestination.entries.forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(stringResource(item.titleResource)) },
                            selected = destination == item,
                            onClick = {
                                destination = item
                                scope.launch { drawerState.close() }
                            },
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(destination.titleResource)) },
                    actions = {
                        IconButton(
                            modifier = Modifier.semantics {
                                contentDescription = menuDescription
                            },
                            enabled = !state.running,
                            onClick = { scope.launch { drawerState.open() } },
                        ) {
                            MenuGlyph()
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (destination) {
                    AppDestination.FILES -> FileOperationsPage(state, actions)
                    AppDestination.KEYS -> KeyManagementPage(state, actions)
                    AppDestination.SETTINGS -> SettingsPage()
                    AppDestination.ABOUT -> AboutPage(state)
                }
            }
        }
    }
}

@Composable
private fun FileOperationsPage(
    state: NekoKEMUiState,
    actions: NekoKEMActions,
) {
    val selectedName = state.selectedFileName
        ?: stringResource(R.string.selected_file_none)

    PageColumn {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.selected_file_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    text = selectedName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.selectedFileName != null) {
                    TextButton(
                        modifier = Modifier.align(Alignment.End),
                        enabled = !state.running,
                        onClick = actions.onClearSelectedFile,
                    ) {
                        Text(stringResource(R.string.action_clear_selection))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        ActionButton(
            labelResource = R.string.action_select_file,
            enabled = !state.running,
            onClick = actions.onSelectFile,
        )
        ActionButton(
            labelResource = R.string.action_encrypt,
            enabled = state.nativeConnected && !state.running &&
                state.selectedFileName != null,
            onClick = actions.onEncrypt,
        )
        ActionButton(
            labelResource = R.string.action_decrypt,
            enabled = state.nativeConnected && !state.running &&
                state.selectedFileName != null,
            onClick = actions.onDecrypt,
        )
        Spacer(modifier = Modifier.height(12.dp))
        KeySelectionCard(
            titleResource = R.string.encryption_public_key_title,
            temporary = state.publicKeyTemporary,
            fileName = state.publicKeyFileName,
            fingerprint = state.publicKeyFingerprint,
        )
        ActionButton(
            labelResource = R.string.action_select_other_public_key,
            enabled = state.nativeConnected && !state.running,
            onClick = actions.onSelectTemporaryPublicKey,
        )
        if (state.publicKeyTemporary) {
            ActionButton(
                labelResource = R.string.action_restore_default_public_key,
                enabled = !state.running,
                onClick = actions.onRestoreDefaultPublicKey,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        KeySelectionCard(
            titleResource = R.string.decryption_private_key_title,
            temporary = state.privateKeyTemporary,
            fileName = state.privateKeyFileName,
            fingerprint = state.privateKeyFingerprint,
        )
        ActionButton(
            labelResource = R.string.action_select_other_private_key,
            enabled = state.nativeConnected && !state.running,
            onClick = actions.onSelectTemporaryPrivateKey,
        )
        if (state.privateKeyTemporary) {
            ActionButton(
                labelResource = R.string.action_restore_default_private_key,
                enabled = !state.running,
                onClick = actions.onRestoreDefaultPrivateKey,
            )
        }
    }
}

@Composable
private fun KeySelectionCard(
    @StringRes titleResource: Int,
    temporary: Boolean,
    fileName: String,
    fingerprint: String?,
) {
    val unavailable = stringResource(R.string.not_available)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(titleResource),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                modifier = Modifier.padding(top = 12.dp),
                text = stringResource(
                    R.string.key_source_line,
                    stringResource(
                        if (temporary) {
                            R.string.key_source_temporary_saf
                        } else {
                            R.string.key_source_app_default
                        },
                    ),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.padding(top = 8.dp),
                text = stringResource(R.string.key_file_line, fileName),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.padding(top = 8.dp),
                text = stringResource(
                    R.string.key_fingerprint_line,
                    fingerprint ?: unavailable,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun KeyManagementPage(
    state: NekoKEMUiState,
    actions: NekoKEMActions,
) {
    val unavailable = stringResource(R.string.not_available)

    PageColumn {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(
                        if (state.privateKeyExists) {
                            R.string.private_key_exists
                        } else {
                            R.string.private_key_not_found
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                Text(
                    text = stringResource(R.string.fingerprint_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    text = state.fingerprint ?: unavailable,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        ActionButton(
            labelResource = R.string.action_generate_keypair,
            enabled = state.nativeConnected && !state.running,
            onClick = actions.onGenerate,
        )
        ActionButton(
            labelResource = R.string.action_check_password,
            enabled = state.nativeConnected && !state.running &&
                state.privateKeyExists,
            onClick = actions.onCheckPassword,
        )
        ActionButton(
            labelResource = R.string.action_import_public_key,
            enabled = state.nativeConnected && !state.running,
            onClick = actions.onImportPublicKey,
        )
        ActionButton(
            labelResource = R.string.action_export_public_key,
            enabled = state.nativeConnected && !state.running &&
                state.fingerprint != null,
            onClick = actions.onExportPublicKey,
        )
        ActionButton(
            labelResource = R.string.action_import_private_key,
            enabled = state.nativeConnected && !state.running,
            onClick = actions.onImportPrivateKey,
        )
        ActionButton(
            labelResource = R.string.action_export_private_key,
            enabled = state.nativeConnected && !state.running &&
                state.privateKeyExists,
            onClick = actions.onExportPrivateKey,
        )
        ActionButton(
            labelResource = R.string.action_delete_public_key,
            enabled = state.nativeConnected && !state.running &&
                state.fingerprint != null,
            onClick = actions.onDeletePublicKey,
        )
        ActionButton(
            labelResource = R.string.action_delete_private_key,
            enabled = state.nativeConnected && !state.running &&
                state.privateKeyExists,
            onClick = actions.onDeletePrivateKey,
        )
        ActionButton(
            labelResource = R.string.action_delete_keypair,
            enabled = state.nativeConnected && !state.running &&
                (state.fingerprint != null || state.privateKeyExists),
            onClick = actions.onDeleteKeypair,
        )
    }
}

@Composable
private fun SettingsPage() {
    PageColumn {
        Text(
            text = stringResource(R.string.settings_appearance_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        SettingRow(
            titleResource = R.string.settings_theme_title,
            valueResource = R.string.settings_theme_system,
        )
        SettingRow(
            titleResource = R.string.settings_dynamic_color_title,
            valueResource = R.string.settings_dynamic_color_system,
        )
        SettingRow(
            titleResource = R.string.settings_language_title,
            valueResource = R.string.settings_language_system,
        )
        SettingRow(
            titleResource = R.string.settings_version_title,
            valueResource = R.string.app_version,
        )
    }
}

@Composable
private fun AboutPage(state: NekoKEMUiState) {
    val unavailable = stringResource(R.string.not_available)

    PageColumn {
        Text(
            text = stringResource(R.string.app_version),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = stringResource(R.string.about_description),
            style = MaterialTheme.typography.bodyLarge,
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(
                        if (state.nativeConnected) {
                            R.string.native_core_connected
                        } else {
                            R.string.native_core_unavailable
                        },
                    ),
                )
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    text = stringResource(
                        R.string.openssl_version,
                        state.nativeVersion ?: unavailable,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
        content = content,
    )
}

@Composable
private fun SettingRow(
    @StringRes titleResource: Int,
    @StringRes valueResource: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = stringResource(titleResource),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            modifier = Modifier.padding(start = 16.dp),
            text = stringResource(valueResource),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionButton(
    @StringRes labelResource: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        enabled = enabled,
        onClick = onClick,
    ) {
        Text(stringResource(labelResource))
    }
}

@Composable
private fun MenuGlyph() {
    Column(
        modifier = Modifier
            .size(24.dp)
            .padding(vertical = 5.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        repeat(3) {
            HorizontalDivider(
                thickness = 2.dp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

package com.shixiaoshi0417.nekokem.ui

import android.app.Activity
import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import com.shixiaoshi0417.nekokem.i18n.AppLanguages
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
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
import com.shixiaoshi0417.nekokem.keys.PublicKeyContact
import com.shixiaoshi0417.nekokem.keys.PublicKeyContactsState
import kotlinx.coroutines.CancellationException
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
    val contactsState: PublicKeyContactsState = PublicKeyContactsState(),
    val publicKeyContactId: String? = null,
    val publicKeyNote: String? = null,
    // Saved-contact recipients still listed, in order, and how many were chosen.
    val publicKeyRecipients: List<PublicKeyContact> = emptyList(),
    val publicKeyRecipientCount: Int = 0,
    // Checkbox selection on the contacts page, not yet applied to encryption.
    val contactSelection: List<String> = emptyList(),
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
    val onImportContact: () -> Unit,
    val onChooseContact: () -> Unit,
    val onSelectContact: (PublicKeyContact) -> Unit,
    val onEditContact: (PublicKeyContact) -> Unit,
    val onDeleteContact: (PublicKeyContact) -> Unit,
    val onToggleContactSelection: (PublicKeyContact) -> Unit = {},
    val onClearContactSelection: () -> Unit = {},
    val onEncryptForSelectedContacts: () -> Unit = {},
)

private enum class AppDestination(@StringRes val titleResource: Int) {
    FILES(R.string.navigation_files),
    KEYS(R.string.navigation_keys),
    CONTACTS(R.string.navigation_contacts),
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
    var destination by rememberSaveable { mutableStateOf(AppDestination.FILES) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val menuDescription = stringResource(R.string.navigation_open_menu)
    // Predictive back: a secondary page follows the system back gesture and
    // reveals Files beneath it. Files itself leaves back to the system, which
    // shows its back-to-home animation; the open drawer handles its own gesture.
    val backProgress = remember { Animatable(0f) }
    var backEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val previewingFiles by remember { derivedStateOf { backProgress.value > 0f } }
    val drawerClosed = drawerState.currentValue == DrawerValue.Closed &&
        drawerState.targetValue == DrawerValue.Closed
    PredictiveBackHandler(
        enabled = destination != AppDestination.FILES && drawerClosed && !state.running,
    ) { events ->
        try {
            events.collect { event ->
                backEdge = event.swipeEdge
                backProgress.snapTo(event.progress)
            }
            destination = AppDestination.FILES
            backProgress.snapTo(0f)
        } catch (error: CancellationException) {
            // A cancelled gesture returns the page to its resting position.
            scope.launch { backProgress.animateTo(0f) }
            throw error
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !state.running,
        drawerContent = {
            // Passing the state enables Material's predictive back for the drawer.
            ModalDrawerSheet(drawerState = drawerState) {
                Text(
                    modifier = Modifier.padding(24.dp),
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Column(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
                ) {
                    AppDestination.entries.forEach { item ->
                        NavigationDrawerItem(
                            modifier = Modifier.heightIn(min = (56f * fontScale).dp),
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
                    expandedHeight = (64f * fontScale).dp,
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
                if (previewingFiles && destination != AppDestination.FILES) {
                    // Visual preview only; the page being left keeps the semantics.
                    Box(modifier = Modifier.fillMaxSize().clearAndSetSemantics {}) {
                        FileOperationsPage(state, actions)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Material predictive back: shrink to 90%, shift away from the
                            // swipe edge and round the corners as the finger moves.
                            val progress = backProgress.value
                            val scale = 1f - 0.1f * progress
                            scaleX = scale
                            scaleY = scale
                            val direction = if (backEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                            translationX = direction * progress *
                                (size.width / 20f - 8.dp.toPx()).coerceAtLeast(0f)
                            shape = RoundedCornerShape(28.dp * progress)
                            clip = progress > 0f
                            shadowElevation = 6.dp.toPx() * progress
                        }
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    when (destination) {
                        AppDestination.FILES -> FileOperationsPage(state, actions)
                        AppDestination.KEYS -> KeyManagementPage(state, actions)
                        AppDestination.CONTACTS -> PublicKeyContactsPage(
                            state,
                            actions,
                            onUse = { contact ->
                                actions.onSelectContact(contact)
                                destination = AppDestination.FILES
                            },
                            onEncryptSelected = {
                                actions.onEncryptForSelectedContacts()
                                destination = AppDestination.FILES
                            },
                        )
                        AppDestination.SETTINGS -> SettingsPage(state.running)
                        AppDestination.ABOUT -> AboutPage(state)
                    }
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
        if (state.publicKeyRecipientCount > 1) {
            RecipientsCard(state.publicKeyRecipients, state.publicKeyRecipientCount)
        } else {
            KeySelectionCard(
                titleResource = R.string.encryption_public_key_title,
                temporary = state.publicKeyTemporary,
                fileName = state.publicKeyFileName,
                fingerprint = state.publicKeyFingerprint,
                sourceResource = if (state.publicKeyContactId != null) R.string.key_source_contacts else null,
                note = state.publicKeyNote,
            )
        }
        if (state.publicKeyRecipientCount > 0 &&
            state.publicKeyRecipients.size < state.publicKeyRecipientCount
        ) {
            Text(stringResource(R.string.contact_unavailable), color = MaterialTheme.colorScheme.error)
        }
        ActionButton(
            labelResource = R.string.action_choose_contact,
            enabled = state.nativeConnected && !state.running,
            onClick = actions.onChooseContact,
        )
        ActionButton(
            labelResource = R.string.action_select_other_public_key,
            enabled = state.nativeConnected && !state.running,
            onClick = actions.onSelectTemporaryPublicKey,
        )
        if (state.publicKeyTemporary || state.publicKeyRecipientCount > 0) {
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

/** Several saved contacts sharing one NKEM v4 file; each is listed with its fingerprint. */
@Composable
private fun RecipientsCard(recipients: List<PublicKeyContact>, count: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.encryption_public_key_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    R.string.key_source_line,
                    stringResource(R.string.key_source_contacts_multiple, count),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            recipients.forEach { contact ->
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Text(contact.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.key_fingerprint_line, contact.fingerprint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                text = stringResource(R.string.contact_recipients_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
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
    @StringRes sourceResource: Int? = null,
    note: String? = null,
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
                        sourceResource ?: if (temporary) {
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
            if (!note.isNullOrBlank()) {
                Text(stringResource(R.string.contact_note_line, note), modifier = Modifier.padding(top = 8.dp))
            }
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
private fun SettingsPage(running: Boolean) {
    val context = LocalContext.current
    var showLanguages by remember { mutableStateOf(false) }
    var languageSaveFailed by remember { mutableStateOf(false) }
    val selection = AppLanguages.selection(context)
    if (languageSaveFailed) {
        AlertDialog(
            onDismissRequest = { languageSaveFailed = false },
            title = { Text(stringResource(R.string.error_dialog_title)) },
            text = { Text(stringResource(R.string.error_language_save)) },
            confirmButton = {
                TextButton(onClick = { languageSaveFailed = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
    if (showLanguages) {
        AlertDialog(
            onDismissRequest = { showLanguages = false },
            title = { Text(stringResource(R.string.settings_language_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    AppLanguages.tags.forEachIndexed { index, tag ->
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = selection == tag,
                                role = Role.RadioButton,
                                onClick = {
                                    showLanguages = false
                                    if (AppLanguages.setSelection(context, tag)) {
                                        if (Build.VERSION.SDK_INT < 33) (context as Activity).recreate()
                                    } else languageSaveFailed = true
                                },
                            ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selection == tag, onClick = null)
                            Text(
                                if (tag.isEmpty()) stringResource(R.string.settings_language_system)
                                else AppLanguages.names(context)[index],
                                modifier = Modifier.padding(start = 12.dp).weight(1f),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguages = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
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
        TextButton(
            enabled = !running,
            onClick = { showLanguages = true },
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_language_title))
                Text(
                    if (selection.isEmpty()) stringResource(R.string.settings_language_system)
                    else AppLanguages.names(context)[AppLanguages.tags.indexOf(selection)],
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
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
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(titleResource),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            modifier = Modifier.padding(top = 4.dp),
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

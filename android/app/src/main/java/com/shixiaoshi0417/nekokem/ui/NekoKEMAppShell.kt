package com.shixiaoshi0417.nekokem.ui

import android.app.Activity
import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
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
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NekoKEMAppShell(
    state: NekoKEMUiState,
    actions: NekoKEMActions,
    snackbarHostState: SnackbarHostState,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var destination by rememberSaveable { mutableStateOf(AppDestination.FILES) }
    var animateNavigation by remember { mutableStateOf(true) }
    val motion = MaterialTheme.motionScheme
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
            // The gesture already showed Files; arrive without a second transition.
            animateNavigation = false
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
                    style = MaterialTheme.typography.headlineSmallEmphasized,
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
                                animateNavigation = true
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
                    title = {
                        Text(
                            text = stringResource(destination.titleResource),
                            style = MaterialTheme.typography.titleLargeEmphasized,
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier.semantics {
                                contentDescription = menuDescription
                            },
                            enabled = !state.running,
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
                    // A newly opened page fades in with the expressive motion scheme. The
                    // page being left goes at once, so its semantics never linger.
                    key(destination) {
                        val pageAlpha = remember { Animatable(if (animateNavigation) 0f else 1f) }
                        LaunchedEffect(Unit) { pageAlpha.animateTo(1f, motion.defaultEffectsSpec()) }
                        Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = pageAlpha.value }) {
                            when (destination) {
                                AppDestination.FILES -> FileOperationsPage(state, actions)
                                AppDestination.KEYS -> KeyManagementPage(state, actions)
                                AppDestination.CONTACTS -> PublicKeyContactsPage(
                                    state,
                                    actions,
                                    onUse = { contact ->
                                        actions.onSelectContact(contact)
                                        animateNavigation = true
                                        destination = AppDestination.FILES
                                    },
                                    onEncryptSelected = {
                                        actions.onEncryptForSelectedContacts()
                                        animateNavigation = true
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
    }
}

@Composable
private fun FileOperationsPage(
    state: NekoKEMUiState,
    actions: NekoKEMActions,
) {
    val selectedName = state.selectedFileName
        ?: stringResource(R.string.selected_file_none)
    val canRun = state.nativeConnected && !state.running

    PageColumn {
        HeroCard {
            Text(
                text = stringResource(R.string.selected_file_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                modifier = Modifier.padding(top = 8.dp),
                text = selectedName,
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExpressiveButton(
                    text = stringResource(R.string.action_select_file),
                    onClick = actions.onSelectFile,
                    enabled = !state.running,
                )
                if (state.selectedFileName != null) {
                    ExpressiveButton(
                        text = stringResource(R.string.action_clear_selection),
                        onClick = actions.onClearSelectedFile,
                        enabled = !state.running,
                        emphasis = ActionEmphasis.TEXT,
                    )
                }
            }
        }
        ActionButtonGroup(
            modifier = Modifier.padding(top = 16.dp),
            size = ActionSize.MEDIUM,
            actions = listOf(
                GroupAction(
                    text = stringResource(R.string.action_encrypt),
                    enabled = canRun && state.selectedFileName != null,
                    onClick = actions.onEncrypt,
                ),
                GroupAction(
                    text = stringResource(R.string.action_decrypt),
                    enabled = canRun && state.selectedFileName != null,
                    onClick = actions.onDecrypt,
                ),
            ),
        )
        Spacer(modifier = Modifier.height(24.dp))
        val publicKeyActions: @Composable ColumnScope.() -> Unit = {
            if (state.publicKeyRecipientCount > 0 &&
                state.publicKeyRecipients.size < state.publicKeyRecipientCount
            ) {
                Text(stringResource(R.string.contact_unavailable), color = MaterialTheme.colorScheme.error)
            }
            CardAction(
                labelResource = R.string.action_choose_contact,
                enabled = canRun,
                onClick = actions.onChooseContact,
            )
            CardAction(
                labelResource = R.string.action_select_other_public_key,
                enabled = canRun,
                onClick = actions.onSelectTemporaryPublicKey,
            )
            if (state.publicKeyTemporary || state.publicKeyRecipientCount > 0) {
                CardAction(
                    labelResource = R.string.action_restore_default_public_key,
                    enabled = !state.running,
                    onClick = actions.onRestoreDefaultPublicKey,
                    emphasis = ActionEmphasis.OUTLINED,
                )
            }
        }
        if (state.publicKeyRecipientCount > 1) {
            RecipientsCard(
                state.publicKeyRecipients,
                state.publicKeyRecipientCount,
                publicKeyActions,
            )
        } else {
            KeySelectionCard(
                titleResource = R.string.encryption_public_key_title,
                temporary = state.publicKeyTemporary,
                fileName = state.publicKeyFileName,
                fingerprint = state.publicKeyFingerprint,
                sourceResource = if (state.publicKeyContactId != null) R.string.key_source_contacts else null,
                note = state.publicKeyNote,
                actions = publicKeyActions,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        KeySelectionCard(
            titleResource = R.string.decryption_private_key_title,
            temporary = state.privateKeyTemporary,
            fileName = state.privateKeyFileName,
            fingerprint = state.privateKeyFingerprint,
        ) {
            CardAction(
                labelResource = R.string.action_select_other_private_key,
                enabled = canRun,
                onClick = actions.onSelectTemporaryPrivateKey,
            )
            if (state.privateKeyTemporary) {
                CardAction(
                    labelResource = R.string.action_restore_default_private_key,
                    enabled = !state.running,
                    onClick = actions.onRestoreDefaultPrivateKey,
                    emphasis = ActionEmphasis.OUTLINED,
                )
            }
        }
    }
}

/** Several saved contacts sharing one NKEM v4 file; each is listed with its fingerprint. */
@Composable
private fun RecipientsCard(
    recipients: List<PublicKeyContact>,
    count: Int,
    actions: @Composable ColumnScope.() -> Unit,
) {
    SectionCard {
        Text(
            text = stringResource(R.string.encryption_public_key_title),
            style = MaterialTheme.typography.titleMediumEmphasized,
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
        actions()
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
    actions: @Composable ColumnScope.() -> Unit,
) {
    val unavailable = stringResource(R.string.not_available)
    SectionCard {
        Text(
            text = stringResource(titleResource),
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        Text(
            modifier = Modifier.padding(top = 4.dp),
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
            text = stringResource(R.string.key_file_line, fileName),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!note.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.contact_note_line, note),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(
                R.string.key_fingerprint_line,
                fingerprint ?: unavailable,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        actions()
    }
}

@Composable
private fun KeyManagementPage(
    state: NekoKEMUiState,
    actions: NekoKEMActions,
) {
    val unavailable = stringResource(R.string.not_available)
    val canRun = state.nativeConnected && !state.running

    PageColumn {
        HeroCard(highlighted = state.privateKeyExists) {
            Text(
                text = stringResource(
                    if (state.privateKeyExists) {
                        R.string.private_key_exists
                    } else {
                        R.string.private_key_not_found
                    },
                ),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = LocalContentColor.current.copy(alpha = 0.24f),
            )
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
        Spacer(modifier = Modifier.height(16.dp))
        ActionButton(
            labelResource = R.string.action_generate_keypair,
            enabled = canRun,
            onClick = actions.onGenerate,
            size = ActionSize.MEDIUM,
        )
        ActionButton(
            labelResource = R.string.action_check_password,
            enabled = canRun && state.privateKeyExists,
            onClick = actions.onCheckPassword,
            emphasis = ActionEmphasis.TONAL,
        )
        Spacer(modifier = Modifier.height(12.dp))
        ActionButtonGroup(
            modifier = Modifier.padding(vertical = 4.dp),
            emphasis = ActionEmphasis.TONAL,
            actions = listOf(
                GroupAction(
                    text = stringResource(R.string.action_import_public_key),
                    enabled = canRun,
                    onClick = actions.onImportPublicKey,
                ),
                GroupAction(
                    text = stringResource(R.string.action_export_public_key),
                    enabled = canRun && state.fingerprint != null,
                    onClick = actions.onExportPublicKey,
                ),
            ),
        )
        ActionButtonGroup(
            modifier = Modifier.padding(vertical = 4.dp),
            emphasis = ActionEmphasis.TONAL,
            actions = listOf(
                GroupAction(
                    text = stringResource(R.string.action_import_private_key),
                    enabled = canRun,
                    onClick = actions.onImportPrivateKey,
                ),
                GroupAction(
                    text = stringResource(R.string.action_export_private_key),
                    enabled = canRun && state.privateKeyExists,
                    onClick = actions.onExportPrivateKey,
                ),
            ),
        )
        Spacer(modifier = Modifier.height(12.dp))
        ActionButtonGroup(
            modifier = Modifier.padding(vertical = 4.dp),
            emphasis = ActionEmphasis.DESTRUCTIVE,
            actions = listOf(
                GroupAction(
                    text = stringResource(R.string.action_delete_public_key),
                    enabled = canRun && state.fingerprint != null,
                    onClick = actions.onDeletePublicKey,
                ),
                GroupAction(
                    text = stringResource(R.string.action_delete_private_key),
                    enabled = canRun && state.privateKeyExists,
                    onClick = actions.onDeletePrivateKey,
                ),
            ),
        )
        ActionButton(
            labelResource = R.string.action_delete_keypair,
            enabled = canRun && (state.fingerprint != null || state.privateKeyExists),
            onClick = actions.onDeleteKeypair,
            emphasis = ActionEmphasis.DESTRUCTIVE,
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
                DialogButton(stringResource(R.string.action_close), onClick = { languageSaveFailed = false })
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
                DialogButton(stringResource(R.string.action_close), onClick = { showLanguages = false })
            },
        )
    }
    PageColumn {
        Text(
            text = stringResource(R.string.settings_appearance_title),
            style = MaterialTheme.typography.titleLargeEmphasized,
        )
        SectionCard(modifier = Modifier.padding(top = 16.dp)) {
            SettingRow(
                titleResource = R.string.settings_theme_title,
                valueResource = R.string.settings_theme_system,
            )
            SettingRow(
                titleResource = R.string.settings_dynamic_color_title,
                valueResource = R.string.settings_dynamic_color_system,
            )
            LanguageSetting(
                running = running,
                value = if (selection.isEmpty()) {
                    stringResource(R.string.settings_language_system)
                } else {
                    AppLanguages.names(context)[AppLanguages.tags.indexOf(selection)]
                },
                onClick = { showLanguages = true },
            )
            SettingRow(
                titleResource = R.string.settings_version_title,
                valueResource = R.string.app_version,
            )
        }
    }
}

/** Opens the language picker; its corners morph while pressed like the other buttons. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LanguageSetting(running: Boolean, value: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.settings_language_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AboutPage(state: NekoKEMUiState) {
    val unavailable = stringResource(R.string.not_available)

    PageColumn {
        // The App icon framed by an Expressive shape; decorative, so it has no description.
        // The icon stays near its 192 px bitmap size so it is not upscaled.
        val cookie = MaterialShapes.Cookie9Sided.toShape()
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, cookie),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(78.dp).clip(cookie),
            )
        }
        Text(
            modifier = Modifier.padding(top = 20.dp),
            text = stringResource(R.string.app_version),
            style = MaterialTheme.typography.headlineMediumEmphasized,
        )
        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = stringResource(R.string.about_description),
            style = MaterialTheme.typography.bodyLarge,
        )
        SectionCard(modifier = Modifier.padding(top = 24.dp)) {
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
                text = stringResource(
                    R.string.openssl_version,
                    state.nativeVersion ?: unavailable,
                ),
            )
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

/** The page's leading card, in the primary container color when [highlighted]. */
@Composable
private fun HeroCard(
    highlighted: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = if (highlighted) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        },
    ) {
        Column(modifier = Modifier.padding(24.dp), content = content)
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingRow(
    @StringRes titleResource: Int,
    @StringRes valueResource: Int,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
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
    emphasis: ActionEmphasis = ActionEmphasis.FILLED,
    size: ActionSize = ActionSize.SMALL,
) {
    ExpressiveButton(
        text = stringResource(labelResource),
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        enabled = enabled,
        emphasis = emphasis,
        size = size,
    )
}

/** A key card's own action, spanning the card. */
@Composable
private fun CardAction(
    @StringRes labelResource: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    emphasis: ActionEmphasis = ActionEmphasis.TONAL,
) {
    ExpressiveButton(
        text = stringResource(labelResource),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        emphasis = emphasis,
    )
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

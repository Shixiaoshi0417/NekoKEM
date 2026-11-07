package com.shixiaoshi0417.nekokem.ui

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.nekokem.R
import com.shixiaoshi0417.nekokem.i18n.AppLanguages
import com.shixiaoshi0417.nekokem.keys.PublicKeyContact
import com.shixiaoshi0417.nekokem.keys.PublicKeyContactsState
import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import com.shixiaoshi0417.nekokem.ui.miuix.liquid.LiquidGlassNavigationBar
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** miuix-blur's backdrop, blur and lens all need RuntimeShader. */
private val liquidGlassSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

private val AppDestination.icon: ImageVector
    get() = when (this) {
        AppDestination.FILES -> MiuixIcons.File
        AppDestination.KEYS -> MiuixIcons.Lock
        AppDestination.CONTACTS -> MiuixIcons.Contacts
        AppDestination.SETTINGS -> MiuixIcons.Settings
        AppDestination.ABOUT -> MiuixIcons.Info
    }

/**
 * The MIUIX interface: each page has a large collapsing title, and the pages
 * scroll beneath a floating liquid glass tab bar. The bar refracts and blurs
 * the page on Android 13 and newer, where miuix-blur's shaders run; older
 * versions never call miuix-blur and get a solid floating bar instead.
 */
@Composable
internal fun MiuixAppShell(
    state: NekoKEMUiState,
    actions: NekoKEMActions,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
) {
    var destination by rememberSaveable { mutableStateOf(AppDestination.FILES) }
    // Back from another tab returns to Files; Files leaves to the system.
    BackHandler(enabled = destination != AppDestination.FILES && !state.running) {
        destination = AppDestination.FILES
    }
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = if (liquidGlassSupported) {
        rememberLayerBackdrop {
            drawRect(surface)
            drawContent()
        }
    } else {
        null
    }
    val items = AppDestination.entries.map {
        NavigationItem(label = stringResource(it.tabResource), icon = it.icon)
    }
    Scaffold(
        bottomBar = {
            if (backdrop != null) {
                LiquidGlassNavigationBar(
                    items = items,
                    selectedIndex = destination.ordinal,
                    onItemClick = { destination = AppDestination.entries[it] },
                    backdrop = backdrop,
                    isBlurActive = true,
                )
            } else {
                FloatingNavigationBar {
                    items.forEachIndexed { index, item ->
                        FloatingNavigationBarItem(
                            selected = destination.ordinal == index,
                            onClick = { destination = AppDestination.entries[index] },
                            icon = item.icon,
                            label = item.label,
                            // The floating bar draws icons only; name each tab for accessibility.
                            modifier = Modifier.semantics { contentDescription = item.label },
                        )
                    }
                }
            }
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = if (backdrop != null) Modifier.fillMaxSize().layerBackdrop(backdrop) else Modifier.fillMaxSize()) {
            key(destination) {
                val title = stringResource(destination.titleResource)
                val bottom = padding.calculateBottomPadding()
                when (destination) {
                    AppDestination.FILES -> MiuixPage(title, bottom) { filesPage(state, actions) }
                    AppDestination.KEYS -> MiuixPage(title, bottom) { keysPage(state, actions) }
                    AppDestination.CONTACTS -> MiuixPage(title, bottom) {
                        contactsPage(
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
                    }
                    AppDestination.SETTINGS -> MiuixSettingsPage(title, bottom, state, actions)
                    AppDestination.ABOUT -> MiuixPage(title, bottom) { aboutPage(state) }
                }
            }
        }
    }
}

/** A page with a large title that collapses as its list scrolls. */
@Composable
private fun MiuixPage(
    title: String,
    bottomPadding: androidx.compose.ui.unit.Dp,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        TopAppBar(title = title, scrollBehavior = scrollBehavior)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
            overscrollEffect = null,
            content = content,
        )
    }
}

private fun LazyListScope.section(
    @StringRes titleResource: Int? = null,
    key: String,
    content: @Composable () -> Unit,
) {
    item(key = key) {
        Column {
            if (titleResource != null) SmallTitle(text = stringResource(titleResource))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp, top = if (titleResource == null) 12.dp else 0.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun Action(
    @StringRes titleResource: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    ArrowPreference(
        title = stringResource(titleResource),
        titleColor = if (destructive) {
            BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error)
        } else {
            BasicComponentDefaults.titleColor()
        },
        enabled = enabled,
        onClick = onClick,
    )
}

/** Two equal buttons side by side; the first is the primary action. */
@Composable
private fun ButtonPair(
    first: String,
    second: String,
    enabled: Boolean,
    onFirst: () -> Unit,
    onSecond: () -> Unit,
    secondEnabled: Boolean = enabled,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(
            text = first,
            onClick = onFirst,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
        TextButton(
            text = second,
            onClick = onSecond,
            enabled = secondEnabled,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun KeyDetails(
    temporary: Boolean,
    fileName: String,
    fingerprint: String?,
    @StringRes sourceResource: Int? = null,
    note: String? = null,
) {
    val source = stringResource(
        sourceResource ?: if (temporary) R.string.key_source_temporary_saf else R.string.key_source_app_default,
    )
    val lines = buildList {
        add(stringResource(R.string.key_source_line, source))
        add(stringResource(R.string.key_file_line, fileName))
        if (!note.isNullOrBlank()) add(stringResource(R.string.contact_note_line, note))
        add(stringResource(R.string.key_fingerprint_line, fingerprint ?: stringResource(R.string.not_available)))
    }
    BasicComponent(title = lines.first(), summary = lines.drop(1).joinToString("\n"))
}

private fun LazyListScope.filesPage(state: NekoKEMUiState, actions: NekoKEMActions) {
    val canRun = state.nativeConnected && !state.running
    section(key = "file") {
        BasicComponent(
            title = state.selectedFileName ?: stringResource(R.string.selected_file_none),
            summary = stringResource(R.string.selected_file_label),
        )
        ButtonPair(
            first = stringResource(R.string.action_encrypt),
            second = stringResource(R.string.action_decrypt),
            enabled = canRun && state.selectedFileName != null,
            onFirst = actions.onEncrypt,
            onSecond = actions.onDecrypt,
        )
        Action(R.string.action_select_file, !state.running, actions.onSelectFile)
        if (state.selectedFileName != null) {
            Action(R.string.action_clear_selection, !state.running, actions.onClearSelectedFile)
        }
    }
    section(R.string.encryption_public_key_title, key = "public") {
        if (state.publicKeyRecipientCount > 1) {
            BasicComponent(
                title = stringResource(
                    R.string.key_source_line,
                    stringResource(R.string.key_source_contacts_multiple, state.publicKeyRecipientCount),
                ),
                summary = stringResource(R.string.contact_recipients_hint),
            )
            state.publicKeyRecipients.forEach { contact ->
                BasicComponent(
                    title = contact.label,
                    summary = stringResource(R.string.key_fingerprint_line, contact.fingerprint),
                )
            }
        } else {
            KeyDetails(
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
            BasicComponent(
                title = stringResource(R.string.contact_unavailable),
                titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
            )
        }
        Action(R.string.action_choose_contact, canRun, actions.onChooseContact)
        Action(R.string.action_select_other_public_key, canRun, actions.onSelectTemporaryPublicKey)
        if (state.publicKeyTemporary || state.publicKeyRecipientCount > 0) {
            Action(R.string.action_restore_default_public_key, !state.running, actions.onRestoreDefaultPublicKey)
        }
    }
    section(R.string.decryption_private_key_title, key = "private") {
        KeyDetails(
            temporary = state.privateKeyTemporary,
            fileName = state.privateKeyFileName,
            fingerprint = state.privateKeyFingerprint,
        )
        Action(R.string.action_select_other_private_key, canRun, actions.onSelectTemporaryPrivateKey)
        if (state.privateKeyTemporary) {
            Action(R.string.action_restore_default_private_key, !state.running, actions.onRestoreDefaultPrivateKey)
        }
    }
}

private fun LazyListScope.keysPage(state: NekoKEMUiState, actions: NekoKEMActions) {
    val canRun = state.nativeConnected && !state.running
    section(key = "status") {
        BasicComponent(
            title = stringResource(
                if (state.privateKeyExists) R.string.private_key_exists else R.string.private_key_not_found,
            ),
            summary = stringResource(R.string.fingerprint_label) + "\n" +
                (state.fingerprint ?: stringResource(R.string.not_available)),
        )
        ButtonPair(
            first = stringResource(R.string.action_generate_keypair),
            second = stringResource(R.string.action_check_password),
            enabled = canRun,
            onFirst = actions.onGenerate,
            onSecond = actions.onCheckPassword,
            secondEnabled = canRun && state.privateKeyExists,
        )
    }
    section(R.string.miuix_keys_transfer_title, key = "transfer") {
        Action(R.string.action_import_public_key, canRun, actions.onImportPublicKey)
        Action(R.string.action_export_public_key, canRun && state.fingerprint != null, actions.onExportPublicKey)
        Action(R.string.action_import_private_key, canRun, actions.onImportPrivateKey)
        Action(R.string.action_export_private_key, canRun && state.privateKeyExists, actions.onExportPrivateKey)
    }
    section(R.string.miuix_keys_delete_title, key = "delete") {
        Action(R.string.action_delete_public_key, canRun && state.fingerprint != null, actions.onDeletePublicKey, true)
        Action(R.string.action_delete_private_key, canRun && state.privateKeyExists, actions.onDeletePrivateKey, true)
        Action(
            R.string.action_delete_keypair,
            canRun && (state.fingerprint != null || state.privateKeyExists),
            actions.onDeleteKeypair,
            true,
        )
    }
}

private fun LazyListScope.contactsPage(
    state: NekoKEMUiState,
    actions: NekoKEMActions,
    onUse: (PublicKeyContact) -> Unit,
    onEncryptSelected: () -> Unit,
) {
    val selected = state.contactSelection
    val atLimit = selected.size >= NativeBridge.MAX_RECIPIENTS
    section(key = "intro") {
        BasicComponent(summary = stringResource(R.string.contact_description))
        Action(R.string.action_import_contact, state.nativeConnected && !state.running, actions.onImportContact)
        ContactStatus(state.contactsState)
    }
    if (selected.isNotEmpty()) {
        section(key = "selection") {
            val summary = buildList {
                if (atLimit) add(stringResource(R.string.contact_selection_limit, NativeBridge.MAX_RECIPIENTS))
                add(stringResource(R.string.contact_recipients_hint))
            }.joinToString("\n")
            BasicComponent(
                title = stringResource(R.string.contact_selection_count, selected.size),
                summary = summary,
            )
            ButtonPair(
                first = stringResource(R.string.action_encrypt_for_selected),
                second = stringResource(R.string.action_clear_selection),
                enabled = state.nativeConnected && !state.running,
                onFirst = onEncryptSelected,
                onSecond = actions.onClearContactSelection,
                secondEnabled = !state.running,
            )
        }
    }
    items(state.contactsState.contacts, key = { it.id }) { contact ->
        val checked = contact.id in selected
        val canToggle = !state.running && (checked || !atLimit)
        val selectLabel = stringResource(R.string.contact_select_recipient)
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        enabled = canToggle,
                        role = Role.Checkbox,
                        onValueChange = { actions.onToggleContactSelection(contact) },
                    )
                    .semantics { contentDescription = "$selectLabel · ${contact.label}" }
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    state = ToggleableState(checked),
                    onClick = null,
                    enabled = canToggle,
                )
                Text(
                    text = selectLabel,
                    modifier = Modifier.padding(start = 12.dp),
                    style = MiuixTheme.textStyles.body2,
                )
            }
            BasicComponent(
                title = contact.label,
                summary = stringResource(R.string.key_file_line, contact.displayName) + "\n" +
                    stringResource(R.string.key_fingerprint_line, contact.fingerprint),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.action_use_contact),
                    onClick = { onUse(contact) },
                    enabled = state.nativeConnected && !state.running,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = stringResource(R.string.action_edit_contact_note),
                    onClick = { actions.onEditContact(contact) },
                    enabled = !state.running,
                )
                TextButton(
                    text = stringResource(R.string.action_delete),
                    onClick = { actions.onDeleteContact(contact) },
                    enabled = !state.running,
                    colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error),
                )
            }
        }
    }
}

@Composable
private fun ContactStatus(state: PublicKeyContactsState) {
    val error = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error)
    when {
        state.storageError -> BasicComponent(title = stringResource(R.string.contact_load_failed), titleColor = error)
        state.unreadableEntries > 0 -> BasicComponent(
            title = stringResource(R.string.contact_unreadable_entries, state.unreadableEntries),
            titleColor = error,
        )
        state.contacts.isEmpty() -> BasicComponent(summary = stringResource(R.string.contact_empty))
    }
}

@Composable
private fun MiuixSettingsPage(
    title: String,
    bottomPadding: androidx.compose.ui.unit.Dp,
    state: NekoKEMUiState,
    actions: NekoKEMActions,
) {
    val context = LocalContext.current
    var languageSaveFailed by remember { mutableStateOf(false) }
    if (languageSaveFailed) {
        AlertDialog(
            onDismissRequest = { languageSaveFailed = false },
            title = { androidx.compose.material3.Text(stringResource(R.string.error_dialog_title)) },
            text = { androidx.compose.material3.Text(stringResource(R.string.error_language_save)) },
            confirmButton = {
                DialogButton(stringResource(R.string.action_close), onClick = { languageSaveFailed = false })
            },
        )
    }
    val selection = AppLanguages.selection(context)
    val languageNames = AppLanguages.tags.mapIndexed { index, tag ->
        if (tag.isEmpty()) stringResource(R.string.settings_language_system) else AppLanguages.names(context)[index]
    }
    val styleNames = UiStyle.entries.map { stringResource(it.labelResource) }
    val unsupported = stringResource(R.string.settings_liquid_glass_unsupported)
    MiuixPage(title, bottomPadding) {
        section(R.string.settings_appearance_title, key = "appearance") {
            OverlayDropdownPreference(
                title = stringResource(R.string.settings_ui_style_title),
                summary = if (liquidGlassSupported) null else unsupported,
                items = styleNames,
                selectedIndex = state.uiStyle.ordinal,
                enabled = !state.running,
                onSelectedIndexChange = { actions.onUiStyleChange(UiStyle.entries[it]) },
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.settings_language_title),
                items = languageNames,
                selectedIndex = AppLanguages.tags.indexOf(selection).coerceAtLeast(0),
                enabled = !state.running,
                onSelectedIndexChange = { index ->
                    val tag = AppLanguages.tags[index]
                    if (tag != selection) {
                        if (AppLanguages.setSelection(context, tag)) {
                            if (Build.VERSION.SDK_INT < 33) (context as Activity).recreate()
                        } else {
                            languageSaveFailed = true
                        }
                    }
                },
            )
            BasicComponent(
                title = stringResource(R.string.settings_theme_title),
                summary = stringResource(R.string.settings_theme_system),
            )
            // MIUIX keeps its own palette; wallpaper color is a Material setting.
        }
        section(key = "version") {
            BasicComponent(
                title = stringResource(R.string.settings_version_title),
                summary = stringResource(R.string.app_version),
            )
        }
    }
}

private fun LazyListScope.aboutPage(state: NekoKEMUiState) {
    item(key = "header") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Decorative; the version below names the App.
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_version),
                style = MiuixTheme.textStyles.title2,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    section(key = "description") {
        BasicComponent(summary = stringResource(R.string.about_description))
    }
    section(key = "native") {
        BasicComponent(
            title = stringResource(
                if (state.nativeConnected) R.string.native_core_connected else R.string.native_core_unavailable,
            ),
            summary = stringResource(
                R.string.openssl_version,
                state.nativeVersion ?: stringResource(R.string.not_available),
            ),
        )
    }
}

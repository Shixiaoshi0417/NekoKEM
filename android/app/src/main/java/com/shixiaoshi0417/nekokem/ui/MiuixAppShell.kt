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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
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
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.NotesFill
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** miuix-blur's backdrop, blur and lens all need RuntimeShader. */
private val liquidGlassSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

// Solid icons, so the floating bar shows as much white as possible. MIUIX's
// icon set is outlined apart from a few, so the rest come from Material.
private val AppDestination.icon: ImageVector
    get() = when (this) {
        AppDestination.FILES -> MiuixIcons.NotesFill
        AppDestination.KEYS -> Icons.Filled.Lock
        AppDestination.CONTACTS -> Icons.Filled.Person
        AppDestination.SETTINGS -> Icons.Filled.Settings
        AppDestination.ABOUT -> Icons.Filled.Info
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
    // About goes back to Settings, where it is opened from.
    BackHandler(enabled = destination != AppDestination.FILES && !state.running) {
        destination = if (destination == AppDestination.ABOUT) AppDestination.SETTINGS else AppDestination.FILES
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
    val items = BarDestinations.map {
        NavigationItem(label = stringResource(it.tabResource), icon = it.icon)
    }
    Scaffold(
        bottomBar = {
            if (backdrop != null) {
                LiquidGlassNavigationBar(
                    items = items,
                    selectedIndex = BarDestinations.indexOf(destination.barTab),
                    onItemClick = { destination = BarDestinations[it] },
                    backdrop = backdrop,
                    isBlurActive = true,
                )
            } else {
                FloatingNavigationBar {
                    items.forEachIndexed { index, item ->
                        FloatingNavigationBarItem(
                            selected = destination.barTab == BarDestinations[index],
                            onClick = { destination = BarDestinations[index] },
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
                    AppDestination.CONTACTS -> {
                        // A small card per contact; tapping it opens the contact's own page.
                        var openId by rememberSaveable { mutableStateOf<String?>(null) }
                        var showArchived by rememberSaveable { mutableStateOf(false) }
                        val open = openId?.let { id -> state.contactsState.contacts.firstOrNull { it.id == id } }
                        BackHandler(enabled = open != null) { openId = null }
                        val onUse = { contact: PublicKeyContact ->
                            actions.onSelectContact(contact) { destination = AppDestination.FILES }
                        }
                        key(open?.id) {
                            if (open != null) {
                                MiuixPage(open.label, bottom, onBack = { openId = null }) {
                                    contactDetailPage(open, state, actions, onUse)
                                }
                            } else {
                                Box(Modifier.fillMaxSize()) {
                                    MiuixPage(title, bottom) {
                                        contactsPage(
                                            state,
                                            actions,
                                            onOpen = { openId = it.id },
                                            onEncryptSelected = {
                                                actions.onEncryptForSelectedContacts { destination = AppDestination.FILES }
                                            },
                                            showArchived = showArchived,
                                            onToggleArchived = { showArchived = !showArchived },
                                        )
                                    }
                                    // With contacts listed, adding another moves to a floating button.
                                    if (state.contactsState.contacts.isNotEmpty()) {
                                        val canImport = state.nativeConnected && !state.running
                                        val addLabel = stringResource(R.string.action_import_contact)
                                        FloatingActionButton(
                                            onClick = { if (canImport) actions.onImportContact() },
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(end = 20.dp, bottom = bottom + 20.dp)
                                                .alpha(if (canImport) 1f else 0.38f)
                                                .semantics { contentDescription = addLabel },
                                        ) {
                                            Icon(MiuixIcons.Add, contentDescription = null, tint = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    AppDestination.SETTINGS -> MiuixSettingsPage(title, bottom, state, actions) {
                        destination = AppDestination.ABOUT
                    }
                    AppDestination.ABOUT -> MiuixPage(
                        title,
                        bottom,
                        onBack = { destination = AppDestination.SETTINGS },
                        backDescription = stringResource(R.string.tab_settings),
                    ) { aboutPage(state, actions) }
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
    onBack: (() -> Unit)? = null,
    backDescription: String? = null,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        TopAppBar(
            title = title,
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.padding(start = 16.dp)) {
                        Icon(MiuixIcons.Back, contentDescription = backDescription ?: stringResource(R.string.action_back_to_contacts))
                    }
                }
            },
        )
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
        if (state.signatureSettings.unsignedEncryption) {
            BasicComponent(
                title = stringResource(R.string.unsigned_warning),
                titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
            )
        }
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
            summary = buildList {
                add(stringResource(R.string.fingerprint_label))
                add(state.fingerprint ?: stringResource(R.string.not_available))
                if (state.fingerprint != null) {
                    add(stringResource(if (state.canSign) R.string.key_identity_status else R.string.key_legacy_status))
                }
                state.legacyFingerprint?.let { legacy ->
                    add(stringResource(R.string.legacy_fingerprint_label))
                    add(legacy)
                }
            }.joinToString("\n"),
        )
        ButtonPair(
            first = stringResource(R.string.action_generate_keypair),
            second = stringResource(R.string.action_check_password),
            enabled = canRun,
            onFirst = actions.onGenerate,
            onSecond = actions.onCheckPassword,
            secondEnabled = canRun && state.privateKeyExists,
        )
        if (state.fingerprint != null && state.privateKeyExists && !state.canSign) {
            Action(R.string.action_upgrade_key, canRun, actions.onUpgradeKey)
        }
        if (state.fingerprint != null) {
            Action(R.string.action_show_fingerprint_qr, !state.running, actions.onShowFingerprintQr)
        }
    }
    section(R.string.miuix_keys_transfer_title, key = "transfer") {
        Action(R.string.action_import_public_key, canRun, actions.onImportPublicKey)
        Action(R.string.action_export_public_key, canRun && state.fingerprint != null, actions.onExportPublicKey)
        if (state.canSign) {
            ArrowPreference(
                title = stringResource(R.string.action_export_legacy_public_key),
                summary = stringResource(R.string.legacy_export_hint),
                enabled = canRun,
                onClick = actions.onExportLegacyPublicKey,
            )
        }
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
    onOpen: (PublicKeyContact) -> Unit,
    onEncryptSelected: () -> Unit,
    showArchived: Boolean,
    onToggleArchived: () -> Unit,
) {
    val selected = state.contactSelection
    val atLimit = selected.size >= NativeBridge.MAX_RECIPIENTS
    section(key = "intro") {
        BasicComponent(summary = stringResource(R.string.contact_description))
        if (state.contactsState.contacts.isEmpty()) {
            Action(R.string.action_import_contact, state.nativeConnected && !state.running, actions.onImportContact)
        }
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
    val active = state.contactsState.contacts.filterNot { it.archived }
    val archived = state.contactsState.contacts.filter { it.archived }
    items(active, key = { it.id }) { contact -> MiuixContactCard(contact, state, actions, atLimit) { onOpen(contact) } }
    if (archived.isNotEmpty()) {
        item(key = "archived") {
            ArrowPreference(
                title = stringResource(R.string.contact_archived_section, archived.size),
                onClick = onToggleArchived,
            )
        }
        if (showArchived) {
            items(archived, key = { it.id }) { contact -> MiuixContactCard(contact, state, actions, atLimit) { onOpen(contact) } }
        }
    }
    if (state.contactsState.contacts.isNotEmpty()) item(key = "fab-space") { Spacer(Modifier.height(80.dp)) }
}

/** A small contact card: selection, name, short fingerprint and the verification mark. */
@Composable
private fun MiuixContactCard(
    contact: PublicKeyContact,
    state: NekoKEMUiState,
    actions: NekoKEMActions,
    atLimit: Boolean,
    onOpen: () -> Unit,
) {
    val checked = contact.id in state.contactSelection
    val canToggle = !state.running && (checked || !atLimit)
    val selectLabel = stringResource(R.string.contact_select_recipient)
    SwipeableContactCard(
        contact,
        enabled = !state.running,
        shape = RoundedCornerShape(16.dp),
        onPin = { actions.onSetContactPinned(contact, it) },
        onArchive = { actions.onSetContactArchived(contact, it) },
        onDelete = { actions.onDeleteContact(contact) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            pressFeedbackType = PressFeedbackType.Sink,
            onClick = onOpen,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    state = ToggleableState(checked),
                    onClick = { actions.onToggleContactSelection(contact) },
                    enabled = canToggle,
                    modifier = Modifier.semantics { contentDescription = "$selectLabel · ${contact.label}" },
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = contact.label,
                            style = MiuixTheme.textStyles.headline1,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (contact.pinned) {
                            Text(
                                text = stringResource(R.string.contact_pinned_label),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    Text(
                        text = shortFingerprint(contact.fingerprint),
                        style = MiuixTheme.textStyles.body2,
                        fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
                VerificationBadge(contact.verified)
            }
        }
    }
}

/** One contact: identity, Signal-style fingerprint verification, then its actions. */
private fun LazyListScope.contactDetailPage(
    contact: PublicKeyContact,
    state: NekoKEMUiState,
    actions: NekoKEMActions,
    onUse: (PublicKeyContact) -> Unit,
) {
    val checked = contact.id in state.contactSelection
    val canToggle = !state.running && (checked || state.contactSelection.size < NativeBridge.MAX_RECIPIENTS)
    section(key = "identity") {
        BasicComponent(
            title = contact.label,
            summary = stringResource(R.string.key_file_line, contact.displayName) + "\n" +
                stringResource(if (contact.canVerify) R.string.contact_verifies else R.string.contact_legacy),
            endActions = { VerificationBadge(contact.verified, size = 40.dp, described = false) },
        )
        BasicComponent(
            title = stringResource(if (contact.verified) R.string.contact_verified else R.string.contact_unverified),
            titleColor = BasicComponentDefaults.titleColor(
                color = if (contact.verified) VerifiedGreen else MiuixTheme.colorScheme.error,
            ),
        )
    }
    section(titleResource = R.string.contact_verify_title, key = "verify") {
        FingerprintGrid(
            contact.fingerprint,
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
            color = MiuixTheme.colorScheme.onSurface,
        )
        BasicComponent(summary = stringResource(R.string.contact_verify_hint, contact.label))
        Action(R.string.action_scan_fingerprint, !state.running, { actions.onScanContact(contact) })
        if (state.fingerprint != null) {
            Action(R.string.action_show_fingerprint_qr, true, actions.onShowFingerprintQr)
        }
        Action(
            if (contact.verified) R.string.action_unmark_verified else R.string.action_mark_verified,
            !state.running,
            { actions.onSetContactVerified(contact, !contact.verified) },
        )
    }
    section(key = "use") {
        val selectLabel = stringResource(R.string.contact_select_recipient)
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
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(state = ToggleableState(checked), onClick = null, enabled = canToggle)
            Text(text = selectLabel, modifier = Modifier.padding(start = 12.dp), style = MiuixTheme.textStyles.body1)
        }
        Action(R.string.action_use_contact, state.nativeConnected && !state.running, { onUse(contact) })
        Action(R.string.action_edit_contact_note, !state.running, { actions.onEditContact(contact) })
        Action(R.string.action_delete, !state.running, { actions.onDeleteContact(contact) }, destructive = true)
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
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    var languageSaveFailed by remember { mutableStateOf(false) }
    if (languageSaveFailed) {
        AppAlertDialog(
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
        section(R.string.settings_signing_title, key = "signing") {
            val unsigned = state.signatureSettings.unsignedEncryption
            val red = MiuixTheme.colorScheme.error
            SwitchPreference(
                checked = unsigned,
                onCheckedChange = actions.onUnsignedEncryptionChange,
                title = stringResource(R.string.settings_unsigned_title),
                titleColor = if (unsigned) BasicComponentDefaults.titleColor(color = red) else BasicComponentDefaults.titleColor(),
                summary = if (unsigned) {
                    stringResource(R.string.unsigned_warning)
                } else {
                    stringResource(R.string.settings_unsigned_summary)
                },
                summaryColor = if (unsigned) BasicComponentDefaults.summaryColor(color = red) else BasicComponentDefaults.summaryColor(),
                enabled = !state.running,
            )
            SwitchPreference(
                checked = state.signatureSettings.requireSignature,
                onCheckedChange = actions.onRequireSignatureChange,
                title = stringResource(R.string.settings_require_signature_title),
                summary = stringResource(R.string.settings_require_signature_summary),
                enabled = !state.running,
            )
        }
        section(R.string.settings_updates_title, key = "updates") {
            SwitchPreference(
                checked = state.autoUpdateCheck,
                onCheckedChange = actions.onAutoUpdateChange,
                title = stringResource(R.string.settings_auto_update_title),
                summary = stringResource(R.string.settings_auto_update_summary),
            )
        }
        section(key = "version") {
            BasicComponent(
                title = stringResource(R.string.settings_version_title),
                summary = stringResource(R.string.app_version),
            )
            ArrowPreference(
                title = stringResource(R.string.tab_about),
                enabled = !state.running,
                onClick = onOpenAbout,
            )
        }
    }
}

private fun LazyListScope.aboutPage(state: NekoKEMUiState, actions: NekoKEMActions) {
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
    section(key = "update") {
        ArrowPreference(
            title = stringResource(R.string.action_check_update),
            summary = if (state.updateChecking) stringResource(R.string.update_checking) else null,
            enabled = !state.updateChecking,
            onClick = actions.onCheckUpdate,
        )
    }
}

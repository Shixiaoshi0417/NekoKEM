package com.shixiaoshi0417.nekokem.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.shixiaoshi0417.nekokem.keys.TemporaryPublicKey
import androidx.compose.ui.state.ToggleableState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.nekokem.R
import com.shixiaoshi0417.nekokem.keys.PublicKeyContact
import com.shixiaoshi0417.nekokem.keys.PublicKeyContacts
import com.shixiaoshi0417.nekokem.keys.PublicKeyContactsState
import com.shixiaoshi0417.nekokem.nativecore.NativeBridge
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PublicKeyContactsPage(
    state: NekoKEMUiState,
    actions: NekoKEMActions,
    onUse: (PublicKeyContact) -> Unit,
    onEncryptSelected: () -> Unit,
) {
    // A small card per contact; tapping it opens the contact's own page.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openId?.let { id -> state.contactsState.contacts.firstOrNull { it.id == id } }
    // Fingerprint verification is its own page under the contact.
    var verifying by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = open != null) { openId = null }
    BackHandler(enabled = open != null && verifying) { verifying = false }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    if (open != null && verifying) {
        PublicKeyContactVerify(open, state, actions, onBack = { verifying = false })
        return
    }
    if (open != null) {
        PublicKeyContactDetail(open, state, actions, onUse, onVerify = { verifying = true }, onBack = { openId = null })
        return
    }
    verifying = false
    val selected = state.contactSelection
    val atLimit = selected.size >= NativeBridge.MAX_RECIPIENTS
    val canImport = state.nativeConnected && !state.running
    // With contacts listed, adding another moves to a floating button in the corner.
    val floatingAdd = state.contactsState.contacts.isNotEmpty()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = if (floatingAdd) 104.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.contact_description), style = MaterialTheme.typography.bodyMedium)
            }
            if (!floatingAdd) {
                item {
                    ExpressiveButton(
                        text = stringResource(R.string.action_import_contact),
                        onClick = actions.onImportContact,
                        enabled = canImport,
                        size = ActionSize.MEDIUM,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item { ContactStorageStatus(state.contactsState) }
            if (selected.isNotEmpty()) {
                item(key = "selection") {
                    // Several recipients share one NKEM v4 file; nothing changes until this is pressed.
                    Card(
                        modifier = Modifier.fillMaxWidth().animateItem(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.contact_selection_count, selected.size),
                                style = MaterialTheme.typography.titleLargeEmphasized,
                            )
                            if (atLimit) {
                                Text(
                                    stringResource(R.string.contact_selection_limit, NativeBridge.MAX_RECIPIENTS),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(stringResource(R.string.contact_recipients_hint), style = MaterialTheme.typography.bodySmall)
                            ExpressiveButton(
                                text = stringResource(R.string.action_encrypt_for_selected),
                                onClick = onEncryptSelected,
                                enabled = state.nativeConnected && !state.running,
                                size = ActionSize.MEDIUM,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                            ExpressiveButton(
                                text = stringResource(R.string.action_clear_selection),
                                onClick = actions.onClearContactSelection,
                                enabled = !state.running,
                                emphasis = ActionEmphasis.TEXT,
                            )
                        }
                    }
                }
            }
            val active = state.contactsState.contacts.filterNot { it.archived }
            val archived = state.contactsState.contacts.filter { it.archived }
            items(active, key = { it.id }) { contact ->
                MaterialContactCard(contact, state, actions, atLimit, Modifier.animateItem()) { openId = contact.id }
            }
            if (archived.isNotEmpty()) {
                item(key = "archived") {
                    Row(
                        Modifier.fillMaxWidth().animateItem()
                            .clickable { showArchived = !showArchived }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.contact_archived_section, archived.size),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (showArchived) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showArchived) {
                    items(archived, key = { it.id }) { contact ->
                        MaterialContactCard(contact, state, actions, atLimit, Modifier.animateItem()) { openId = contact.id }
                    }
                }
            }
        }
        if (floatingAdd) {
            val addLabel = stringResource(R.string.action_import_contact)
            FloatingActionButton(
                onClick = { if (canImport) actions.onImportContact() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .alpha(if (canImport) 1f else 0.38f)
                    .semantics { contentDescription = addLabel },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        }
    }
}

/** A small contact card: selection, name, short fingerprint and the verification mark. */
@Composable
private fun MaterialContactCard(
    contact: PublicKeyContact,
    state: NekoKEMUiState,
    actions: NekoKEMActions,
    atLimit: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
) {
    val checked = contact.id in state.contactSelection
    val canToggle = !state.running && (checked || !atLimit)
    val selectLabel = stringResource(R.string.contact_select_recipient)
    // A chosen recipient's card rounds further and gains an outline.
    val corner by animateDpAsState(
        targetValue = if (checked) 28.dp else 16.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "contactCorner",
    )
    val shape = RoundedCornerShape(corner)
    SwipeableContactCard(
        contact,
        enabled = !state.running,
        shape = shape,
        onPin = { actions.onSetContactPinned(contact, it) },
        onArchive = { actions.onSetContactArchived(contact, it) },
        onDelete = { actions.onDeleteContact(contact) },
        modifier = modifier.fillMaxWidth(),
    ) {
        Card(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            border = if (checked) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { actions.onToggleContactSelection(contact) },
                    enabled = canToggle,
                    modifier = Modifier.semantics { contentDescription = "$selectLabel · ${contact.label}" },
                )
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            contact.label,
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (contact.pinned) {
                            Text(
                                stringResource(R.string.contact_pinned_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    Text(
                        shortFingerprint(contact.fingerprint),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                VerifiedMark(contact.verified, MaterialTheme.colorScheme.onSurface, Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** The contact's fingerprint card on its own page. */
@Composable
private fun PublicKeyContactVerify(
    contact: PublicKeyContact,
    state: NekoKEMUiState,
    actions: NekoKEMActions,
    onBack: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ExpressiveButton(text = contact.label, onClick = onBack, emphasis = ActionEmphasis.TEXT) }
        item {
            SafetyNumberCard(
                fingerprint = contact.fingerprint,
                verified = contact.verified,
                enabled = !state.running,
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onScan = { actions.onScanContact(contact) },
                onToggleVerified = { actions.onSetContactVerified(contact, !contact.verified) },
            )
        }
    }
}

/** One contact: identity, then its actions; fingerprint verification opens from here. */
@Composable
private fun PublicKeyContactDetail(
    contact: PublicKeyContact,
    state: NekoKEMUiState,
    actions: NekoKEMActions,
    onUse: (PublicKeyContact) -> Unit,
    onVerify: () -> Unit,
    onBack: () -> Unit,
) {
    val checked = contact.id in state.contactSelection
    val canToggle = !state.running && (checked || state.contactSelection.size < NativeBridge.MAX_RECIPIENTS)
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            ExpressiveButton(
                text = stringResource(R.string.action_back_to_contacts),
                onClick = onBack,
                emphasis = ActionEmphasis.TEXT,
            )
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = cardColors) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(contact.label, style = MaterialTheme.typography.headlineSmallEmphasized)
                        Text(stringResource(R.string.key_file_line, contact.displayName), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(if (contact.canVerify) R.string.contact_verifies else R.string.contact_legacy),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (contact.canVerify) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    VerifiedMark(contact.verified, MaterialTheme.colorScheme.onSurface, size = 24.dp)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = cardColors) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val selectLabel = stringResource(R.string.contact_select_recipient)
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = checked,
                            enabled = canToggle,
                            role = Role.Checkbox,
                            onValueChange = { actions.onToggleContactSelection(contact) },
                        ).semantics { contentDescription = "$selectLabel · ${contact.label}" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = canToggle)
                        Text(selectLabel, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExpressiveButton(
                            text = stringResource(R.string.contact_verify_title),
                            onClick = onVerify,
                            emphasis = ActionEmphasis.OUTLINED,
                        )
                        ExpressiveButton(
                            text = stringResource(R.string.action_use_contact),
                            onClick = { onUse(contact) },
                            enabled = state.nativeConnected && !state.running,
                            emphasis = ActionEmphasis.TONAL,
                        )
                        ExpressiveButton(
                            text = stringResource(R.string.action_edit_contact_note),
                            onClick = { actions.onEditContact(contact) },
                            enabled = !state.running,
                            emphasis = ActionEmphasis.OUTLINED,
                        )
                        ExpressiveButton(
                            text = stringResource(R.string.action_delete),
                            onClick = { actions.onDeleteContact(contact) },
                            enabled = !state.running,
                            emphasis = ActionEmphasis.DESTRUCTIVE,
                        )
                    }
                }
            }
        }
    }
}

/** Red for warnings about contacts; fixed, since dark themes turn the error color pink. */
internal val UnverifiedRed = Color(0xFFD93025)

/**
 * Signal's mark for a verified contact: an outlined shield with a tick in the
 * surrounding text color. Unverified contacts carry no mark.
 */
@Composable
internal fun VerifiedMark(verified: Boolean, color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    if (!verified) return
    val description = stringResource(R.string.contact_verified)
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        val side = this.size.minDimension
        val stroke = Stroke(width = side * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val shield = Path().apply {
            moveTo(side * 0.5f, side * 0.07f)
            lineTo(side * 0.85f, side * 0.2f)
            lineTo(side * 0.85f, side * 0.46f)
            cubicTo(side * 0.85f, side * 0.7f, side * 0.69f, side * 0.86f, side * 0.5f, side * 0.94f)
            cubicTo(side * 0.31f, side * 0.86f, side * 0.15f, side * 0.7f, side * 0.15f, side * 0.46f)
            lineTo(side * 0.15f, side * 0.2f)
            close()
        }
        drawPath(shield, color, style = stroke)
        val tick = Path().apply {
            moveTo(side * 0.34f, side * 0.5f)
            lineTo(side * 0.46f, side * 0.62f)
            lineTo(side * 0.67f, side * 0.39f)
        }
        drawPath(tick, color, style = stroke)
    }
}

/** The fingerprint as four rows of four 4-digit groups, like Signal's safety number. */
@Composable
internal fun FingerprintGrid(fingerprint: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(
        modifier.clearAndSetSemantics { contentDescription = fingerprint },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Each row of four groups stays on one line; the text shrinks on narrow screens or large font scales.
        fingerprintRows(fingerprint).forEach { row ->
            BasicText(
                row,
                style = TextStyle(color = color, fontFamily = FontFamily.Monospace, fontSize = 18.sp, letterSpacing = 1.sp, textAlign = TextAlign.Center),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 18.sp, stepSize = 0.5.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun fingerprintRows(fingerprint: String): List<String> =
    fingerprint.replace(":", "").chunked(4).chunked(4).map { it.joinToString(" ") }

/** The grid's first two groups and last group, enough to tell contacts apart at a glance. */
internal fun shortFingerprint(fingerprint: String): String {
    val groups = fingerprint.replace(":", "").chunked(4)
    if (groups.size <= 3) return groups.joinToString(" ")
    return groups.take(2).joinToString(" ") + " … " + groups.last()
}

@Composable
private fun ContactStorageStatus(state: PublicKeyContactsState) {
    if (state.storageError) {
        Text(stringResource(R.string.contact_load_failed), color = MaterialTheme.colorScheme.error)
    } else if (state.unreadableEntries > 0) {
        Text(stringResource(R.string.contact_unreadable_entries, state.unreadableEntries), color = MaterialTheme.colorScheme.error)
    } else if (state.contacts.isEmpty()) {
        Text(stringResource(R.string.contact_empty), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ContactIdentity(contact: PublicKeyContact) {
    Text(contact.label, style = MaterialTheme.typography.titleMediumEmphasized)
    Text(stringResource(R.string.key_file_line, contact.displayName), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.key_fingerprint_line, contact.fingerprint), style = MaterialTheme.typography.bodySmall)
    Text(
        stringResource(if (contact.canVerify) R.string.contact_verifies else R.string.contact_legacy),
        style = MaterialTheme.typography.labelMedium,
        color = if (contact.canVerify) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun PublicKeyContactPicker(
    state: PublicKeyContactsState,
    selectedId: String?,
    onSelect: (PublicKeyContact) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_picker_title)) },
        text = {
            Column {
                ContactStorageStatus(state)
                LazyColumn(Modifier.heightIn(max = 360.dp).selectableGroup()) {
                    items(state.contacts.filterNot { it.archived }, key = { it.id }) { contact ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = selectedId == contact.id,
                                role = Role.RadioButton,
                                onClick = { onSelect(contact) },
                            ).padding(vertical = 12.dp),
                        ) {
                            if (isMiuixStyle()) {
                                top.yukonga.miuix.kmp.basic.RadioButton(
                                    selected = selectedId == contact.id,
                                    onClick = null,
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                            } else {
                                RadioButton(selected = selectedId == contact.id, onClick = null)
                            }
                            Column(Modifier.weight(1f)) { ContactIdentity(contact) }
                            VerifiedMark(contact.verified, if (isMiuixStyle()) MiuixTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            DialogButton(stringResource(R.string.action_import_contact), onClick = onImport)
        },
        dismissButton = {
            DialogButton(stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    )
}

/** One tickable recipient: a check box, the name, a short detail, and the verified mark. */
@Composable
private fun RecipientOption(title: String, detail: String, mark: String?, checked: Boolean, enabled: Boolean, verified: Boolean, onToggle: () -> Unit) {
    val miuix = isMiuixStyle()
    val text = if (miuix) MiuixTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface
    val summary = if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (miuix) {
            top.yukonga.miuix.kmp.basic.Checkbox(state = ToggleableState(checked), onClick = null, enabled = enabled)
        } else {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (mark != null) Text(mark, color = summary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
            }
            if (detail.isNotEmpty()) {
                Text(detail, color = summary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
        VerifiedMark(verified, text, Modifier.padding(start = 8.dp))
    }
}

/**
 * Asked when Encrypt is pressed: this device's own key first, then pinned
 * contacts, then the rest, then one-off key files. Nothing is ticked for the
 * user; the private key is the only default.
 */
@Composable
fun RecipientPickerDialog(
    state: PublicKeyContactsState,
    ownAvailable: Boolean,
    own: Boolean,
    selected: List<String>,
    extras: List<TemporaryPublicKey>,
    onToggleOwn: () -> Unit,
    onToggleContact: (PublicKeyContact) -> Unit,
    onRemoveExtra: (TemporaryPublicKey) -> Unit,
    onAddFile: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = selected.size + extras.size + if (own) 1 else 0
    val full = count >= NativeBridge.MAX_RECIPIENTS
    val contacts = state.contacts.filterNot { it.archived }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recipient_picker_title)) },
        text = {
            Column {
                ContactStorageStatus(state)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (ownAvailable) item(key = "own") {
                        RecipientOption(stringResource(R.string.recipient_own_key), "", stringResource(R.string.key_source_app_default), own, own || !full, false, onToggleOwn)
                    }
                    items(contacts, key = { it.id }) { contact ->
                        val checked = contact.id in selected
                        RecipientOption(
                            contact.label, shortFingerprint(contact.fingerprint),
                            if (contact.pinned) stringResource(R.string.contact_pinned_label) else null,
                            checked, checked || !full, contact.verified,
                        ) { onToggleContact(contact) }
                    }
                    items(extras, key = { "file:" + it.fingerprint }) { key ->
                        RecipientOption(key.displayName, shortFingerprint(key.fingerprint), null, true, true, false) { onRemoveExtra(key) }
                    }
                    item(key = "add") {
                        DialogButton(stringResource(R.string.recipient_other_file), onClick = onAddFile, enabled = !full)
                    }
                }
                Text(
                    stringResource(R.string.recipient_count, count, NativeBridge.MAX_RECIPIENTS),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            DialogButton(stringResource(R.string.action_encrypt), onClick = onConfirm, enabled = count > 0)
        },
        dismissButton = {
            DialogButton(stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    )
}

@Composable
fun PublicKeyContactNoteDialog(
    displayName: String,
    fingerprint: String,
    initialNote: String,
    editing: Boolean,
    duplicate: Boolean = false,
    upgrades: PublicKeyContact? = null,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember(fingerprint) { mutableStateOf(initialNote) }
    var checked by remember(fingerprint) { mutableStateOf(false) }
    val valid = PublicKeyContacts.validNote(note) && (upgrades == null || checked)
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editing) R.string.contact_edit_title else R.string.contact_save_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.key_file_line, displayName))
                Text(stringResource(R.string.key_fingerprint_line, fingerprint), style = MaterialTheme.typography.bodySmall)
                if (duplicate) Text(stringResource(R.string.contact_duplicate))
                if (upgrades != null) {
                    // Anyone with the old key can attach signing keys to it, so
                    // the new fingerprint must be checked with the owner first.
                    val danger = if (isMiuixStyle()) MiuixTheme.colorScheme.error else MaterialTheme.colorScheme.error
                    Text(stringResource(R.string.contact_upgrade_warning, upgrades.label), color = danger)
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = checked,
                            role = Role.Checkbox,
                            onValueChange = { checked = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(stringResource(R.string.contact_upgrade_confirm), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (isMiuixStyle()) {
                    top.yukonga.miuix.kmp.basic.TextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.contact_note_label),
                        minLines = 2,
                        maxLines = 4,
                    )
                    Text(
                        stringResource(R.string.contact_note_limit, PublicKeyContacts.MAX_NOTE_LENGTH),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (valid) LocalContentColor.current else MaterialTheme.colorScheme.error,
                    )
                } else {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.contact_note_label)) },
                        placeholder = { Text(stringResource(R.string.contact_note_hint)) },
                        supportingText = { Text(stringResource(R.string.contact_note_limit, PublicKeyContacts.MAX_NOTE_LENGTH)) },
                        isError = !valid,
                        minLines = 2,
                        maxLines = 4,
                    )
                }
            }
        },
        confirmButton = {
            DialogButton(stringResource(R.string.action_save_contact), onClick = { onSave(note) }, enabled = valid)
        },
        dismissButton = {
            DialogButton(stringResource(R.string.action_cancel), onClick = onDismiss)
        },
    )
}

@Composable
fun DeletePublicKeyContactDialog(contact: PublicKeyContact, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_delete_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.contact_delete_message, contact.label))
                Text(stringResource(R.string.key_fingerprint_line, contact.fingerprint))
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.action_delete), onClick = onConfirm) },
        dismissButton = { DialogButton(stringResource(R.string.action_cancel), onClick = onDismiss) },
    )
}

/**
 * Asked before an unverified contact's key is used: a key replaced on its way
 * to the user would let someone else open what is encrypted with it.
 */
@Composable
fun UnverifiedContactDialog(contacts: List<PublicKeyContact>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val danger = if (isMiuixStyle()) MiuixTheme.colorScheme.error else MaterialTheme.colorScheme.error
    val names = contacts.joinToString(stringResource(R.string.contact_name_separator)) { "“${it.label}”" }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_unverified_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.contact_unverified_message, names), color = danger)
            }
        },
        confirmButton = {
            DialogButton(stringResource(R.string.action_use_anyway), onClick = onConfirm, destructive = true)
        },
        dismissButton = { DialogButton(stringResource(R.string.action_cancel), onClick = onDismiss) },
    )
}

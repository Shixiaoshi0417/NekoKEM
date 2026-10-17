package com.shixiaoshi0417.nekokem.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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

@Composable
fun PublicKeyContactsPage(
    state: NekoKEMUiState,
    actions: NekoKEMActions,
    onUse: (PublicKeyContact) -> Unit,
    onEncryptSelected: () -> Unit,
) {
    val selected = state.contactSelection
    val atLimit = selected.size >= NativeBridge.MAX_RECIPIENTS
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(stringResource(R.string.contact_description), style = MaterialTheme.typography.bodyMedium)
        }
        item {
            ExpressiveButton(
                text = stringResource(R.string.action_import_contact),
                onClick = actions.onImportContact,
                enabled = state.nativeConnected && !state.running,
                size = ActionSize.MEDIUM,
                modifier = Modifier.fillMaxWidth(),
            )
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
        items(state.contactsState.contacts, key = { it.id }) { contact ->
            val checked = contact.id in selected
            val selectLabel = stringResource(R.string.contact_select_recipient)
            // A chosen recipient's card rounds further and gains an outline.
            val corner by animateDpAsState(
                targetValue = if (checked) 28.dp else 16.dp,
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                label = "contactCorner",
            )
            Card(
                modifier = Modifier.fillMaxWidth().animateItem(),
                shape = RoundedCornerShape(corner),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                border = if (checked) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = checked,
                            enabled = !state.running && (checked || !atLimit),
                            role = Role.Checkbox,
                            onValueChange = { actions.onToggleContactSelection(contact) },
                        ).semantics { contentDescription = "$selectLabel · ${contact.label}" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = !state.running && (checked || !atLimit))
                        Text(selectLabel, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
                    }
                    ContactIdentity(contact)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
}

@Composable
fun PublicKeyContactPicker(
    state: PublicKeyContactsState,
    selectedId: String?,
    onSelect: (PublicKeyContact) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_picker_title)) },
        text = {
            Column {
                ContactStorageStatus(state)
                LazyColumn(Modifier.heightIn(max = 360.dp).selectableGroup()) {
                    items(state.contacts, key = { it.id }) { contact ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = selectedId == contact.id,
                                role = Role.RadioButton,
                                onClick = { onSelect(contact) },
                            ).padding(vertical = 12.dp),
                        ) {
                            RadioButton(selected = selectedId == contact.id, onClick = null)
                            Column(Modifier.weight(1f)) { ContactIdentity(contact) }
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

@Composable
fun PublicKeyContactNoteDialog(
    displayName: String,
    fingerprint: String,
    initialNote: String,
    editing: Boolean,
    duplicate: Boolean = false,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember(fingerprint) { mutableStateOf(initialNote) }
    val valid = PublicKeyContacts.validNote(note)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editing) R.string.contact_edit_title else R.string.contact_save_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.key_file_line, displayName))
                Text(stringResource(R.string.key_fingerprint_line, fingerprint), style = MaterialTheme.typography.bodySmall)
                if (duplicate) Text(stringResource(R.string.contact_duplicate))
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
    AlertDialog(
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

package com.shixiaoshi0417.nekokem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
            Button(
                onClick = actions.onImportContact,
                enabled = state.nativeConnected && !state.running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_import_contact)) }
        }
        item { ContactStorageStatus(state.contactsState) }
        if (selected.isNotEmpty()) {
            item(key = "selection") {
                // Several recipients share one NKEM v4 file; nothing changes until this is pressed.
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.contact_selection_count, selected.size),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (atLimit) {
                            Text(
                                stringResource(R.string.contact_selection_limit, NativeBridge.MAX_RECIPIENTS),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(stringResource(R.string.contact_recipients_hint), style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = onEncryptSelected,
                            enabled = state.nativeConnected && !state.running,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.action_encrypt_for_selected)) }
                        TextButton(onClick = actions.onClearContactSelection, enabled = !state.running) {
                            Text(stringResource(R.string.action_clear_selection))
                        }
                    }
                }
            }
        }
        items(state.contactsState.contacts, key = { it.id }) { contact ->
            val checked = contact.id in selected
            val selectLabel = stringResource(R.string.contact_select_recipient)
            Card(Modifier.fillMaxWidth()) {
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
                        Text(selectLabel, style = MaterialTheme.typography.labelLarge)
                    }
                    ContactIdentity(contact)
                    TextButton(onClick = { onUse(contact) }, enabled = state.nativeConnected && !state.running) {
                        Text(stringResource(R.string.action_use_contact))
                    }
                    TextButton(onClick = { actions.onEditContact(contact) }, enabled = !state.running) {
                        Text(stringResource(R.string.action_edit_contact_note))
                    }
                    TextButton(onClick = { actions.onDeleteContact(contact) }, enabled = !state.running) {
                        Text(stringResource(R.string.action_delete))
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
    Text(contact.label, style = MaterialTheme.typography.titleMedium)
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
            TextButton(onClick = onImport) { Text(stringResource(R.string.action_import_contact)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
            TextButton(onClick = { onSave(note) }, enabled = valid) { Text(stringResource(R.string.action_save_contact)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

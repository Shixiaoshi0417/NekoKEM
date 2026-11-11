package com.shixiaoshi0417.nekokem.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Create
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Sidebar
import top.yukonga.miuix.kmp.icon.extended.Unlock

// One set of line icons for both styles.
private fun View.icon(): ImageVector = when (this) {
    View.Keygen -> MiuixIcons.Create; View.Encrypt -> MiuixIcons.Lock; View.Decrypt -> MiuixIcons.Unlock
    View.Fingerprint -> MiuixIcons.Scan; View.Contacts -> MiuixIcons.Contacts; View.Settings -> MiuixIcons.Settings
}

@Composable
fun NekoKEMApp(state: AppState) {
    val kit: Kit = if (state.style == Style.Material) MaterialKit else MiuixKit
    // Both themes stay installed, so either library's widgets find their theme.
    MiuixKit.Theme {
        MaterialKit.Theme {
            CompositionLocalProvider(LocalKit provides kit) {
                run {
                    kit.Root {
                        val colors = kit.colors()
                        Row(Modifier.fillMaxSize().background(colors.window)) {
                            if (state.sidebarOpen) Sidebar(state)
                            Column(Modifier.weight(1f).fillMaxHeight().background(colors.content)) {
                                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    kit.IconAction(MiuixIcons.Sidebar, state.t(if (state.sidebarOpen) "collapseSidebar" else "expandSidebar"), state::toggleSidebar)
                                }
                                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                                    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) { Page(state) }
                                }
                            }
                        }
                        QrDialog(state)
                        UpdateDialog(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(state: AppState) {
    val kit = LocalKit.current
    kit.Sidebar(
        header = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource("icon.png"), contentDescription = null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.padding(start = 12.dp)) {
                    kit.Text("NekoKEM", TextRole.Title)
                    kit.Text("v${state.version}", TextRole.Small)
                }
            }
        },
        entries = View.entries.map { NavEntry(state.t(it.key), it.icon(), state.view == it) { state.switchTab(it) } },
        enabled = !state.locked && state.loaded,
        footer = {
            Spacer(Modifier.height(24.dp))
            kit.LinkButton(state.t("exit"), enabled = !state.locked && state.loaded, modifier = Modifier.padding(horizontal = 16.dp), onClick = state::exitApp)
        },
    )
}

@Composable
private fun ColumnScope.Page(state: AppState) {
    val kit = LocalKit.current
    kit.Text(state.t(state.view.key), TextRole.Headline, Modifier.padding(top = 8.dp))
    kit.Text(state.description, TextRole.Small, Modifier.padding(top = 8.dp, bottom = 16.dp))
    when (state.view) {
        View.Settings -> SettingsPage(state)
        View.Contacts -> { OperationCard(state); ContactList(state) }
        else -> OperationCard(state)
    }
    Column(Modifier.padding(top = 24.dp)) {
        kit.Text(state.t("storage"), TextRole.Label)
        Hint(state.t("storageHint"))
    }
}

@Composable
private fun Hint(text: String, danger: Boolean = false, modifier: Modifier = Modifier) {
    val kit = LocalKit.current
    kit.Text(text, TextRole.Small, modifier.padding(top = 8.dp), color = if (danger) kit.colors().danger else null)
}

@Composable
private fun FieldLabel(text: String, invalid: Boolean) {
    val kit = LocalKit.current
    kit.Text(text, TextRole.Label, Modifier.padding(top = 16.dp, bottom = 8.dp), color = if (invalid) kit.colors().danger else null)
}

private val AppState.editable get() = !locked && loaded

@Composable
private fun PathInput(state: AppState, field: PathField, label: String, name: String) {
    val kit = LocalKit.current
    val invalid = state.invalidField == name
    FieldLabel(label, invalid)
    Row(verticalAlignment = Alignment.CenterVertically) {
        kit.TextInput(state.path(field), { state.setPath(field, it) }, label, state.editable, invalid, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        kit.SecondaryButton(if (state.choosing == field) state.t("choosing") else state.t("browse"), enabled = state.editable) { state.browse(field) }
    }
}

@Composable
private fun Secret(state: AppState, field: androidx.compose.foundation.text.input.TextFieldState, label: String, name: String, masked: Boolean, lines: Int = 1, placeholder: String = "") {
    val invalid = state.invalidField == name
    FieldLabel(label, invalid)
    LocalKit.current.SecretInput(field, label, state.editable, invalid, masked, lines, placeholder)
}

@Composable
private fun CheckRow(checked: Boolean, label: String, enabled: Boolean = true, onToggle: () -> Unit) {
    val kit = LocalKit.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onToggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        kit.Check(checked, enabled, label, null)
        kit.Text(label, TextRole.Body, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun OperationCard(state: AppState) {
    val kit = LocalKit.current
    kit.Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            kit.Text(state.t("subtitle"), TextRole.Small, Modifier.weight(1f))
            kit.Text(state.status, TextRole.Small)
        }
        when (state.view) {
            View.Keygen -> KeyFields(state)
            View.Contacts -> ContactFields(state)
            else -> FileFields(state)
        }
        if (state.passwordRequired) {
            Secret(state, state.password, state.t("password"), "password", masked = true)
            if (state.view == View.Keygen && state.keyMode == KeyMode.Generate) Secret(state, state.confirmation, state.t("confirmation"), "confirmation", masked = true)
            Hint(state.t(if (state.signing) "signPasswordHint" else if (state.view == View.Keygen && state.keyMode == KeyMode.Upgrade) "upgradePasswordHint" else "passwordHint"))
        }
        if (state.busy) ProgressBlock(state)
        UpgradeConfirm(state)
        if (state.feedback == Feedback.Form) FormFeedback(state)
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                kit.Text(state.algorithms, TextRole.Tiny)
                kit.Text(state.formatLabel, TextRole.Tiny)
            }
            if (state.busy && (state.view == View.Encrypt || state.view == View.Decrypt)) {
                kit.SecondaryButton(state.t("cancel"), enabled = !state.cancelling, onClick = state::cancel)
                Spacer(Modifier.width(8.dp))
            }
            val action = when {
                state.busy || state.contactBusy -> state.t("working")
                state.view == View.Contacts -> state.t("saveContact")
                state.view == View.Keygen && state.keyMode == KeyMode.Upgrade -> state.t("upgradeKey")
                state.view == View.Keygen && state.keyMode == KeyMode.Export -> state.t("exportLegacy")
                state.view == View.Keygen && state.replace -> state.t("replaceKeysAction")
                else -> state.t(state.view.key)
            }
            kit.PrimaryButton(action, enabled = state.editable, onClick = state::submit)
        }
    }
}

@Composable
private fun KeyFields(state: AppState) {
    Spacer(Modifier.height(16.dp))
    LocalKit.current.Segments(listOf(state.t("keygen"), state.t("upgradeKey"), state.t("exportLegacy")), state.keyMode.ordinal, state.editable) { state.changeKeyMode(KeyMode.entries[it]) }
    if (state.keyMode == KeyMode.Export) {
        PathInput(state, PathField.KeyPath, state.t("publicKey"), "keyPath")
        PathInput(state, PathField.Output, state.t("legacyOutput"), "output")
    } else {
        val upgrade = state.keyMode == KeyMode.Upgrade
        PathInput(state, PathField.PublicPath, state.t(if (upgrade) "publicKey" else "publicPath"), "publicPath")
        PathInput(state, PathField.PrivatePath, state.t(if (upgrade) "privateKey" else "privatePath"), "privatePath")
        if (state.replaceOffered) {
            Spacer(Modifier.height(8.dp))
            CheckRow(state.replace, state.t("replaceKeys"), state.editable) { state.replace = !state.replace }
            Hint(state.t("replaceKeysHint"), danger = true)
        }
    }
}

@Composable
private fun ContactFields(state: AppState) {
    val kit = LocalKit.current
    Spacer(Modifier.height(16.dp))
    kit.Segments(listOf(state.t("path"), state.t("paste")), if (state.contactPaste) 1 else 0, state.editable) { state.changeContactSource(it == 1) }
    if (!state.contactPaste) PathInput(state, PathField.ContactKeyPath, state.t("publicKey"), "contactKeyPath")
    else Secret(state, state.contactKeyText, state.t("paste"), "contactKeyText", masked = false, lines = 4, placeholder = state.t("pastePublicHint"))
    FieldLabel(state.t("note"), state.invalidField == "note")
    kit.TextInput(state.note, { state.note = it; state.edited("note") }, state.t("note"), state.editable, state.invalidField == "note", Modifier.fillMaxWidth())
    Hint(state.t("noteHint"))
}

@Composable
private fun FileFields(state: AppState) {
    val kit = LocalKit.current
    Spacer(Modifier.height(16.dp))
    val sources = buildList { add(KeySource.Path); add(KeySource.Paste); if (state.view == View.Encrypt) add(KeySource.Contact) }
    val names = sources.map { state.t(when (it) { KeySource.Path -> "path"; KeySource.Paste -> "paste"; KeySource.Contact -> "savedContact" }) }
    kit.Segments(names, sources.indexOf(state.source).coerceAtLeast(0), state.editable) { state.changeSource(sources[it]) }
    when (state.source) {
        KeySource.Path -> PathInput(state, PathField.KeyPath, state.t("key"), "keyPath")
        KeySource.Paste -> Secret(state, state.keyText, state.t("paste"), "keyText", masked = false, lines = 4, placeholder = state.t("pasteHint"))
        KeySource.Contact -> RecipientChoice(state)
    }
    if (state.view != View.Fingerprint) {
        PathInput(state, PathField.Input, state.t("input"), "input")
        PathInput(state, PathField.Output, state.t("output"), "output")
    }
    if (state.signing) PathInput(state, PathField.SignKeyPath, state.t("signKey"), "signKeyPath")
    if (state.view == View.Encrypt && state.unsigned) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint(state.t("unsignedWarning"), danger = true, modifier = Modifier.weight(1f))
            kit.LinkButton(state.t("settings"), modifier = Modifier.padding(start = 8.dp, top = 8.dp)) { state.switchTab(View.Settings) }
        }
    }
}

@Composable
private fun RecipientChoice(state: AppState) {
    val kit = LocalKit.current
    Row(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
        kit.Text(state.t("recipients"), TextRole.Label, Modifier.weight(1f), color = if (state.invalidField == "contacts") kit.colors().danger else null)
        kit.Text("${state.selected.size} / $MAX_RECIPIENTS", TextRole.Small)
    }
    for (contact in state.contacts) {
        val chosen = contact.id in state.selected
        val enabled = !state.locked && (chosen || !state.atRecipientLimit)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { state.toggleContact(contact) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            kit.Check(chosen, enabled, state.label(contact), null)
            Column(Modifier.padding(start = 12.dp)) {
                kit.Text(state.label(contact), TextRole.Body)
                kit.Text(contact.fingerprint, TextRole.Tiny, mono = true)
            }
        }
    }
    val chosen = state.selectedContacts
    if (chosen.size == 1) Hint("${state.t("fingerprint")}: ${chosen[0].fingerprint}")
    else if (chosen.size > 1) Hint(state.t("multiRecipientHint"))
    if (state.atRecipientLimit) Hint(state.t("recipient-limit"))
    state.contactsFailure?.let { Hint(state.t(it), danger = true) }
    if (state.contactsFailure == null && state.contactsLoaded && state.contacts.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint(state.t("noContacts"), modifier = Modifier.weight(1f))
            kit.LinkButton(state.t("manageContacts"), modifier = Modifier.padding(top = 8.dp)) { state.switchTab(View.Contacts) }
        }
    }
}

@Composable
private fun ProgressBlock(state: AppState) {
    val kit = LocalKit.current
    Column(Modifier.padding(top = 16.dp)) {
        Row {
            kit.Text(state.t(if (state.cancelling) "cancelling" else if (state.progress != null) "progress" else "preparing"), TextRole.Body, Modifier.weight(1f))
            state.percent?.let { kit.Text("$it%", TextRole.BodyStrong) }
        }
        kit.Progress(state.percent?.let { it / 100f }, Modifier.padding(top = 8.dp))
    }
}

/** A grouped message, with an optional detail block. */
@Composable
private fun Notice(title: String, danger: Boolean = false, content: @Composable ColumnScope.() -> Unit = {}) {
    val kit = LocalKit.current
    val colors = kit.colors()
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(10.dp)).background(colors.notice).padding(12.dp)) {
        kit.Text(title, TextRole.BodyStrong, color = if (danger) colors.danger else null)
        content()
    }
}

@Composable
private fun Detail(text: String, mono: Boolean = false) {
    SelectionContainer { LocalKit.current.Text(text, TextRole.Small, Modifier.padding(top = 4.dp), mono = mono) }
}

@Composable
private fun FormFeedback(state: AppState) {
    val kit = LocalKit.current
    if (state.overwriteOffered) {
        Spacer(Modifier.height(8.dp))
        CheckRow(state.overwrite, state.t("overwriteOutput"), state.editable) { state.overwrite = !state.overwrite }
        Hint(state.t("overwriteOutputHint"), danger = true)
    }
    state.failure?.let { failure ->
        Notice(state.t(failure), danger = failure != "cancelled") {
            state.failedContact?.let { Detail("${state.t("recipient")}: ${state.label(it)} · ${it.fingerprint}") }
        }
    }
    val result = state.result ?: return
    Notice(state.t(state.successMessage), danger = state.successMessage == "encryptedUnsigned") {
        result.output?.let { Detail(it) }
        if (state.recipients.isNotEmpty()) Detail("${state.t(if (state.recipients.size > 1) "recipients" else "recipient")}: ${state.recipients.joinToString(", ") { state.label(it) }}")
        result.fingerprint?.let { fingerprint ->
            Detail(fingerprint, mono = true)
            if (FingerprintQr.isFingerprint(fingerprint)) { Spacer(Modifier.height(8.dp)); kit.SecondaryButton(state.t("showQr")) { state.showQr(fingerprint) } }
        }
        result.legacyFingerprint?.takeIf { it != result.fingerprint }?.let { Detail(state.t("legacyFingerprint")); Detail(it, mono = true) }
        if (result.canVerify == false) Hint(state.t("legacyKeyHint"), danger = true)
    }
    val signature = result.signature ?: return
    val title = when (signature.status) { "trusted" -> "signatureTrusted"; "untrusted" -> "signatureUntrusted"; else -> "signatureNone" }
    Notice(state.t(title), danger = signature.status != "trusted") {
        when (signature.status) {
            "trusted" -> Detail("${state.t("signer")}: ${state.signerContact?.let(state::label) ?: state.t("savedContact")}")
            "untrusted" -> Detail(state.t("signatureUntrustedHint"))
            else -> Detail(state.t("signatureNoneHint"))
        }
        signature.fingerprint?.let { Detail(it, mono = true) }
        if (state.signerSaved) Detail(state.t("signerSaved"))
        else if (signature.signerKey != null) { Spacer(Modifier.height(8.dp)); kit.SecondaryButton(state.t("saveSigner"), enabled = !state.locked, onClick = state::saveSigner) }
    }
}

@Composable
private fun UpgradeConfirm(state: AppState) {
    val prompt = state.upgradePrompt ?: return
    val kit = LocalKit.current
    Notice(state.t("upgradeTitle").replace("{name}", state.upgradeName), danger = true) {
        Hint(state.t("upgradeText"), danger = true)
        Detail(prompt.fingerprint, mono = true)
        CheckRow(state.upgradeChecked, state.t("upgradeCheck")) { state.upgradeChecked = !state.upgradeChecked }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            kit.SecondaryButton(state.t("cancel")) { state.confirmUpgrade(false) }
            Spacer(Modifier.width(8.dp))
            kit.DangerButton(state.t("upgradeAccept"), enabled = state.upgradeChecked && !state.locked) { state.confirmUpgrade(true) }
        }
    }
}

@Composable
private fun ContactList(state: AppState) {
    val kit = LocalKit.current
    kit.SectionTitle("${state.t("savedContacts")} · ${state.contacts.size}")
    kit.Section {
        state.contactsFailure?.let { Notice(state.t(it), danger = true) }
        if (state.unreadable > 0) Notice(state.t("contactUnreadable").replace("{count}", state.unreadable.toString()))
        if (state.feedback == Feedback.List) {
            state.failure?.let { Notice(state.t(it), danger = true) }
            if (state.result != null) Notice(state.t(state.successMessage))
        }
        if (state.contactsLoaded && state.contactsFailure == null && state.contacts.isEmpty()) Hint(state.t("noContacts"))
        if (state.selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                kit.Text(state.t("selectedCount").replace("{count}", state.selected.size.toString()), TextRole.Body, Modifier.weight(1f))
                if (state.atRecipientLimit) kit.Text(state.t("recipient-limit"), TextRole.Small, Modifier.padding(end = 8.dp))
                kit.LinkButton(state.t("clearSelection"), enabled = !state.locked, onClick = state::clearSelection)
                Spacer(Modifier.width(8.dp))
                kit.SecondaryButton(state.t("encryptSelected"), enabled = !state.locked, onClick = state::encryptSelected)
            }
        }
        val colors = kit.colors()
        state.contacts.forEachIndexed { index, contact ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
            ContactRow(state, contact)
        }
    }
}

@Composable
private fun ContactRow(state: AppState, contact: Contact) {
    val kit = LocalKit.current
    val colors = kit.colors()
    val chosen = contact.id in state.selected
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (state.highlighted == contact.id) colors.highlight else Color.Transparent).padding(vertical = 12.dp, horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            kit.Check(chosen, !state.locked && (chosen || !state.atRecipientLimit), "${state.t("selectContact")} · ${state.label(contact)}") { state.toggleContact(contact) }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                kit.Text(state.label(contact), TextRole.BodyStrong)
                if (contact.note.isNotEmpty()) kit.Text("${state.t("sourceFile")}: ${contact.name.ifEmpty { state.t("pastedKey") }}", TextRole.Small)
                Detail(contact.fingerprint, mono = true)
                kit.Text(state.t(if (contact.canVerify) "contactVerifies" else "contactLegacy"), TextRole.Tiny, Modifier.padding(top = 4.dp), color = if (contact.canVerify) null else colors.danger)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            when (contact.id) {
                state.editing -> {
                    kit.TextInput(state.noteDraft, state::editNoteDraft, "${state.t("note")} · ${state.label(contact)}", !state.locked, state.invalidField == "noteDraft", Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    kit.SecondaryButton(state.t("saveNote"), enabled = !state.locked) { state.saveNote(contact) }
                    Spacer(Modifier.width(8.dp))
                    kit.LinkButton(state.t("cancel"), enabled = !state.locked, onClick = state::cancelEdit)
                }
                state.confirmingDelete -> {
                    kit.Text(state.t("deletePrompt"), TextRole.Body, Modifier.weight(1f))
                    kit.DangerButton(state.t("confirmDelete"), enabled = !state.locked) { state.removeContact(contact) }
                    Spacer(Modifier.width(8.dp))
                    kit.LinkButton(state.t("cancel"), enabled = !state.locked, onClick = state::cancelDelete)
                }
                else -> {
                    kit.SecondaryButton(state.t("useContact"), enabled = !state.locked) { state.useContact(contact) }
                    Spacer(Modifier.width(8.dp))
                    kit.LinkButton(state.t("editNote"), enabled = !state.locked) { state.startEdit(contact) }
                    Spacer(Modifier.width(8.dp))
                    kit.LinkButton(state.t("deleteContact"), enabled = !state.locked) { state.askDelete(contact) }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(state: AppState) {
    val kit = LocalKit.current
    val danger = kit.colors().danger
    kit.SectionTitle(state.t("settingsGeneral"), first = true)
    kit.Section(padded = false) {
        val tags = Strings.choices.map { it.first }
        kit.ChoiceRow(state.t("language"), state.t("languageHint"), Strings.choices.map { (tag, name) -> if (tag == "system") state.t("system") else name },
            tags.indexOf(state.selectedLanguage).coerceAtLeast(0), state.editable) { state.changeLanguage(tags[it]) }
        kit.ChoiceRow(state.t("uiStyle"), state.t("uiStyleHint"), Style.entries.map { it.title }, state.style.ordinal, true) { state.changeStyle(Style.entries[it]) }
    }
    kit.SectionTitle(state.t("settingsSigning"))
    kit.Section(padded = false) {
        kit.SwitchRow(state.t("unsignedSetting"), state.t(if (state.unsigned) "unsignedWarning" else "unsignedSettingHint"), state.unsigned, danger = state.unsigned, enabled = !state.confirmingUnsigned, onChange = state::toggleUnsigned)
        if (state.confirmingUnsigned) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                kit.Text(state.t("unsignedConfirmTitle"), TextRole.BodyStrong, color = danger)
                Hint(state.t("unsignedConfirmText"), danger = true)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    kit.SecondaryButton(state.t("cancel")) { state.confirmUnsigned(false) }
                    Spacer(Modifier.width(8.dp))
                    kit.DangerButton(state.t("unsignedConfirm")) { state.confirmUnsigned(true) }
                }
            }
        }
        kit.SwitchRow(state.t("requireSignature"), state.t("requireSignatureHint"), state.requireSignature, onChange = state::changeRequireSignature)
    }
    kit.SectionTitle(state.t("settingsUpdates"))
    kit.Section(padded = false) {
        kit.SwitchRow(state.t("autoUpdate"), state.t("autoUpdateHint"), state.autoUpdate, onChange = state::changeAutoUpdate)
        kit.ActionRow(state.t(if (state.checkingUpdate) "checkingUpdate" else "checkUpdate"), state.updateNotice?.let(state::t) ?: "v${state.version}", !state.checkingUpdate) { state.checkForUpdate(true) }
    }
}

@Composable
private fun QrDialog(state: AppState) {
    val kit = LocalKit.current
    val fingerprint = state.qrFingerprint
    kit.Dialog(fingerprint.isNotEmpty(), state.t("qrTitle"), state.t("qrHint"), state::closeQr) {
        if (fingerprint.isEmpty()) return@Dialog
        val modules = remember(fingerprint) { FingerprintQr.modules(fingerprint) }
        // Black on white in both themes, with a four-module quiet zone, so cameras read it.
        Canvas(Modifier.size(240.dp).background(Color.White)) {
            val cell = size.width / (modules.size + 8)
            for (row in modules.indices) for (column in modules[row].indices) {
                if (modules[row][column]) drawRect(Color.Black, Offset((column + 4) * cell, (row + 4) * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
        kit.Text(fingerprint.take(47), TextRole.Small, Modifier.padding(top = 12.dp), mono = true)
        kit.Text(fingerprint.drop(48), TextRole.Small, mono = true)
        Spacer(Modifier.height(16.dp))
        kit.SecondaryButton(state.t("close"), modifier = Modifier.fillMaxWidth(), onClick = state::closeQr)
    }
}

@Composable
private fun UpdateDialog(state: AppState) {
    val kit = LocalKit.current
    val version = state.availableVersion
    kit.Dialog(version.isNotEmpty(), state.t("updateAvailableTitle").replace("{version}", version), state.t("updateAvailableText").replace("{current}", state.version), state::dismissUpdate) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            kit.LinkButton(state.t("ignoreVersion"), onClick = state::ignoreRelease)
            Spacer(Modifier.weight(1f))
            kit.SecondaryButton(state.t("later"), onClick = state::dismissUpdate)
            Spacer(Modifier.width(8.dp))
            kit.PrimaryButton(state.t("openRelease"), enabled = true, onClick = state::openRelease)
        }
    }
}

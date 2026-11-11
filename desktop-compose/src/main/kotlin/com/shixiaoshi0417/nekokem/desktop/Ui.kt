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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.NotesFill
import top.yukonga.miuix.kmp.icon.extended.Sidebar

/** The four sidebar entries, as on Android: file encryption, keys, contacts and settings. */
private enum class Destination(val key: String, val icon: ImageVector, val views: List<View>) {
    Files("navFiles", MiuixIcons.NotesFill, listOf(View.Encrypt, View.Decrypt)),
    Keys("navKeys", Icons.Filled.Lock, listOf(View.Keygen, View.Fingerprint)),
    Contacts("navContacts", Icons.Filled.Person, listOf(View.Contacts)),
    Settings("settings", Icons.Filled.Settings, listOf(View.Settings)),
}
private val AppState.destination get() = Destination.entries.first { view in it.views }

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
                        RecipientDialog(state)
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
        entries = Destination.entries.map { NavEntry(state.t(it.key), it.icon, state.destination == it) { state.switchTab(it.views.first()) } },
        enabled = !state.locked && state.loaded,
        footer = {},
    )
}

@Composable
private fun ColumnScope.Page(state: AppState) {
    val kit = LocalKit.current
    kit.Text(state.t(state.destination.key), TextRole.Headline, Modifier.padding(top = 8.dp, bottom = 16.dp))
    when (state.destination) {
        Destination.Settings -> SettingsPage(state)
        Destination.Contacts -> { OperationCard(state); ContactList(state) }
        Destination.Files -> {
            kit.Segments(listOf(state.t("encrypt"), state.t("decrypt")), if (state.view == View.Decrypt) 1 else 0, state.editable) { state.switchTab(if (it == 1) View.Decrypt else View.Encrypt) }
            Spacer(Modifier.height(16.dp))
            OperationCard(state)
        }
        Destination.Keys -> {
            // Generate, upgrade and export are the key page's modes; the fingerprint is the fourth tab.
            val selected = if (state.view == View.Fingerprint) 3 else state.keyMode.ordinal
            kit.Segments(listOf(state.t("keygen"), state.t("upgradeKey"), state.t("exportLegacy"), state.t("fingerprintTab")), selected, state.editable) {
                if (it == 3) state.switchTab(View.Fingerprint) else { state.switchTab(View.Keygen); state.changeKeyMode(KeyMode.entries[it]) }
            }
            Spacer(Modifier.height(16.dp))
            OperationCard(state)
        }
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
        when (state.view) {
            View.Keygen -> KeyFields(state)
            View.Contacts -> ContactFields(state)
            else -> FileFields(state)
        }
        if (state.passwordRequired) {
            Secret(state, state.password, state.t("password"), "password", masked = true)
            if (state.view == View.Keygen && state.keyMode == KeyMode.Generate) Secret(state, state.confirmation, state.t("confirmation"), "confirmation", masked = true)
        }
        if (state.busy) ProgressBlock(state)
        UpgradeConfirm(state)
        if (state.feedback == Feedback.Form) FormFeedback(state)
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
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
    kit.Segments(listOf(state.t("path"), state.t("paste")), if (state.contactPaste) 1 else 0, state.editable) { state.changeContactSource(it == 1) }
    if (!state.contactPaste) PathInput(state, PathField.ContactKeyPath, state.t("publicKey"), "contactKeyPath")
    else Secret(state, state.contactKeyText, state.t("paste"), "contactKeyText", masked = false, lines = 4, placeholder = state.t("pastePublicHint"))
    FieldLabel(state.t("note"), state.invalidField == "note")
    kit.TextInput(state.note, { state.note = it; state.edited("note") }, state.t("note"), state.editable, state.invalidField == "note", Modifier.fillMaxWidth())
}

@Composable
private fun FileFields(state: AppState) {
    val kit = LocalKit.current
    when (state.view) {
        View.Fingerprint -> {
            val sources = listOf(KeySource.Path, KeySource.Paste)
            kit.Segments(sources.map { state.t(if (it == KeySource.Path) "path" else "paste") }, sources.indexOf(state.source).coerceAtLeast(0), state.editable) { state.changeSource(sources[it]) }
            if (state.source == KeySource.Paste) Secret(state, state.keyText, state.t("paste"), "keyText", masked = false, lines = 4, placeholder = state.t("pasteHint"))
            else PathInput(state, PathField.KeyPath, state.t("key"), "keyPath")
            return
        }
        View.Decrypt -> PathInput(state, PathField.KeyPath, state.t("myPrivateKey"), "keyPath")
        else -> {}
    }
    PathInput(state, PathField.Input, state.t("input"), "input")
    PathInput(state, PathField.Output, state.t("output"), "output")
    if (state.signing) PathInput(state, PathField.SignKeyPath, state.t("myPrivateKey"), "signKeyPath")
    if (state.view == View.Encrypt && state.unsigned) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint(state.t("unsignedWarning"), danger = true, modifier = Modifier.weight(1f))
            kit.LinkButton(state.t("settings"), modifier = Modifier.padding(start = 8.dp, top = 8.dp)) { state.switchTab(View.Settings) }
        }
    }
}


/** As on Android and Signal: an outlined shield with a tick, in the text color, once the fingerprint was compared. Unverified keys have no mark. */
@Composable
private fun VerificationBadge(verified: Boolean, size: Dp = 20.dp) {
    if (!verified) return
    val color = LocalKit.current.colors().text
    Canvas(Modifier.size(size)) {
        val side = this.size.minDimension
        val stroke = Stroke(width = side * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val shield = Path().apply {
            moveTo(side * 0.5f, side * 0.07f); lineTo(side * 0.85f, side * 0.2f); lineTo(side * 0.85f, side * 0.46f)
            cubicTo(side * 0.85f, side * 0.7f, side * 0.69f, side * 0.86f, side * 0.5f, side * 0.94f)
            cubicTo(side * 0.31f, side * 0.86f, side * 0.15f, side * 0.7f, side * 0.15f, side * 0.46f)
            lineTo(side * 0.15f, side * 0.2f); close()
        }
        drawPath(shield, color, style = stroke)
        val tick = Path().apply { moveTo(side * 0.34f, side * 0.5f); lineTo(side * 0.46f, side * 0.62f); lineTo(side * 0.67f, side * 0.39f) }
        drawPath(tick, color, style = stroke)
    }
}

/** One tickable recipient row; [verified] puts the verification mark at its end. */
@Composable
private fun RecipientRow(title: String, detail: String, mark: String, checked: Boolean, enabled: Boolean, verified: Boolean?, onToggle: () -> Unit) {
    val kit = LocalKit.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onToggle).padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        kit.Check(checked, enabled, title, null)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                kit.Text(title, TextRole.Body)
                if (mark.isNotEmpty()) kit.Text(mark, TextRole.Tiny, Modifier.padding(start = 8.dp), color = kit.colors().summary)
            }
            if (detail.isNotEmpty()) kit.Text(detail, TextRole.Tiny, mono = true)
        }
        if (verified == true) { Spacer(Modifier.width(12.dp)); VerificationBadge(true) }
    }
}

private fun fileName(path: String) = path.split('/', '\\').last()

/** Asked when Encrypt is pressed: own key first, then pinned contacts, then the rest. Nothing is ticked for the user. */
@Composable
private fun RecipientDialog(state: AppState) {
    val kit = LocalKit.current
    kit.Dialog(state.choosingRecipients, state.t("chooseRecipients"), "", state::closeRecipients) {
        if (!state.choosingRecipients) return@Dialog
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            if (state.ownPublicKey.isNotEmpty()) {
                RecipientRow(state.t("myPublicKey"), fileName(state.ownPublicKey), state.t("appDefault"), state.ownSelected,
                    !state.locked && (state.ownSelected || !state.atRecipientLimit), null, state::toggleOwn)
            }
            for (contact in state.contacts) {
                val chosen = contact.id in state.selected
                RecipientRow(state.label(contact), contact.fingerprint.take(23), if (contact.pinned) state.t("pinnedMark") else "", chosen,
                    !state.locked && (chosen || !state.atRecipientLimit), contact.verified) { state.toggleContact(contact) }
            }
            for (path in state.otherKeys) RecipientRow(fileName(path), "", state.t("otherKeyFile"), true, !state.locked, false) { state.removeOtherKey(path) }
        }
        kit.LinkButton(state.t("otherKeyFile"), enabled = !state.locked && !state.atRecipientLimit, modifier = Modifier.padding(top = 8.dp), onClick = state::addOtherKey)
        state.contactsFailure?.let { Hint(state.t(it), danger = true) }
        if (state.invalidField == "contacts") state.failure?.let { Hint(state.t(it), danger = true) }
        val unverified = state.unverifiedChosen
        if (state.unverifiedWarned && unverified.isNotEmpty()) Hint(state.t("unverifiedMessage").replace("{names}", unverified.joinToString(", ") { state.label(it) }), danger = true)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            kit.Text("${state.recipientCount} / $MAX_RECIPIENTS", TextRole.Small, Modifier.weight(1f))
            kit.SecondaryButton(state.t("cancel"), onClick = state::closeRecipients)
            Spacer(Modifier.width(8.dp))
            val warned = state.unverifiedWarned && unverified.isNotEmpty()
            if (warned) kit.DangerButton(state.t("encryptAnyway"), enabled = state.editable, onClick = state::submit)
            else kit.PrimaryButton(state.t("encrypt"), enabled = state.editable && state.recipientCount > 0, onClick = state::submit)
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
        val names = (if (state.ownRecipient) listOf(state.t("myPublicKey")) else emptyList()) + state.otherRecipients.map(::fileName) + state.recipients.map(state::label)
        if (names.isNotEmpty()) Detail("${state.t(if (names.size > 1) "recipients" else "recipient")}: ${names.joinToString(", ")}")
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    kit.Text(state.label(contact), TextRole.BodyStrong)
                    if (contact.pinned) kit.Text(state.t("pinnedMark"), TextRole.Tiny, Modifier.padding(start = 8.dp))
                }
                if (contact.note.isNotEmpty()) kit.Text("${state.t("sourceFile")}: ${contact.name.ifEmpty { state.t("pastedKey") }}", TextRole.Small)
                Detail(contact.fingerprint, mono = true)
                kit.Text(state.t(if (contact.canVerify) "contactVerifies" else "contactLegacy"), TextRole.Tiny, Modifier.padding(top = 4.dp), color = if (contact.canVerify) null else colors.danger)
            }
            if (contact.verified) { Spacer(Modifier.width(12.dp)); VerificationBadge(true, 24.dp) }
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
                    kit.LinkButton(state.t(if (contact.verified) "unmarkVerified" else "markVerified"), enabled = !state.locked) { state.toggleVerified(contact) }
                    Spacer(Modifier.width(8.dp))
                    kit.LinkButton(state.t(if (contact.pinned) "unpinContact" else "pinContact"), enabled = !state.locked) { state.togglePin(contact) }
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
        kit.ChoiceRow(state.t("language"), "", Strings.choices.map { (tag, name) -> if (tag == "system") state.t("system") else name },
            tags.indexOf(state.selectedLanguage).coerceAtLeast(0), state.editable) { state.changeLanguage(tags[it]) }
        kit.ChoiceRow(state.t("uiStyle"), "", Style.entries.map { it.title }, state.style.ordinal, true) { state.changeStyle(Style.entries[it]) }
    }
    kit.SectionTitle(state.t("settingsKeys"))
    kit.Section(padded = false) {
        kit.ActionRow(state.t("myPrivateKey"), state.signKeyPath.ifEmpty { state.t("notSet") }, state.editable) { state.browse(PathField.SignKeyPath) }
        kit.ActionRow(state.t("myPublicKey"), state.ownPublicKey.ifEmpty { state.t("notSet") }, state.editable) { state.browse(PathField.OwnPublicKey) }
    }
    kit.SectionTitle(state.t("settingsSigning"))
    kit.Section(padded = false) {
        kit.SwitchRow(state.t("unsignedSetting"), if (state.unsigned) state.t("unsignedWarning") else "", state.unsigned, danger = state.unsigned, enabled = !state.confirmingUnsigned, onChange = state::toggleUnsigned)
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
        kit.SwitchRow(state.t("requireSignature"), "", state.requireSignature, onChange = state::changeRequireSignature)
    }
    kit.SectionTitle(state.t("settingsUpdates"))
    kit.Section(padded = false) {
        kit.SwitchRow(state.t("autoUpdate"), "", state.autoUpdate, onChange = state::changeAutoUpdate)
        kit.ActionRow(state.t(if (state.checkingUpdate) "checkingUpdate" else "checkUpdate"), state.updateNotice?.let(state::t) ?: "v${state.version}", !state.checkingUpdate) { state.checkForUpdate(true) }
    }
}

@Composable
private fun QrDialog(state: AppState) {
    val kit = LocalKit.current
    val fingerprint = state.qrFingerprint
    kit.Dialog(fingerprint.isNotEmpty(), state.t("qrTitle"), "", state::closeQr) {
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

import AppKit
import CoreImage
import SwiftUI

private let danger = Color(nsColor: .systemRed)

// Liquid Glass belongs to the navigation layer: the system draws the sidebar
// and the toolbar as glass on macOS 26, and the page content stays plain
// underneath. Earlier systems show the same structure with their own materials.
extension View {
    /// The one main action of a page or sheet.
    @ViewBuilder func primaryAction() -> some View {
        if #available(macOS 26.0, *) { buttonStyle(.glassProminent) } else { buttonStyle(.borderedProminent) }
    }
    /// Controls that float over content.
    @ViewBuilder func floatingGlass() -> some View {
        if #available(macOS 26.0, *) { glassEffect(.regular, in: .capsule) } else { background(.regularMaterial, in: Capsule()) }
    }
}

struct ContentView: View {
    @ObservedObject var state: AppState

    var body: some View {
        NavigationSplitView {
            List(selection: Binding<Destination?>(get: { state.destination }, set: { if let item = $0, state.editable { state.open(item) } })) {
                ForEach(Destination.allCases) { item in
                    Label(state.t(item.key), systemImage: item.symbol).tag(item)
                }
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 200, max: 280)
            .safeAreaInset(edge: .bottom) {
                Text("NekoKEM v" + state.version).font(.caption).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.bottom, 12)
            }
        } detail: {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    switch state.destination {
                    case .files: FilesPage(state: state)
                    case .keys: KeysPage(state: state)
                    case .contacts: ContactsPage(state: state)
                    case .settings: SettingsPage(state: state)
                    }
                }
                .frame(maxWidth: 640, alignment: .leading)
                .padding(24)
                .frame(maxWidth: .infinity)
            }
            .safeAreaInset(edge: .bottom) {
                if state.destination == .contacts && !state.selected.isEmpty { SelectionBar(state: state).padding(.bottom, 16) }
            }
            .navigationTitle(state.t(state.destination.key))
            .toolbar {
                ToolbarItem(placement: .principal) {
                    if state.destination == .keys { KeysModePicker(state: state) }
                }
            }
        }
        .frame(minWidth: 820, minHeight: 600)
        // Encrypting asks for the recipients and then, to sign, for the password: one sheet, two steps.
        .sheet(isPresented: Binding(get: { state.choosingRecipients || state.askingPassword }, set: { if !$0 { state.closeRecipients() } })) {
            if state.askingPassword { PasswordSheet(state: state) } else { RecipientSheet(state: state) }
        }
        .sheet(isPresented: Binding(get: { !state.qrFingerprint.isEmpty }, set: { if !$0 { state.qrFingerprint = "" } })) { QrSheet(state: state) }
        .sheet(isPresented: Binding(get: { !state.availableVersion.isEmpty }, set: { if !$0 { state.availableVersion = "" } })) { UpdateSheet(state: state) }
    }
}

/// The four key tasks, in the toolbar.
private struct KeysModePicker: View {
    @ObservedObject var state: AppState
    var body: some View {
        Picker("", selection: Binding(get: { state.screen == .fingerprint ? 3 : state.keyMode.rawValue }, set: { choice in
            if choice == 3 { state.switchTo(.fingerprint) } else { state.switchTo(.keygen); state.changeKeyMode(KeyMode(rawValue: choice) ?? .generate) }
        })) {
            Text(state.t("keygen")).tag(0)
            Text(state.t("upgradeKey")).tag(1)
            Text(state.t("exportLegacy")).tag(2)
            Text(state.t("fingerprintTab")).tag(3)
        }
        .pickerStyle(.segmented).labelsHidden().disabled(!state.editable)
    }
}

/// Floats over the contact list while contacts are ticked.
private struct SelectionBar: View {
    @ObservedObject var state: AppState
    var body: some View {
        HStack(spacing: 12) {
            Text(state.t("selectedCount").replacingOccurrences(of: "{count}", with: String(state.selected.count)))
            Button(state.t("clearSelection")) { state.clearSelection() }.buttonStyle(.link).disabled(state.locked)
            Button(state.t("encryptSelected")) { state.encryptSelected() }.primaryAction().disabled(state.locked)
        }
        .padding(.leading, 16).padding(.trailing, 8).padding(.vertical, 8)
        .floatingGlass()
    }
}

/// A titled group box, as in System Settings.
private struct Card<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            // A quiet neutral fill: on macOS 26 the window and the control background are the same white.
            .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

private struct FieldLabel: View {
    var text: String
    var invalid: Bool
    var body: some View { Text(text).font(.subheadline).foregroundStyle(invalid ? danger : Color.secondary) }
}

private struct PathInput: View {
    @ObservedObject var state: AppState
    var field: PathField
    var label: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            FieldLabel(text: label, invalid: state.invalidField == field.rawValue)
            HStack(spacing: 8) {
                TextField("", text: Binding(get: { state.path(field) }, set: { state.setPath(field, $0) }))
                    .textFieldStyle(.roundedBorder)
                    .disabled(!state.editable)
                    .accessibilityLabel(label)
                Button(state.t("browse")) { state.browse(field) }.disabled(!state.editable)
            }
        }
    }
}

private struct SecretInput: View {
    @ObservedObject var state: AppState
    var label: String
    var name: String
    @Binding var text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            FieldLabel(text: label, invalid: state.invalidField == name)
            SecureField("", text: $text).textFieldStyle(.roundedBorder).disabled(!state.editable).accessibilityLabel(label)
        }
    }
}

/// Under a private-key password field: save it to the login keychain once it
/// is accepted, and fill in the one saved earlier.
private struct KeychainOptions: View {
    @ObservedObject var state: AppState

    var body: some View {
        HStack(spacing: 12) {
            Toggle(state.t("keychainSave"), isOn: $state.saveToKeychain).disabled(!state.editable)
            Spacer()
            if state.keychainFillOffered {
                Button(state.t("keychainFill")) { state.fillFromKeychain() }.disabled(!state.editable || state.keychainKeyPath.isEmpty)
            }
        }
        if let notice = state.keychainNotice, notice == "keychainMissing" || notice == "keychainFailed" {
            Text(state.t(notice)).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct PasteInput: View {
    @ObservedObject var state: AppState
    var label: String
    var name: String
    @Binding var text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            FieldLabel(text: label, invalid: state.invalidField == name)
            TextEditor(text: $text)
                .font(.system(.body, design: .monospaced))
                .frame(height: 88)
                .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(Color(nsColor: .separatorColor)))
                .disabled(!state.editable)
                .accessibilityLabel(label)
        }
    }
}

private struct Notice<Content: View>: View {
    var title: String
    var warn = false
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).fontWeight(.semibold).foregroundStyle(warn ? danger : Color.primary)
            content
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(nsColor: .quaternaryLabelColor), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct Detail: View {
    var text: String
    var mono = false
    var body: some View {
        Text(text).font(mono ? .system(.callout, design: .monospaced) : .callout).foregroundStyle(.secondary).textSelection(.enabled)
    }
}

/// As on Android and Signal: a shield with a tick once the fingerprint was compared. Unverified keys have no mark.
private struct VerifiedMark: View {
    var verified: Bool
    var body: some View {
        if verified { Image(systemName: "checkmark.shield").foregroundStyle(.primary) }
    }
}

// MARK: Pages

/// The file page, as on Android: the chosen file with Encrypt and Decrypt, then
/// the private key that decrypts. Recipients and the password are asked for when
/// a button is pressed, and where to save once the work is done.
private struct FilesPage: View {
    @ObservedObject var state: AppState

    var body: some View {
        let chosen = !state.input.isEmpty
        let other = !state.otherPrivateKey.isEmpty
        Card {
            VStack(alignment: .leading, spacing: 2) {
                Text(chosen ? fileName(state.input) : state.t("noFileSelected")).font(.title3).fontWeight(.semibold)
                Text(chosen ? state.input : state.t("selectedFile")).font(.callout).foregroundStyle(.secondary).lineLimit(2).truncationMode(.middle)
            }
            HStack(spacing: 8) {
                Button { state.encrypt() } label: { Text(state.t("encryptAction")).frame(maxWidth: .infinity) }
                    .primaryAction().controlSize(.large).disabled(!state.editable || !chosen)
                Button { state.decrypt() } label: { Text(state.t("decryptAction")).frame(maxWidth: .infinity) }
                    .controlSize(.large).disabled(!state.editable || !chosen)
            }
            if state.unsigned { Text(state.t("unsignedWarning")).font(.callout).foregroundStyle(danger) }
            HStack(spacing: 12) {
                Button(state.t("selectFile")) { state.browse(.input) }.disabled(!state.editable)
                if chosen { Button(state.t("clearSelection")) { state.clearFile() }.buttonStyle(.link).disabled(!state.editable) }
            }
        }
        Text(state.t("decryptKeyTitle")).font(.headline).padding(.top, 8)
        Card {
            VStack(alignment: .leading, spacing: 2) {
                Text(state.t("keySourceLine").replacingOccurrences(of: "{source}", with: state.t(other ? "keySourceChosen" : "myPrivateKey")))
                Text(state.t("keyFileLine").replacingOccurrences(of: "{file}", with: state.decryptKey.isEmpty ? state.t("notSet") : state.decryptKey))
                    .font(.callout).foregroundStyle(.secondary).lineLimit(2).truncationMode(.middle)
            }
            HStack(spacing: 12) {
                Button(state.t("selectOtherPrivateKey")) { state.browse(.otherPrivateKey) }.disabled(!state.editable)
                if other { Button(state.t("restoreDefaultPrivateKey")) { state.restoreDefaultKey() }.buttonStyle(.link).disabled(!state.editable) }
            }
        }
        if state.busy || state.unsaved || state.failure != nil || state.result != nil {
            Card {
                if state.busy {
                    ProgressBlock(state: state)
                    HStack {
                        Spacer()
                        Button(state.t("cancel")) { state.cancel() }.disabled(state.cancelling)
                    }
                }
                FormFeedback(state: state)
                if state.unsaved {
                    HStack {
                        Spacer()
                        Button(state.t("saveAs")) { state.saveAgain() }.primaryAction().disabled(!state.editable)
                    }
                }
            }
        }
    }
}

private struct ProgressBlock: View {
    @ObservedObject var state: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(state.t(state.cancelling ? "cancelling" : (state.progress != nil ? "progress" : "preparing")))
                Spacer()
                if let percent = state.percent { Text("\(percent)%").monospacedDigit() }
            }
            if let percent = state.percent { ProgressView(value: Double(percent), total: 100) } else { ProgressView().progressViewStyle(.linear) }
        }
    }
}

private struct KeysPage: View {
    @ObservedObject var state: AppState

    var body: some View {
        Card {
            if state.screen == .fingerprint {
                Picker("", selection: Binding(get: { state.pasteKey ? 1 : 0 }, set: { state.changeSource(paste: $0 == 1) })) {
                    Text(state.t("path")).tag(0)
                    Text(state.t("paste")).tag(1)
                }
                .pickerStyle(.segmented).labelsHidden().fixedSize().disabled(!state.editable)
                if state.pasteKey { PasteInput(state: state, label: state.t("paste"), name: "keyText", text: $state.keyText) }
                else { PathInput(state: state, field: .keyPath, label: state.t("key")) }
            } else if state.keyMode == .export {
                PathInput(state: state, field: .keyPath, label: state.t("publicKey"))
                PathInput(state: state, field: .output, label: state.t("legacyOutput"))
            } else {
                let upgrade = state.keyMode == .upgrade
                PathInput(state: state, field: .publicPath, label: state.t(upgrade ? "publicKey" : "publicPath"))
                PathInput(state: state, field: .privatePath, label: state.t(upgrade ? "privateKey" : "privatePath"))
                if state.replaceOffered {
                    Toggle(state.t("replaceKeys"), isOn: $state.replace).disabled(!state.editable)
                    Text(state.t("replaceKeysHint")).font(.callout).foregroundStyle(danger)
                }
            }
            OperationFooter(state: state)
        }
    }
}

/// Password, progress, the result and the action button: shared by every operation.
private struct OperationFooter: View {
    @ObservedObject var state: AppState

    var body: some View {
        if state.passwordRequired {
            SecretInput(state: state, label: state.t("password"), name: "password", text: $state.password)
            if state.screen == .keygen && state.keyMode == .generate {
                SecretInput(state: state, label: state.t("confirmation"), name: "confirmation", text: $state.confirmation)
            }
            KeychainOptions(state: state)
        }
        if state.busy { ProgressBlock(state: state) }
        UpgradeConfirm(state: state)
        if state.feedback == .form { FormFeedback(state: state) }
        HStack {
            Spacer()
            Button(actionTitle) { state.submit() }.primaryAction().keyboardShortcut(.defaultAction).disabled(!state.editable)
        }
    }

    private var actionTitle: String {
        if state.busy || state.contactBusy { return state.t("working") }
        switch state.screen {
        case .contacts: return state.t("saveContact")
        case .keygen:
            if state.keyMode == .upgrade { return state.t("upgradeKey") }
            if state.keyMode == .export { return state.t("exportLegacy") }
            return state.t(state.replace ? "replaceKeysAction" : "keygen")
        default: return state.t(state.screen.rawValue)
        }
    }
}

private func fileName(_ path: String) -> String { (path as NSString).lastPathComponent }

private struct FormFeedback: View {
    @ObservedObject var state: AppState

    var body: some View {
        if state.overwriteOffered {
            Toggle(state.t("overwriteOutput"), isOn: $state.overwrite).disabled(!state.editable)
            Text(state.t("overwriteOutputHint")).font(.callout).foregroundStyle(danger)
        }
        if let notice = state.keychainNotice, notice == "keychainSaved" || notice == "keychainNotSaved" {
            Text(state.t(notice)).font(.callout).foregroundStyle(notice == "keychainSaved" ? Color.secondary : danger)
        }
        // The recipient and password sheets show their own errors.
        if let failure = state.failure, state.invalidField != "contacts", state.invalidField != "password" {
            Notice(title: state.t(failure), warn: failure != "cancelled") {
                if let contact = state.failedContact { Detail(text: "\(state.t("recipient")): \(state.label(contact)) · \(contact.fingerprint)") }
            }
        }
        if let result = state.result {
            Notice(title: state.t(state.successMessage), warn: state.successMessage == "encryptedUnsigned") {
                if let output = result.output { Detail(text: output) }
                let names = (state.ownRecipient ? [state.t("myPublicKey")] : []) + state.otherRecipients.map(fileName) + state.recipients.map(state.label)
                if !names.isEmpty { Detail(text: "\(state.t(names.count > 1 ? "recipients" : "recipient")): \(names.joined(separator: ", "))") }
                if let fingerprint = result.fingerprint {
                    Detail(text: fingerprint, mono: true)
                    if FingerprintQr.isFingerprint(fingerprint) { Button(state.t("showQr")) { state.qrFingerprint = fingerprint } }
                }
                if let legacy = result.legacyFingerprint, legacy != result.fingerprint {
                    Detail(text: state.t("legacyFingerprint"))
                    Detail(text: legacy, mono: true)
                }
                if result.canVerify == false { Text(state.t("legacyKeyHint")).font(.callout).foregroundStyle(danger) }
            }
            if let signature = result.signature {
                let title = signature.status == "trusted" ? "signatureTrusted" : (signature.status == "untrusted" ? "signatureUntrusted" : "signatureNone")
                Notice(title: state.t(title), warn: signature.status != "trusted") {
                    switch signature.status {
                    case "trusted": Detail(text: "\(state.t("signer")): \(state.signerContact.map(state.label) ?? state.t("savedContact"))")
                    case "untrusted": Detail(text: state.t("signatureUntrustedHint"))
                    default: Detail(text: state.t("signatureNoneHint"))
                    }
                    if let fingerprint = signature.fingerprint { Detail(text: fingerprint, mono: true) }
                    if state.signerSaved { Detail(text: state.t("signerSaved")) }
                    else if signature.signerKey != nil { Button(state.t("saveSigner")) { state.saveSigner() }.disabled(state.locked) }
                }
            }
        }
    }
}

private struct UpgradeConfirm: View {
    @ObservedObject var state: AppState

    var body: some View {
        if let prompt = state.upgradePrompt {
            Notice(title: state.t("upgradeTitle").replacingOccurrences(of: "{name}", with: state.upgradeName), warn: true) {
                Text(state.t("upgradeText")).font(.callout).foregroundStyle(danger)
                Detail(text: prompt.fingerprint, mono: true)
                Toggle(state.t("upgradeCheck"), isOn: $state.upgradeChecked)
                HStack {
                    Spacer()
                    Button(state.t("cancel")) { state.confirmUpgrade(false) }
                    Button(state.t("upgradeAccept"), role: .destructive) { state.confirmUpgrade(true) }.disabled(!state.upgradeChecked || state.locked)
                }
            }
        }
    }
}

private struct ContactsPage: View {
    @ObservedObject var state: AppState

    var body: some View {
        Card {
            Picker("", selection: Binding(get: { state.contactPaste ? 1 : 0 }, set: { state.changeContactSource(paste: $0 == 1) })) {
                Text(state.t("path")).tag(0)
                Text(state.t("paste")).tag(1)
            }
            .pickerStyle(.segmented).labelsHidden().fixedSize().disabled(!state.editable)
            if state.contactPaste { PasteInput(state: state, label: state.t("paste"), name: "contactKeyText", text: $state.contactKeyText) }
            else { PathInput(state: state, field: .contactKeyPath, label: state.t("publicKey")) }
            VStack(alignment: .leading, spacing: 4) {
                FieldLabel(text: state.t("note"), invalid: state.invalidField == "note")
                TextField("", text: $state.note).textFieldStyle(.roundedBorder).disabled(!state.editable).accessibilityLabel(state.t("note"))
            }
            OperationFooter(state: state)
        }
        Text("\(state.t("savedContacts")) · \(state.contacts.count)").font(.headline).padding(.top, 8)
        Card {
            if let failure = state.contactsFailure { Notice(title: state.t(failure), warn: true) {} }
            if state.unreadable > 0 { Notice(title: state.t("contactUnreadable").replacingOccurrences(of: "{count}", with: String(state.unreadable))) {} }
            if state.feedback == .list {
                if let failure = state.failure { Notice(title: state.t(failure), warn: true) {} }
                if state.result != nil { Notice(title: state.t(state.successMessage)) {} }
            }
            if state.contactsLoaded && state.contactsFailure == nil && state.contacts.isEmpty { Text(state.t("noContacts")).foregroundStyle(.secondary) }
            ForEach(Array(state.contacts.enumerated()), id: \.element.id) { index, contact in
                if index > 0 { Divider() }
                ContactRow(state: state, contact: contact)
            }
        }
    }
}

private struct ContactRow: View {
    @ObservedObject var state: AppState
    var contact: Contact

    var body: some View {
        let chosen = state.selected.contains(contact.id)
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 12) {
                Toggle("", isOn: Binding(get: { chosen }, set: { _ in state.toggleContact(contact) }))
                    .toggleStyle(.checkbox).labelsHidden()
                    .disabled(state.locked || (!chosen && state.atRecipientLimit))
                    .accessibilityLabel("\(state.t("selectContact")) · \(state.label(contact))")
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(state.label(contact)).fontWeight(.semibold)
                        if contact.pinned { Text(state.t("pinnedMark")).font(.caption).foregroundStyle(.secondary) }
                    }
                    if !contact.note.isEmpty { Detail(text: "\(state.t("sourceFile")): \(contact.name.isEmpty ? state.t("pastedKey") : contact.name)") }
                    Detail(text: contact.fingerprint, mono: true)
                    if !contact.canVerify { Text(state.t("contactLegacy")).font(.caption).foregroundStyle(danger) }
                }
                Spacer()
                VerifiedMark(verified: contact.verified).font(.title3)
            }
            HStack(spacing: 12) {
                Spacer()
                if state.editing == contact.id {
                    TextField("", text: $state.noteDraft).textFieldStyle(.roundedBorder).disabled(state.locked)
                        .accessibilityLabel("\(state.t("note")) · \(state.label(contact))")
                    Button(state.t("saveNote")) { state.saveNote(contact) }.disabled(state.locked)
                    Button(state.t("cancel")) { state.cancelEdit() }.buttonStyle(.link).disabled(state.locked)
                } else if state.confirmingDelete == contact.id {
                    Text(state.t("deletePrompt"))
                    Button(state.t("confirmDelete"), role: .destructive) { state.removeContact(contact) }.disabled(state.locked)
                    Button(state.t("cancel")) { state.cancelDelete() }.buttonStyle(.link).disabled(state.locked)
                } else {
                    Button(state.t("useContact")) { state.useContact(contact) }.disabled(state.locked)
                    Menu {
                        Button(state.t(contact.verified ? "unmarkVerified" : "markVerified")) { state.toggleVerified(contact) }
                        Button(state.t(contact.pinned ? "unpinContact" : "pinContact")) { state.togglePin(contact) }
                        Button(state.t("showQr")) { state.qrFingerprint = contact.fingerprint }
                        Button(state.t("editNote")) { state.startEdit(contact) }
                        Divider()
                        Button(state.t("deleteContact"), role: .destructive) { state.askDelete(contact) }
                    } label: { Image(systemName: "ellipsis.circle") }
                    .menuStyle(.borderlessButton).fixedSize().disabled(state.locked)
                    .accessibilityLabel(state.label(contact))
                }
            }
        }
        .padding(.vertical, 4)
        .background(state.highlighted == contact.id ? Color.accentColor.opacity(0.12) : Color.clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct SettingRow<Content: View>: View {
    var title: String
    var detail = ""
    var warn = false
    @ViewBuilder var content: Content

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if !detail.isEmpty { Text(detail).font(.caption).foregroundStyle(warn ? danger : Color.secondary).lineLimit(2).truncationMode(.middle) }
            }
            Spacer()
            content
        }
    }
}

private struct SettingsPage: View {
    @ObservedObject var state: AppState

    var body: some View {
        Text(state.t("settingsGeneral")).font(.headline)
        Card {
            SettingRow(title: state.t("language")) {
                Picker("", selection: Binding(get: { state.selectedLanguage }, set: { state.changeLanguage($0) })) {
                    ForEach(Strings.choices, id: \.tag) { choice in Text(choice.tag == "system" ? state.t("system") : choice.name).tag(choice.tag) }
                }
                .labelsHidden().fixedSize().disabled(!state.editable)
            }
        }
        Text(state.t("settingsKeys")).font(.headline)
        Card {
            SettingRow(title: state.t("myPrivateKey"), detail: state.signKeyPath.isEmpty ? state.t("notSet") : state.signKeyPath) {
                Button(state.t("browse")) { state.browse(.signKeyPath) }.disabled(!state.editable)
            }
            Divider()
            SettingRow(title: state.t("myPublicKey"), detail: state.ownPublicKey.isEmpty ? state.t("notSet") : state.ownPublicKey) {
                Button(state.t("browse")) { state.browse(.ownPublicKey) }.disabled(!state.editable)
            }
        }
        Text(state.t("settingsSigning")).font(.headline)
        Card {
            SettingRow(title: state.t("unsignedSetting"), detail: state.unsigned ? state.t("unsignedWarning") : "", warn: true) {
                Toggle("", isOn: Binding(get: { state.unsigned }, set: { state.toggleUnsigned($0) })).toggleStyle(.switch).labelsHidden()
                    .accessibilityLabel(state.t("unsignedSetting"))
            }
            Divider()
            SettingRow(title: state.t("requireSignature")) {
                Toggle("", isOn: Binding(get: { state.requireSignature }, set: { state.changeRequireSignature($0) })).toggleStyle(.switch).labelsHidden()
                    .accessibilityLabel(state.t("requireSignature"))
            }
        }
        .alert(state.t("unsignedConfirmTitle"), isPresented: $state.confirmingUnsigned) {
            Button(state.t("unsignedConfirm"), role: .destructive) { state.confirmUnsigned(true) }
            Button(state.t("cancel"), role: .cancel) { state.confirmUnsigned(false) }
        } message: { Text(state.t("unsignedConfirmText")) }
        Text(state.t("settingsUpdates")).font(.headline)
        Card {
            SettingRow(title: state.t("autoUpdate")) {
                Toggle("", isOn: Binding(get: { state.autoUpdate }, set: { state.changeAutoUpdate($0) })).toggleStyle(.switch).labelsHidden()
                    .accessibilityLabel(state.t("autoUpdate"))
            }
            Divider()
            SettingRow(title: "NekoKEM v" + state.version, detail: state.updateNotice.map(state.t) ?? "") {
                Button(state.t(state.checkingUpdate ? "checkingUpdate" : "checkUpdate")) { state.checkForUpdate(manual: true) }.disabled(state.checkingUpdate)
            }
        }
    }
}

// MARK: Sheets

private struct RecipientRow: View {
    var title: String
    var detail: String
    var mark: String
    var checked: Bool
    var enabled: Bool
    var verified: Bool
    var toggle: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Toggle("", isOn: Binding(get: { checked }, set: { _ in toggle() })).toggleStyle(.checkbox).labelsHidden().disabled(!enabled).accessibilityLabel(title)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(title)
                    if !mark.isEmpty { Text(mark).font(.caption).foregroundStyle(.secondary) }
                }
                if !detail.isEmpty { Text(detail).font(.system(.caption, design: .monospaced)).foregroundStyle(.secondary) }
            }
            Spacer()
            VerifiedMark(verified: verified)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture { if enabled { toggle() } }
    }
}

/// Asked when Encrypt is pressed: own key first, then pinned contacts, then the rest. Nothing is ticked for the user.
private struct RecipientSheet: View {
    @ObservedObject var state: AppState

    var body: some View {
        let unverified = state.unverifiedChosen
        let warned = state.unverifiedWarned && !unverified.isEmpty
        VStack(alignment: .leading, spacing: 12) {
            Text(state.t("chooseRecipients")).font(.headline)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if !state.ownPublicKey.isEmpty {
                        RecipientRow(title: state.t("myPublicKey"), detail: fileName(state.ownPublicKey), mark: state.t("appDefault"), checked: state.ownSelected,
                                     enabled: !state.locked && (state.ownSelected || !state.atRecipientLimit), verified: false) { state.toggleOwn() }
                    }
                    ForEach(state.contacts) { contact in
                        let chosen = state.selected.contains(contact.id)
                        RecipientRow(title: state.label(contact), detail: String(contact.fingerprint.prefix(23)), mark: contact.pinned ? state.t("pinnedMark") : "",
                                     checked: chosen, enabled: !state.locked && (chosen || !state.atRecipientLimit), verified: contact.verified) { state.toggleContact(contact) }
                    }
                    ForEach(state.otherKeys, id: \.self) { path in
                        RecipientRow(title: fileName(path), detail: "", mark: state.t("otherKeyFile"), checked: true, enabled: !state.locked, verified: false) { state.removeOtherKey(path) }
                    }
                }
            }
            .frame(maxHeight: 300)
            Button(state.t("otherKeyFile")) { state.addOtherKey() }.buttonStyle(.link).disabled(state.locked || state.atRecipientLimit)
            if let failure = state.contactsFailure { Text(state.t(failure)).font(.callout).foregroundStyle(danger) }
            if state.invalidField == "contacts", let failure = state.failure { Text(state.t(failure)).font(.callout).foregroundStyle(danger) }
            if warned {
                Text(state.t("unverifiedMessage").replacingOccurrences(of: "{names}", with: unverified.map(state.label).joined(separator: ", ")))
                    .font(.callout).foregroundStyle(danger).fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                Text("\(state.recipientCount) / \(maxRecipients)").font(.callout).foregroundStyle(.secondary).monospacedDigit()
                Spacer()
                Button(state.t("cancel"), role: .cancel) { state.closeRecipients() }.keyboardShortcut(.cancelAction)
                if warned { Button(state.t("encryptAnyway"), role: .destructive) { state.confirmRecipients() }.disabled(!state.editable) }
                else { Button(state.t("encryptAction")) { state.confirmRecipients() }.primaryAction().keyboardShortcut(.defaultAction).disabled(!state.editable || state.recipientCount == 0) }
            }
        }
        .padding(20)
        .frame(width: 440)
    }
}

/// Asked after Encrypt (to sign) or Decrypt is pressed; the password opens the private key and is not kept.
private struct PasswordSheet: View {
    @ObservedObject var state: AppState

    var body: some View {
        let encrypting = state.fileAction == .encrypt
        VStack(alignment: .leading, spacing: 12) {
            Text(state.t("password")).font(.headline)
            if encrypting { Text(state.t("signPasswordHint")).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
            SecureField("", text: $state.password).textFieldStyle(.roundedBorder).accessibilityLabel(state.t("password"))
            if state.invalidField == "password", let failure = state.failure { Text(state.t(failure)).font(.callout).foregroundStyle(danger) }
            KeychainOptions(state: state)
            HStack {
                Spacer()
                Button(state.t("cancel"), role: .cancel) { state.cancelPassword() }.keyboardShortcut(.cancelAction)
                Button(state.t(encrypting ? "encryptAction" : "decryptAction")) { state.confirmPassword() }.primaryAction().keyboardShortcut(.defaultAction).disabled(!state.editable)
            }
        }
        .padding(20)
        .frame(width: 440)
    }
}

// The fingerprint QR code shared with Android and the other desktop apps:
// NEKOKEM-FP1: and the 64 hexadecimal digits, upper case, at level M.
enum FingerprintQr {
    static func isFingerprint(_ value: String) -> Bool {
        let parts = value.split(separator: ":", omittingEmptySubsequences: false)
        return parts.count == 32 && parts.allSatisfy { $0.count == 2 && $0.allSatisfy { "0123456789ABCDEF".contains($0) } }
    }
    static func image(_ fingerprint: String) -> NSImage? {
        guard isFingerprint(fingerprint), let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(("NEKOKEM-FP1:" + fingerprint.replacingOccurrences(of: ":", with: "")).utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
        let representation = NSCIImageRep(ciImage: output)
        let image = NSImage(size: representation.size)
        image.addRepresentation(representation)
        return image
    }
}

private struct QrSheet: View {
    @ObservedObject var state: AppState

    var body: some View {
        let fingerprint = state.qrFingerprint
        VStack(spacing: 12) {
            Text(state.t("qrTitle")).font(.headline)
            // Black on white in both themes, so cameras read it.
            if let image = FingerprintQr.image(fingerprint) {
                Image(nsImage: image).interpolation(.none).resizable().frame(width: 220, height: 220)
                    .padding(12).background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            VStack(spacing: 2) {
                Text(String(fingerprint.prefix(47)))
                Text(String(fingerprint.dropFirst(48)))
            }
            .font(.system(.callout, design: .monospaced)).textSelection(.enabled)
            Button(state.t("close")) { state.qrFingerprint = "" }.keyboardShortcut(.defaultAction)
        }
        .padding(20)
        .frame(width: 440)
    }
}

private struct UpdateSheet: View {
    @ObservedObject var state: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(state.t("updateAvailableTitle").replacingOccurrences(of: "{version}", with: state.availableVersion)).font(.headline)
            Text(state.t("updateAvailableText").replacingOccurrences(of: "{current}", with: state.version)).fixedSize(horizontal: false, vertical: true)
            HStack {
                Button(state.t("ignoreVersion")) { state.ignoreRelease() }.buttonStyle(.link)
                Spacer()
                Button(state.t("later"), role: .cancel) { state.availableVersion = "" }.keyboardShortcut(.cancelAction)
                Button(state.t("openRelease")) { state.openRelease() }.primaryAction().keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 440)
    }
}

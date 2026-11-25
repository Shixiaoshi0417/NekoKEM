import SwiftUI
import UIKit

// The iPhone and iPad pages, laid out as on Android and drawn with the
// system's own grouped lists, rows, switches and sheets. The state, the
// strings and the bridge are the macOS app's (macos/gui/Sources/NekoKEM).

/// The key tasks, each its own page under the Keys tab, as on Android.
enum KeyRoute: Hashable, CaseIterable {
    case generate, upgrade, export, fingerprint

    var key: String {
        switch self {
        case .generate: return "keygen"
        case .upgrade: return "upgradeKey"
        case .export: return "exportOlder"
        case .fingerprint: return "fingerprint"
        }
    }
    var symbol: String {
        switch self {
        case .generate: return "key"
        case .upgrade: return "arrow.up.circle"
        case .export: return "clock.arrow.circlepath"
        case .fingerprint: return "checkmark.shield"
        }
    }
}

extension AppState {
    /// Puts the shared state on the task a key page shows.
    func openKeyTask(_ route: KeyRoute) {
        switch route {
        case .fingerprint: switchTo(.fingerprint)
        case .generate: switchTo(.keygen); changeKeyMode(.generate)
        case .upgrade: switchTo(.keygen); changeKeyMode(.upgrade)
        case .export: switchTo(.keygen); changeKeyMode(.export)
        }
    }
}

private func fileName(_ path: String) -> String { (path as NSString).lastPathComponent }

/// The four pages are tabs, each with its own navigation stack.
struct ContentView: View {
    @ObservedObject var state: AppState

    var body: some View {
        TabView(selection: Binding<Destination>(get: { state.destination }, set: { destination in
            guard state.editable else { return }
            state.open(destination)
            // Back on the Keys tab, the shared state follows the page still open there.
            if destination == .keys, let route = state.keysPath.last { state.openKeyTask(route) }
        })) {
            NavigationStack { PhoneFilesPage(state: state) }
                .tabItem { Label(state.t(Destination.files.key), systemImage: Destination.files.symbol) }
                .tag(Destination.files)
            NavigationStack(path: $state.keysPath) { PhoneKeysPage(state: state) }
                .tabItem { Label(state.t(Destination.keys.key), systemImage: Destination.keys.symbol) }
                .tag(Destination.keys)
            NavigationStack(path: $state.contactPath) { PhoneContactsPage(state: state) }
                .tabItem { Label(state.t(Destination.contacts.key), systemImage: Destination.contacts.symbol) }
                .tag(Destination.contacts)
            NavigationStack { PhoneSettingsPage(state: state) }
                .tabItem { Label(state.t(Destination.settings.key), systemImage: Destination.settings.symbol) }
                .tag(Destination.settings)
        }
        // System sheets: half the screen, pulled up for more.
        .sheet(isPresented: Binding(get: { state.choosingRecipients || state.askingPassword }, set: { if !$0 { state.closeRecipients() } })) {
            Group { if state.askingPassword { PhonePasswordSheet(state: state) } else { PhoneRecipientSheet(state: state) } }
                .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
        }
        .sheet(isPresented: Binding(get: { !state.qrFingerprint.isEmpty }, set: { if !$0 { state.qrFingerprint = "" } })) {
            PhoneQrSheet(state: state).presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
        }
    }
}

// MARK: Rows

/// A row that opens something, drawn like a navigation row: title, the current value under it, a chevron.
struct ActionRow: View {
    var title: String
    var detail = ""
    var invalid = false
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(invalid ? danger : Color.primary)
                    if !detail.isEmpty { Text(detail).font(.footnote).foregroundStyle(.secondary).lineLimit(2).truncationMode(.middle) }
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// A file the operation reads opens the system picker; one it writes shows where it goes.
struct PathRow: View {
    @ObservedObject var state: AppState
    var field: PathField
    var label: String

    var body: some View {
        let value = state.path(field)
        let shownValue = value.isEmpty ? state.t("notSet") : shown(value)
        if state.saves(field) {
            LabeledContent {
                Text(shownValue).lineLimit(2).truncationMode(.middle)
            } label: {
                Text(label).foregroundStyle(state.invalidField == field.rawValue ? danger : Color.primary)
            }
        } else {
            ActionRow(title: label, detail: shownValue, invalid: state.invalidField == field.rawValue) { state.browse(field) }
                .disabled(!state.editable)
        }
    }
}

private struct SecretRow: View {
    @ObservedObject var state: AppState
    var label: String
    var name: String
    @Binding var text: String

    var body: some View {
        SecureField(label, text: $text)
            .textContentType(.password)
            .foregroundStyle(state.invalidField == name ? danger : Color.primary)
            .disabled(!state.editable)
            .accessibilityLabel(label)
    }
}

/// Save the password to Keychain once it is accepted, and fill in the one saved earlier.
private struct KeychainRows: View {
    @ObservedObject var state: AppState

    var body: some View {
        Toggle(state.t("keychainSave"), isOn: $state.saveToKeychain).disabled(!state.editable)
        if state.keychainFillOffered {
            Button(state.t("keychainFill")) { state.fillFromKeychain() }.disabled(!state.editable || state.keychainKeyPath.isEmpty)
        }
        if let notice = state.keychainNotice, notice == "keychainMissing" || notice == "keychainFailed" {
            Text(state.t(notice)).font(.footnote).foregroundStyle(.secondary)
        }
    }
}

/// The page's one action, full width under the form, as on Android.
private struct ActionSection: View {
    var title: String
    var enabled: Bool
    var action: () -> Void

    var body: some View {
        Section {
            Button(action: action) { Text(title).fontWeight(.semibold).frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large)
                .disabled(!enabled)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
        }
    }
}

private struct ProgressSection: View {
    @ObservedObject var state: AppState
    var cancellable: Bool

    var body: some View {
        if state.busy {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(state.t(state.cancelling ? "cancelling" : (state.progress != nil ? "progress" : "preparing")))
                        Spacer()
                        if let percent = state.percent { Text("\(percent)%").monospacedDigit().foregroundStyle(.secondary) }
                    }
                    if let percent = state.percent { ProgressView(value: Double(percent), total: 100) } else { ProgressView().progressViewStyle(.linear) }
                }
                .padding(.vertical, 4)
                if cancellable { Button(state.t("cancel"), role: .destructive) { state.cancel() }.disabled(state.cancelling) }
            }
        }
    }
}

/// An older-format key the user is about to trust: confirmed in place, as on the desktop.
struct UpgradeSection: View {
    @ObservedObject var state: AppState

    var body: some View {
        if let prompt = state.upgradePrompt {
            Section {
                Text(state.t("upgradeText")).font(.callout)
                Text(prompt.fingerprint).font(.system(.footnote, design: .monospaced)).textSelection(.enabled)
                Toggle(state.t("upgradeCheck"), isOn: $state.upgradeChecked)
                Button(state.t("upgradeAccept"), role: .destructive) { state.confirmUpgrade(true) }.disabled(!state.upgradeChecked || state.locked)
                Button(state.t("cancel")) { state.confirmUpgrade(false) }
            } header: {
                Text(state.t("upgradeTitle").replacingOccurrences(of: "{name}", with: state.upgradeName)).foregroundStyle(danger)
            }
        }
    }
}

/// The outcome of the last operation: a failure, or what was written and who can open it.
struct FeedbackSections: View {
    @ObservedObject var state: AppState

    var body: some View {
        if state.overwriteOffered {
            Section {
                Toggle(state.t("overwriteOutput"), isOn: $state.overwrite).disabled(!state.editable)
            } footer: { Text(state.t("overwriteOutputHint")).foregroundStyle(danger) }
        }
        if let notice = state.keychainNotice, notice == "keychainSaved" || notice == "keychainNotSaved" {
            Section { Text(state.t(notice)).foregroundStyle(notice == "keychainSaved" ? Color.secondary : danger) }
        }
        // The recipient and password sheets show their own errors while they are open;
        // the key pages ask for the password inline and show it here.
        let inSheet = (state.invalidField == "contacts" && state.choosingRecipients) || (state.invalidField == "password" && state.askingPassword)
        if let failure = state.failure, !inSheet {
            Section {
                Text(state.t(failure)).foregroundStyle(failure == "cancelled" ? Color.primary : danger)
                if let contact = state.failedContact {
                    LabeledContent(state.t("recipient"), value: state.label(contact))
                    Text(contact.fingerprint).font(.system(.footnote, design: .monospaced)).foregroundStyle(.secondary).textSelection(.enabled)
                }
            }
        }
        if let result = state.result {
            Section {
                if let output = result.output {
                    LabeledContent { Text(shown(output)).lineLimit(2).truncationMode(.middle) } label: { Text(state.t("selectedFile")) }
                    ShareLink(item: URL(fileURLWithPath: output)) { Label(state.t("share"), systemImage: "square.and.arrow.up") }
                }
                let names = (state.ownRecipient ? [state.t("myPublicKey")] : []) + state.otherRecipients.map(fileName) + state.recipients.map(state.label)
                if !names.isEmpty { LabeledContent(state.t(names.count > 1 ? "recipients" : "recipient"), value: names.joined(separator: ", ")) }
                if let fingerprint = result.fingerprint {
                    Text(fingerprint).font(.system(.callout, design: .monospaced)).textSelection(.enabled)
                    if FingerprintQr.isFingerprint(fingerprint) {
                        Button { state.qrFingerprint = fingerprint } label: { Label(state.t("showQr"), systemImage: "qrcode") }
                    }
                }
                if result.canVerify == false { Text(state.t("legacyKeyHint")).font(.footnote).foregroundStyle(danger) }
                if result.keyUpdated { Text(state.t("keyUpdated")).font(.footnote) }
            } header: {
                Text(state.t(state.successMessage)).foregroundStyle(state.successMessage == "encryptedUnsigned" ? danger : Color.secondary)
            }
            if let signature = result.signature {
                let title = signature.status == "trusted" ? "signatureTrusted" : (signature.status == "untrusted" ? "signatureUntrusted" : "signatureNone")
                Section {
                    switch signature.status {
                    case "trusted": LabeledContent(state.t("signer"), value: state.signerContact.map(state.label) ?? state.t("savedContact"))
                    case "untrusted": Text(state.t("signatureUntrustedHint")).font(.callout)
                    default: Text(state.t("signatureNoneHint")).font(.callout)
                    }
                    if let fingerprint = signature.fingerprint {
                        Text(fingerprint).font(.system(.footnote, design: .monospaced)).foregroundStyle(.secondary).textSelection(.enabled)
                    }
                    if state.signerSaved { Text(state.t("signerSaved")).foregroundStyle(.secondary) }
                    else if signature.signerKey != nil { Button(state.t("saveSigner")) { state.saveSigner() }.disabled(state.locked) }
                } header: {
                    Text(state.t(title)).foregroundStyle(signature.status == "trusted" ? Color.secondary : danger)
                }
            }
        }
    }
}

// MARK: Files

/// As on Android: the chosen file with Encrypt and Decrypt, then the private
/// key that decrypts. Recipients and the password are asked for in sheets.
private struct PhoneFilesPage: View {
    @ObservedObject var state: AppState

    var body: some View {
        let chosen = !state.input.isEmpty
        let other = !state.otherPrivateKey.isEmpty
        Form {
            Section {
                VStack(alignment: .leading, spacing: 2) {
                    Text(chosen ? fileName(state.input) : state.t("noFileSelected")).font(.headline).lineLimit(2)
                    Text(chosen ? shown(state.input) : state.t("selectedFile")).font(.subheadline).foregroundStyle(.secondary).lineLimit(2).truncationMode(.middle)
                }
                .padding(.vertical, 4)
                // Bordered buttons keep their own taps inside a list row.
                HStack(spacing: 12) {
                    Button { state.encrypt() } label: { Text(state.t("encryptAction")).frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent).disabled(!state.editable || !chosen)
                    Button { state.decrypt() } label: { Text(state.t("decryptAction")).frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered).disabled(!state.editable || !chosen)
                }
                .controlSize(.large)
                .padding(.vertical, 4)
                if state.unsigned { Text(state.t("unsignedWarning")).font(.footnote).foregroundStyle(danger) }
                ActionRow(title: state.t("selectFile")) { state.browse(.input) }.disabled(!state.editable)
                if chosen { Button(state.t("clearSelection")) { state.clearFile() }.disabled(!state.editable) }
            }
            Section {
                VStack(alignment: .leading, spacing: 2) {
                    Text(state.t("keySourceLine").replacingOccurrences(of: "{source}", with: state.t(other ? "keySourceChosen" : "myPrivateKey")))
                    Text(state.t("keyFileLine").replacingOccurrences(of: "{file}", with: state.decryptKey.isEmpty ? state.t("notSet") : shown(state.decryptKey)))
                        .font(.subheadline).foregroundStyle(.secondary).lineLimit(2).truncationMode(.middle)
                }
                .padding(.vertical, 2)
                ActionRow(title: state.t("selectOtherPrivateKey")) { state.browse(.otherPrivateKey) }.disabled(!state.editable)
                if other { Button(state.t("restoreDefaultPrivateKey")) { state.restoreDefaultKey() }.disabled(!state.editable) }
            } header: { Text(state.t("decryptKeyTitle")) }
            ProgressSection(state: state, cancellable: true)
            // Saving a signer that upgrades an older-format contact asks here.
            UpgradeSection(state: state)
            FeedbackSections(state: state)
            if state.unsaved { ActionSection(title: state.t("saveAs"), enabled: state.editable) { state.saveAgain() } }
        }
        .navigationTitle(state.t(Destination.files.key))
    }
}

// MARK: Keys

/// As on Android: the user's own keys, then one row for each key task.
private struct PhoneKeysPage: View {
    @ObservedObject var state: AppState

    var body: some View {
        Form {
            Section {
                LabeledContent(state.t("myPrivateKey"), value: state.signKeyPath.isEmpty ? state.t("notSet") : fileName(state.signKeyPath))
                LabeledContent(state.t("myPublicKey"), value: state.ownPublicKey.isEmpty ? state.t("notSet") : fileName(state.ownPublicKey))
            } header: { Text(state.t("settingsKeys")) }
            Section {
                ForEach(KeyRoute.allCases, id: \.self) { route in
                    NavigationLink(value: route) { Label(state.t(route.key), systemImage: route.symbol) }
                        .disabled(!state.editable)
                }
            }
        }
        .navigationTitle(state.t(Destination.keys.key))
        .navigationDestination(for: KeyRoute.self) { route in KeyTaskPage(state: state, route: route) }
    }
}

/// One key task as a form: the files, the password, the outcome, and the action.
private struct KeyTaskPage: View {
    @ObservedObject var state: AppState
    let route: KeyRoute

    var body: some View {
        Form {
            switch route {
            case .fingerprint: fingerprint
            case .export: export
            case .generate, .upgrade: keyPair
            }
            if state.passwordRequired {
                Section {
                    SecretRow(state: state, label: state.t("password"), name: "password", text: $state.password)
                    if route == .generate {
                        SecretRow(state: state, label: state.t("confirmation"), name: "confirmation", text: $state.confirmation)
                    }
                    KeychainRows(state: state)
                }
            }
            UpgradeSection(state: state)
            ProgressSection(state: state, cancellable: false)
            FeedbackSections(state: state)
            ActionSection(title: actionTitle, enabled: state.editable) { state.submit() }
        }
        .navigationTitle(state.t(route.key))
        .navigationBarTitleDisplayMode(.inline)
        .scrollDismissesKeyboard(.interactively)
        .onAppear { state.openKeyTask(route) }
    }

    @ViewBuilder private var keyPair: some View {
        let upgrade = route == .upgrade
        Section {
            PathRow(state: state, field: .publicPath, label: state.t(upgrade ? "publicKey" : "publicPath"))
            PathRow(state: state, field: .privatePath, label: state.t(upgrade ? "privateKey" : "privatePath"))
        }
        if state.replaceOffered {
            Section {
                Toggle(state.t("replaceKeys"), isOn: $state.replace).disabled(!state.editable)
            } footer: { Text(state.t("replaceKeysHint")).foregroundStyle(danger) }
        }
        if upgrade && state.legacyOffered {
            Section {
                Toggle(state.t("legacyConfirm") + (state.legacyWait > 0 ? " (\(state.legacyWait))" : ""), isOn: $state.legacyConfirmed)
                    .disabled(!state.editable || state.legacyWait > 0)
            }
        }
    }

    @ViewBuilder private var export: some View {
        Section {
            Picker("", selection: Binding(get: { state.exportPrivate ? 1 : 0 }, set: { state.changeExportPrivate($0 == 1) })) {
                Text(state.t("exportPublicOption")).tag(0)
                Text(state.t("exportPrivateOption")).tag(1)
            }
            .pickerStyle(.segmented).labelsHidden().disabled(!state.editable)
            .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
        }
        Section {
            if state.exportPrivate {
                PathRow(state: state, field: .privatePath, label: state.t("privateKey"))
                PathRow(state: state, field: .output, label: state.t("legacyPrivateOutput"))
            } else {
                PathRow(state: state, field: .keyPath, label: state.t("publicKey"))
                PathRow(state: state, field: .output, label: state.t("legacyOutput"))
            }
        }
    }

    @ViewBuilder private var fingerprint: some View {
        Section {
            Picker("", selection: Binding(get: { state.pasteKey ? 1 : 0 }, set: { state.changeSource(paste: $0 == 1) })) {
                Text(state.t("path")).tag(0)
                Text(state.t("paste")).tag(1)
            }
            .pickerStyle(.segmented).labelsHidden().disabled(!state.editable)
            .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
        }
        Section {
            if state.pasteKey { PasteRow(state: state, label: state.t("paste"), name: "keyText", text: $state.keyText) }
            else { PathRow(state: state, field: .keyPath, label: state.t("key")) }
        }
    }

    private var actionTitle: String {
        if state.busy { return state.t("working") }
        switch route {
        case .fingerprint: return state.t("fingerprint")
        case .upgrade: return state.t("upgradeKey")
        case .export: return state.t(state.exportPrivate ? "exportLegacyPrivate" : "exportLegacy")
        case .generate: return state.t(state.replace ? "replaceKeysAction" : "keygen")
        }
    }
}

/// Pasted key text, in a monospaced box under its label.
struct PasteRow: View {
    @ObservedObject var state: AppState
    var label: String
    var name: String
    @Binding var text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.subheadline).foregroundStyle(state.invalidField == name ? danger : Color.secondary)
            TextEditor(text: $text)
                .font(.system(.callout, design: .monospaced))
                .frame(minHeight: 96)
                .autocorrectionDisabled().textInputAutocapitalization(.never)
                .disabled(!state.editable)
                .accessibilityLabel(label)
        }
        .padding(.vertical, 4)
    }
}

// MARK: Settings

private struct PhoneSettingsPage: View {
    @ObservedObject var state: AppState

    var body: some View {
        Form {
            Section {
                Picker(state.t("language"), selection: Binding(get: { state.selectedLanguage }, set: { state.changeLanguage($0) })) {
                    ForEach(Strings.choices, id: \.tag) { choice in Text(choice.tag == "system" ? state.t("system") : choice.name).tag(choice.tag) }
                }
                .disabled(!state.editable)
            } header: { Text(state.t("settingsGeneral")) }
            Section {
                ActionRow(title: state.t("myPrivateKey"), detail: state.signKeyPath.isEmpty ? state.t("notSet") : shown(state.signKeyPath)) { state.browse(.signKeyPath) }
                    .disabled(!state.editable)
                ActionRow(title: state.t("myPublicKey"), detail: state.ownPublicKey.isEmpty ? state.t("notSet") : shown(state.ownPublicKey)) { state.browse(.ownPublicKey) }
                    .disabled(!state.editable)
            } header: { Text(state.t("settingsKeys")) }
            Section {
                Toggle(state.t("unsignedSetting"), isOn: Binding(get: { state.unsigned }, set: { state.toggleUnsigned($0) }))
                // Unsigned files never record a name, so the switch waits for signing.
                Toggle(state.t("recordName"), isOn: Binding(get: { state.recordName }, set: { state.changeRecordName($0) })).disabled(state.unsigned)
                Toggle(state.t("requireSignature"), isOn: Binding(get: { state.requireSignature }, set: { state.changeRequireSignature($0) }))
            } header: { Text(state.t("settingsSigning")) } footer: {
                if state.unsigned { Text(state.t("unsignedWarning")).foregroundStyle(danger) }
            }
            // iOS cannot ask GitHub for releases in the background: the release page opens instead.
            Section {
                LabeledContent("NekoKEM", value: "v" + state.version)
                Button(state.t("openRelease")) { state.openLatestRelease() }
            } header: { Text(state.t("settingsUpdates")) }
        }
        .navigationTitle(state.t(Destination.settings.key))
        .alert(state.t("unsignedConfirmTitle"), isPresented: $state.confirmingUnsigned) {
            Button(state.t("unsignedConfirm"), role: .destructive) { state.confirmUnsigned(true) }
            Button(state.t("cancel"), role: .cancel) { state.confirmUnsigned(false) }
        } message: { Text(state.t("unsignedConfirmText")) }
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
        Button(action: toggle) {
            HStack(spacing: 12) {
                Image(systemName: checked ? "checkmark.circle.fill" : "circle").font(.title3)
                    .foregroundStyle(checked ? Color.accentColor : Color.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text(title).foregroundStyle(.primary)
                        if !mark.isEmpty { Text(mark).font(.caption).foregroundStyle(.secondary) }
                    }
                    if !detail.isEmpty { Text(detail).font(.system(.caption, design: .monospaced)).foregroundStyle(.secondary).lineLimit(1) }
                }
                Spacer(minLength: 8)
                VerifiedMark(verified: verified)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityAddTraits(checked ? .isSelected : [])
    }
}

/// Asked when Encrypt is pressed: own key first, then pinned contacts, then the rest. Nothing is ticked for the user.
private struct PhoneRecipientSheet: View {
    @ObservedObject var state: AppState

    var body: some View {
        let unverified = state.unverifiedChosen
        let warned = state.unverifiedWarned && !unverified.isEmpty
        NavigationStack {
            List {
                Section {
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
                } footer: {
                    Text("\(state.recipientCount) / \(maxRecipients)").monospacedDigit()
                }
                Section {
                    Button { state.addOtherKey() } label: { Label(state.t("otherKeyFile"), systemImage: "doc.badge.plus") }
                        .disabled(state.locked || state.atRecipientLimit)
                }
                let failure = state.contactsFailure ?? (state.invalidField == "contacts" ? state.failure : nil)
                if failure != nil || warned {
                    Section {
                        if let failure { Text(state.t(failure)).foregroundStyle(danger) }
                        if warned {
                            Text(state.t("unverifiedMessage").replacingOccurrences(of: "{names}", with: unverified.map(state.label).joined(separator: ", ")))
                                .foregroundStyle(danger)
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(state.t("chooseRecipients"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(state.t("cancel")) { state.closeRecipients() } }
                ToolbarItem(placement: .confirmationAction) {
                    if warned { Button(state.t("encryptAnyway"), role: .destructive) { state.confirmRecipients() }.disabled(!state.editable) }
                    else { Button(state.t("encryptAction")) { state.confirmRecipients() }.fontWeight(.semibold).disabled(!state.editable || state.recipientCount == 0) }
                }
            }
        }
    }
}

/// Asked after Encrypt (to sign) or Decrypt is pressed; the password opens the private key and is not kept.
private struct PhonePasswordSheet: View {
    @ObservedObject var state: AppState
    @FocusState private var focused: Bool

    var body: some View {
        let encrypting = state.fileAction == .encrypt
        NavigationStack {
            Form {
                Section {
                    SecureField(state.t("password"), text: $state.password)
                        .textContentType(.password).focused($focused).submitLabel(.go)
                        .onSubmit { if state.editable { state.confirmPassword() } }
                } footer: {
                    if state.invalidField == "password", let failure = state.failure { Text(state.t(failure)).foregroundStyle(danger) }
                }
                Section { KeychainRows(state: state) }
            }
            .navigationTitle(state.t("password"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(state.t("cancel")) { state.cancelPassword() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(state.t(encrypting ? "encryptAction" : "decryptAction")) { state.confirmPassword() }.fontWeight(.semibold).disabled(!state.editable)
                }
            }
        }
    }
}

private struct PhoneQrSheet: View {
    @ObservedObject var state: AppState

    var body: some View {
        let fingerprint = state.qrFingerprint
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Black on white in both themes, so cameras read it.
                    if let image = FingerprintQr.image(fingerprint) {
                        Image(decorative: image, scale: 1).interpolation(.none).resizable().frame(width: 220, height: 220)
                            .padding(12).background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    VStack(spacing: 2) {
                        Text(String(fingerprint.prefix(47)))
                        Text(String(fingerprint.dropFirst(48)))
                    }
                    .font(.system(.footnote, design: .monospaced)).textSelection(.enabled)
                }
                .frame(maxWidth: .infinity)
                .padding(16)
            }
            .navigationTitle(state.t("qrTitle"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(state.t("close")) { state.qrFingerprint = "" } }
            }
        }
    }
}

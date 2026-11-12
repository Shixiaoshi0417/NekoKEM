import AppKit
import Foundation

enum Destination: String, CaseIterable, Identifiable {
    case files, keys, contacts, settings
    var id: String { rawValue }
    var key: String { switch self { case .files: return "navFiles"; case .keys: return "navKeys"; case .contacts: return "navContacts"; case .settings: return "settings" } }
    var symbol: String { switch self { case .files: return "doc.fill"; case .keys: return "key.fill"; case .contacts: return "person.2.fill"; case .settings: return "gearshape.fill" } }
}
enum Screen: String { case keygen, encrypt, decrypt, fingerprint, contacts, settings }
// The key page generates identity keys, upgrades a 4.x key pair or exports the
// two-component public key that NekoKEM 4.x and earlier read.
enum KeyMode: Int { case generate, upgrade, export }
enum PathField: String { case input, output, keyPath, publicPath, privatePath, contactKeyPath, signKeyPath, ownPublicKey }
enum Feedback { case form, list }

struct UpgradePrompt {
    var keyPath: String
    var keyText: String?
    var note: String
    var contact: String
    var fingerprint: String
    var fromSigner: Bool
}

/// The window's state and actions, step for step the same as the Compose
/// desktop app (desktop-compose AppState.kt), so the desktop apps behave alike.
@MainActor
final class AppState: ObservableObject {
    @Published private(set) var screen = Screen.encrypt
    @Published private(set) var language = "en"
    @Published private(set) var selectedLanguage = "system"
    @Published private(set) var version = "5.0.0"
    @Published private(set) var loaded = false
    @Published private(set) var busy = false
    @Published private(set) var cancelling = false
    private var cancelPending = false
    private var jobId = ""
    @Published private(set) var result: Outcome?
    @Published private(set) var successMessage = "success"
    @Published private(set) var failure: String?
    @Published private(set) var feedback = Feedback.form
    @Published private(set) var progress: (processed: UInt64, total: UInt64)?

    @Published var input = "" { didSet { edited("input") } }
    @Published var output = "" { didSet { if output != oldValue { withdrawOverwrite() }; edited("output") } }
    @Published var keyPath = "" { didSet { edited("keyPath") } }
    @Published var publicPath = "" { didSet { if publicPath != oldValue { withdrawReplace() }; edited("publicPath") } }
    @Published var privatePath = "" { didSet { if privatePath != oldValue { withdrawReplace() }; edited("privatePath") } }
    @Published var password = "" { didSet { edited("password") } }
    @Published var confirmation = "" { didSet { edited("confirmation") } }
    @Published var keyText = "" { didSet { edited("keyText") } }
    @Published private(set) var pasteKey = false
    // Saved-contact recipients, in selection order: shared by the Contacts page and the recipient sheet.
    @Published private(set) var selected: [String] = []
    @Published var replace = false
    @Published var overwrite = false
    @Published var contactKeyPath = "" { didSet { edited("contactKeyPath") } }
    @Published var contactKeyText = "" { didSet { edited("contactKeyText") } }
    @Published private(set) var contactPaste = false
    @Published var note = "" { didSet { edited("note") } }

    // Unsigned output is never saved: every launch signs again.
    @Published private(set) var unsigned = false
    @Published private(set) var requireSignature = false
    // The user's own key pair. The private key is the only default: it signs and
    // decrypts. The public key is offered first when choosing recipients, never ticked.
    @Published var signKeyPath = "" { didSet { edited("signKeyPath") } }
    @Published private(set) var ownPublicKey = ""
    // Encrypting asks for the recipients after the form is complete.
    @Published var choosingRecipients = false
    @Published private(set) var ownSelected = false
    // Public key files added in the sheet for this file alone; ticked when added.
    @Published private(set) var otherKeys: [String] = []
    // Unverified contacts are encrypted to only after a second, warned press.
    @Published private(set) var unverifiedWarned = false
    @Published private(set) var keyMode = KeyMode.generate
    // Turning off signing needs an explicit, red-marked confirmation.
    @Published var confirmingUnsigned = false

    private var update = UpdatePreferences()
    private var stored = DesktopPreferences()
    @Published private(set) var autoUpdate = true
    @Published private(set) var checkingUpdate = false
    @Published private(set) var updateNotice: String?
    @Published var availableVersion = ""
    @Published var qrFingerprint = ""
    @Published private(set) var signerSaved = false
    // Replacing keys is offered only after Core reported existing keys at the
    // chosen paths, must be ticked for each run and is withdrawn when they change.
    @Published private(set) var replaceOffered = false
    @Published private(set) var overwriteOffered = false
    @Published private(set) var invalidField: String?
    @Published private(set) var choosing = false
    @Published var contacts: [Contact] = []
    @Published private(set) var unreadable = 0
    @Published private(set) var contactsFailure: String?
    @Published private(set) var contactsLoaded = false
    @Published private(set) var contactBusy = false
    @Published private(set) var editing: String?
    @Published var noteDraft = "" { didSet { edited("noteDraft") } }
    @Published private(set) var confirmingDelete: String?
    @Published private(set) var highlighted: String?
    @Published private(set) var recipients: [Contact] = []
    @Published private(set) var ownRecipient = false
    @Published private(set) var otherRecipients: [String] = []
    // The saved contact that made the last encryption fail, named beside the error.
    @Published private(set) var failedContact: Contact?
    @Published private(set) var upgradePrompt: UpgradePrompt?
    @Published var upgradeChecked = false
    private var closeRequested = false
    /// Screenshots show fixed data and never touch the user's files.
    var demo = false

    var locked: Bool { busy || contactBusy || choosing }
    var editable: Bool { !locked && loaded }
    func t(_ key: String) -> String { Strings.get(key, language) }
    func label(_ contact: Contact) -> String { !contact.note.isEmpty ? contact.note : (!contact.name.isEmpty ? contact.name : t("pastedKey")) }

    var destination: Destination {
        switch screen {
        case .encrypt, .decrypt: return .files
        case .keygen, .fingerprint: return .keys
        case .contacts: return .contacts
        case .settings: return .settings
        }
    }
    var kind: Kind {
        switch screen {
        case .keygen: return keyMode == .generate ? .keygen : (keyMode == .upgrade ? .upgrade : .exportLegacy)
        case .encrypt: return .encrypt
        case .decrypt: return .decrypt
        default: return .fingerprint
        }
    }
    var signing: Bool { screen == .encrypt && !unsigned }
    var passwordRequired: Bool { (screen == .keygen && keyMode != .export) || screen == .decrypt || signing }
    var percent: Int? {
        guard let progress, progress.total > 0 else { return nil }
        return min(100, Int(progress.processed * 100 / progress.total))
    }
    var selectedContacts: [Contact] { selected.compactMap { id in contacts.first { $0.id == id } } }
    var recipientCount: Int { selected.count + otherKeys.count + (ownSelected ? 1 : 0) }
    var unverifiedChosen: [Contact] { selectedContacts.filter { !$0.verified } }
    var atRecipientLimit: Bool { recipientCount >= maxRecipients }
    var signerContact: Contact? { contacts.first { $0.id == result?.signature?.contact } }
    var upgradeName: String { contacts.first { $0.id == upgradePrompt?.contact }.map(label) ?? t("savedContact") }

    private static let contactFailures = ["contact-missing", "contact-invalid", "contact-mismatch", "contact-storage"]
    private static let day: Int64 = 24 * 60 * 60 * 1000

    /// Runs a blocking native call off the main thread.
    private func native<T>(_ work: @escaping @Sendable () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async { continuation.resume(with: Result { try work() }) }
        }
    }
    private func code(_ error: Error) -> String {
        guard let failure = error as? BridgeFailure, Strings.has(failure.code) else { return "internal" }
        return failure.code
    }
    private var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    func start() {
        Task {
            do {
                let settings = try await native { try Backend.settings() }
                language = settings.language; selectedLanguage = settings.selection; version = settings.version
                if let saved = try? await native({ try Backend.desktopPreferences() }) {
                    stored = saved
                    requireSignature = saved.requireSignature; signKeyPath = saved.signKeyPath; ownPublicKey = saved.publicKeyPath
                }
                loaded = true
                if ProcessInfo.processInfo.environment["NEKOKEM_NATIVE_STARTUP_EVIDENCE"] == "1" {
                    print("NekoKEM native startup: settings-ready"); fflush(stdout)
                }
            } catch { failure = code(error); return }
            if let saved = try? await native({ try Backend.updatePreferences() }) { update = saved; autoUpdate = saved.auto }
            if update.auto && (now - update.lastCheck >= Self.day || now < update.lastCheck) { checkForUpdate(manual: false) }
            await refreshContacts()
        }
    }

    private func storePreferences() {
        guard !demo else { return }
        stored.requireSignature = requireSignature; stored.signKeyPath = signKeyPath; stored.publicKeyPath = ownPublicKey
        let value = stored
        Task { _ = try? await native { try Backend.save(value) } }
    }
    private func storeUpdate() {
        guard !demo else { return }
        let value = update
        Task { do { try await native { try Backend.save(value) } } catch { updateNotice = code(error) } }
    }

    func changeRequireSignature(_ value: Bool) { requireSignature = value; storePreferences() }
    func changeAutoUpdate(_ value: Bool) { autoUpdate = value; update.auto = value; storeUpdate() }
    func toggleUnsigned(_ value: Bool) {
        if !value { unsigned = false; confirmingUnsigned = false; return }
        // The switch stays off until the warning is confirmed.
        confirmingUnsigned = true
    }
    func confirmUnsigned(_ enable: Bool) { unsigned = enable; confirmingUnsigned = false }

    func checkForUpdate(manual: Bool) {
        if checkingUpdate || demo { return }
        checkingUpdate = true
        if manual { updateNotice = nil }
        Task {
            let status = (try? await native { Backend.checkUpdate() }) ?? .failed
            checkingUpdate = false
            if status != .failed { update.lastCheck = now; storeUpdate() }
            if case .available(let found) = status, manual || found != update.ignored { availableVersion = found }
            else if manual { updateNotice = status == .current ? "upToDate" : "updateFailed" }
        }
    }
    func openRelease() {
        let target = availableVersion
        availableVersion = ""
        Task { do { try await native { try Backend.openReleasePage(target) } } catch { updateNotice = code(error) } }
    }
    func ignoreRelease() { update.ignored = availableVersion; availableVersion = ""; storeUpdate() }

    private func withdrawReplace() { replaceOffered = false; replace = false }
    private func withdrawOverwrite() { overwriteOffered = false; overwrite = false }

    func path(_ field: PathField) -> String {
        switch field {
        case .input: return input
        case .output: return output
        case .keyPath: return keyPath
        case .publicPath: return publicPath
        case .privatePath: return privatePath
        case .contactKeyPath: return contactKeyPath
        case .signKeyPath: return signKeyPath
        case .ownPublicKey: return ownPublicKey
        }
    }
    func setPath(_ field: PathField, _ value: String) {
        switch field {
        case .input: input = value
        case .output: output = value
        case .keyPath: keyPath = value
        case .publicPath: publicPath = value
        case .privatePath: privatePath = value
        case .contactKeyPath: contactKeyPath = value
        case .signKeyPath: signKeyPath = value; if screen == .settings { storePreferences() }
        case .ownPublicKey: ownPublicKey = value; storePreferences()
        }
    }
    /// Editing the field that an error points at clears the error.
    func edited(_ field: String) { if invalidField == field { invalidField = nil; failure = nil } }

    // Pasted contact text should be public, but is cleared like key text in
    // case a private key was pasted by mistake.
    func clearSecrets() { password = ""; confirmation = ""; keyText = ""; contactKeyText = "" }

    private func resetFeedback() {
        result = nil; failure = nil; invalidField = nil; progress = nil
        recipients = []; ownRecipient = false; otherRecipients = []; failedContact = nil; signerSaved = false
        upgradePrompt = nil
        feedback = .form; successMessage = "success"
    }

    func open(_ destination: Destination) {
        if destination == self.destination { return }
        switch destination {
        case .files: switchTo(.encrypt)
        case .keys: switchTo(.keygen)
        case .contacts: switchTo(.contacts)
        case .settings: switchTo(.settings)
        }
    }
    func switchTo(_ value: Screen) {
        if locked || !loaded || value == screen { return }
        clearSecrets()
        output = ""
        resetFeedback()
        editing = nil; confirmingDelete = nil; highlighted = nil
        withdrawReplace(); withdrawOverwrite()
        confirmingUnsigned = false; closeRecipientsNow()
        pasteKey = false
        // Decryption uses a key file, by default the user's own private key.
        if value == .decrypt { keyPath = signKeyPath } else if screen == .decrypt { keyPath = "" }
        screen = value
        if value == .encrypt || value == .decrypt || value == .contacts { Task { await refreshContacts() } }
    }
    func changeSource(paste: Bool) {
        if locked || !loaded || paste == pasteKey { return }
        keyText = ""; pasteKey = paste; resetFeedback()
    }
    func changeKeyMode(_ mode: KeyMode) {
        if locked || !loaded || mode == keyMode { return }
        password = ""; confirmation = ""
        output = ""
        keyMode = mode
        withdrawReplace(); withdrawOverwrite(); resetFeedback()
    }
    func changeContactSource(paste: Bool) {
        if locked || !loaded || paste == contactPaste { return }
        contactKeyText = ""; contactPaste = paste; resetFeedback()
    }

    private func fileName(_ path: String) -> String { (path as NSString).lastPathComponent }
    private func chooseOpen() -> String? {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true; panel.canChooseDirectories = false; panel.allowsMultipleSelection = false
        return panel.runModal() == .OK ? panel.url?.path : nil
    }
    private func chooseSave(_ name: String) -> String? {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = name
        return panel.runModal() == .OK ? panel.url?.path : nil
    }
    func browse(_ field: PathField) {
        if locked || !loaded { return }
        choosing = true
        defer { choosing = false }
        // Upgrading changes existing key files, so it opens them.
        let saving = [.output, .publicPath, .privatePath].contains(field) && !(screen == .keygen && keyMode == .upgrade)
        let value: String?
        if saving {
            let name = input.isEmpty ? "file" : fileName(input)
            switch field {
            case .publicPath: value = chooseSave("public.key")
            case .privatePath: value = chooseSave("private.key.enc")
            default:
                if screen == .keygen { value = chooseSave("legacy-public.key") }
                else if screen == .encrypt { value = chooseSave(name + ".nkem") }
                else { value = chooseSave(name.hasSuffix(".nkem") ? String(name.dropLast(5)) : name) }
            }
        } else { value = chooseOpen() }
        if let value { setPath(field, value) }
    }

    func changeLanguage(_ tag: String) {
        selectedLanguage = tag
        Task { do { language = try await native { try Backend.setLanguage(tag) } } catch { failure = code(error) } }
    }

    private func invalid(_ message: String, _ field: String) -> String { invalidField = field; return message }
    // Same rule as the native store: 512 code points, no control characters.
    private func invalidNote(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.unicodeScalars.count > 512 || text.unicodeScalars.contains { scalar in
            let value = scalar.value
            return value <= 8 || (0x0b...0x1f).contains(value) || (0x7f...0x9f).contains(value)
        }
    }

    private func validate() -> String? {
        invalidField = nil
        if screen == .contacts {
            if contactPaste { if contactKeyText.isEmpty || contactKeyText.utf8.count > 1_048_576 { return invalid("key-limit", "contactKeyText") } }
            else if contactKeyPath.isEmpty { return invalid("invalid-path", "contactKeyPath") }
            if invalidNote(note) { return invalid("note-limit", "note") }
            return nil
        }
        if password.utf8.count > 1024 { return invalid("password-limit", "password") }
        if confirmation.utf8.count > 1024 { return invalid("password-limit", "confirmation") }
        if screen == .keygen && keyMode == .export {
            if keyPath.isEmpty { return invalid("invalid-path", "keyPath") }
            if output.isEmpty { return invalid("invalid-path", "output") }
        } else if screen == .keygen {
            if publicPath.isEmpty { return invalid("invalid-path", "publicPath") }
            if privatePath.isEmpty { return invalid("invalid-path", "privatePath") }
            if password.isEmpty { return invalid("password-empty", "password") }
            if keyMode == .generate && password != confirmation { return invalid("password-mismatch", "confirmation") }
        } else if screen == .encrypt {
            if input.isEmpty { return invalid("invalid-path", "input") }
            if output.isEmpty { return invalid("invalid-path", "output") }
            // Signing is never skipped silently: it needs the sender's key and password.
            if signing {
                if signKeyPath.isEmpty { return invalid("sign-key-missing", "signKeyPath") }
                if password.isEmpty { return invalid("password-empty", "password") }
            }
            // The recipients are chosen afterwards, explicitly; none is ticked for the user.
            if choosingRecipients {
                if recipientCount == 0 { return invalid("contact-required", "contacts") }
                if recipientCount > maxRecipients { return invalid("recipient-limit", "contacts") }
                if selectedContacts.count != selected.count { return invalid("contact-missing", "contacts") }
                if ownSelected && ownPublicKey.isEmpty { return invalid("invalid-path", "contacts") }
            }
        } else {
            if pasteKey && screen == .fingerprint {
                if keyText.isEmpty || keyText.utf8.count > 1_048_576 { return invalid("key-limit", "keyText") }
            } else if keyPath.isEmpty { return invalid("invalid-path", "keyPath") }
            if screen != .fingerprint {
                if input.isEmpty { return invalid("invalid-path", "input") }
                if output.isEmpty { return invalid("invalid-path", "output") }
            }
        }
        return nil
    }

    func refreshContacts() async {
        if demo { contactsLoaded = true; return }
        do {
            let list = try await native { try Backend.contacts() }
            contacts = list.contacts; unreadable = list.unreadable; contactsFailure = nil
        } catch {
            contacts = []; unreadable = 0; contactsFailure = code(error)
        }
        contactsLoaded = true
        // A selected contact that disappeared is deselected and reported, never replaced.
        let listed = selected.filter { id in contacts.contains { $0.id == id } }
        if listed.count != selected.count && !busy {
            selected = listed
            if screen == .encrypt && choosingRecipients && failure == nil { failure = invalid("contact-missing", "contacts") }
        }
    }

    func submit() {
        if locked || !loaded { return }
        if screen == .contacts { saveContact(); return }
        if screen == .settings { return }
        resetFeedback()
        failure = validate()
        if failure != nil { return }
        if screen == .encrypt && !choosingRecipients { closeRecipientsNow(); choosingRecipients = true; return }
        if screen == .encrypt && !unverifiedChosen.isEmpty && !unverifiedWarned { unverifiedWarned = true; return }
        let encrypting = screen == .encrypt
        let chosen = encrypting ? selectedContacts : []
        let own = encrypting && ownSelected
        let others = encrypting ? otherKeys : []
        closeRecipientsNow()
        jobId = UUID().uuidString
        busy = true; cancelling = false
        let kind = self.kind
        let exporting = kind == .exportLegacy
        let keys = kind == .keygen || kind == .upgrade
        let pasting = kind == .fingerprint && pasteKey
        var request = OperationRequest(id: jobId, kind: kind)
        request.input = keys || exporting ? "" : input
        request.output = keys ? "" : output
        request.keyPath = encrypting ? (own ? ownPublicKey : "") : (exporting || (!keys && !pasting) ? keyPath : "")
        request.publicPath = keys ? publicPath : ""
        request.privatePath = keys ? privatePath : ""
        request.password = passwordRequired ? password : ""
        request.confirmation = kind == .keygen ? confirmation : ""
        request.keyText = pasting ? keyText : ""
        request.paste = pasting
        request.contacts = chosen.map(\.id)
        request.keyPaths = others
        request.replace = kind == .keygen && replaceOffered && replace
        request.overwrite = [.encrypt, .decrypt, .exportLegacy].contains(kind) && overwriteOffered && overwrite
        request.signKeyPath = signing ? signKeyPath : ""
        request.unsigned = encrypting && unsigned
        request.requireSignature = screen == .decrypt && requireSignature
        if signing { storePreferences() }
        withdrawReplace(); withdrawOverwrite()
        // The native layer keeps its own copy until it finishes; the fields are cleared now.
        clearSecrets()
        let id = jobId
        Task {
            var unavailable = false
            do {
                let sent = request
                let outcome = try await native { [weak self] in
                    try Backend.run(sent) { processed, total in
                        Task { @MainActor in
                            guard let self, self.busy, self.jobId == id else { return }
                            self.progress = (processed, total)
                        }
                    }
                }
                result = outcome
                recipients = chosen; ownRecipient = own; otherRecipients = others
                // Every encryption starts with nothing ticked.
                if encrypting { selected = [] }
                // A new or upgraded key pair becomes the user's own keys.
                if keys { ownPublicKey = request.publicPath; signKeyPath = request.privatePath; storePreferences() }
                switch kind {
                case .encrypt: successMessage = outcome.format == 5 ? "encryptedSigned" : "encryptedUnsigned"
                case .upgrade: successMessage = "keyUpgraded"
                case .exportLegacy: successMessage = "legacyExported"
                default: successMessage = "success"
                }
            } catch {
                let message = code(error)
                failure = message
                if kind == .keygen && message == "key-exists" { replaceOffered = true }
                if [.encrypt, .decrypt, .exportLegacy].contains(kind) && message == "output-exists" { overwriteOffered = true }
                // Nothing was encrypted. Deselect the contact that could not be used and
                // require the user to start again with the recipients that remain.
                unavailable = !chosen.isEmpty && Self.contactFailures.contains(message)
                if unavailable {
                    let failed = chosen.first { $0.id == (error as? BridgeFailure)?.contact }
                    failedContact = failed
                    if let failed { selected.removeAll { $0 == failed.id } } else { selected = [] }
                    invalidField = "contacts"
                }
            }
            busy = false; cancelling = false
            if unavailable { await refreshContacts() }
            closeIfRequested()
        }
    }

    private func saveContact() {
        resetFeedback()
        highlighted = nil
        failure = validate()
        if failure != nil { return }
        let paste = contactPaste
        let path = paste ? "" : contactKeyPath
        let text = paste ? contactKeyText : ""
        let noteText = note
        contactKeyText = ""
        contactBusy = true
        Task {
            do {
                let contact = try await native { try Backend.saveContact(keyPath: path, keyText: text, paste: paste, note: noteText) }
                contactKeyPath = ""; note = ""
                successMessage = "contactSaved"
                result = Outcome(fingerprint: contact.fingerprint)
                highlighted = contact.id
            } catch {
                if !askUpgrade(error, path: path, text: paste ? text : nil, note: noteText, fromSigner: false) {
                    let message = code(error)
                    failure = message
                    highlighted = (error as? BridgeFailure)?.contact
                    if message == "note-limit" { invalidField = "note" }
                    else if ["public-key-invalid", "invalid-path", "key-limit", "key-line-limit"].contains(message) { invalidField = paste ? "contactKeyText" : "contactKeyPath" }
                }
            }
            contactBusy = false
            await refreshContacts()
            closeIfRequested()
        }
    }

    // An identity key replacing a legacy contact waits for the user to check its
    // full fingerprint: anyone with the legacy key could attach signing keys.
    private func askUpgrade(_ error: Error, path: String, text: String?, note: String, fromSigner: Bool) -> Bool {
        guard let details = error as? BridgeFailure, details.code == "upgrade-unconfirmed",
              let contact = details.contact, let fingerprint = details.fingerprint else { return false }
        upgradePrompt = UpgradePrompt(keyPath: path, keyText: text, note: note, contact: contact, fingerprint: fingerprint, fromSigner: fromSigner)
        upgradeChecked = false
        return true
    }

    func confirmUpgrade(_ accept: Bool) {
        guard let prompt = upgradePrompt else { return }
        upgradePrompt = nil
        upgradeChecked = false
        if !accept || locked { return }
        contactBusy = true
        failure = nil
        Task {
            do {
                let contact = try await native {
                    try Backend.saveContact(keyPath: prompt.keyPath, keyText: prompt.keyText ?? "", paste: prompt.keyText != nil, note: prompt.note, confirmUpgrade: prompt.fingerprint)
                }
                if prompt.fromSigner { signerSaved = true }
                else {
                    contactKeyPath = ""; note = ""
                    successMessage = "contactSaved"
                    result = Outcome(fingerprint: contact.fingerprint)
                }
                highlighted = contact.id
            } catch { failure = code(error) }
            contactBusy = false
            await refreshContacts()
            closeIfRequested()
        }
    }

    // Saves the public key Core extracted from a valid signature. The user is
    // asked to compare the fingerprint first; later files then show as trusted.
    func saveSigner() {
        guard !locked, let key = result?.signature?.signerKey else { return }
        contactBusy = true
        failure = nil
        Task {
            do {
                let contact = try await native { try Backend.saveContact(keyPath: "", keyText: key, paste: true, note: "") }
                signerSaved = true
                highlighted = contact.id
            } catch {
                if !askUpgrade(error, path: "", text: key, note: "", fromSigner: true) {
                    failure = code(error)
                    if failure == "contact-exists" { failure = nil; signerSaved = true }
                }
            }
            contactBusy = false
            await refreshContacts()
            closeIfRequested()
        }
    }

    func startEdit(_ contact: Contact) {
        if locked { return }
        resetFeedback()
        confirmingDelete = nil
        editing = contact.id
        noteDraft = contact.note
    }
    func cancelEdit() { editing = nil }
    func askDelete(_ contact: Contact) {
        if locked { return }
        resetFeedback()
        editing = nil
        confirmingDelete = contact.id
    }
    func cancelDelete() { confirmingDelete = nil }

    /// One change to a saved contact, reported above the list.
    private func change(_ success: String?, _ work: @escaping @Sendable () throws -> Void, done: @escaping () -> Void = {}) {
        resetFeedback()
        feedback = .list
        contactBusy = true
        Task {
            do {
                try await native(work)
                if let success { successMessage = success; result = Outcome() }
                done()
            } catch { failure = code(error) }
            confirmingDelete = nil
            contactBusy = false
            await refreshContacts()
            closeIfRequested()
        }
    }
    func saveNote(_ contact: Contact) {
        if locked { return }
        if invalidNote(noteDraft) { resetFeedback(); feedback = .list; failure = invalid("note-limit", "noteDraft"); return }
        let draft = noteDraft
        change("noteSaved", { try Backend.updateNote(contact.id, draft) }) { [weak self] in self?.editing = nil; self?.highlighted = contact.id }
    }
    func removeContact(_ contact: Contact) { if !locked { change("contactDeleted", { try Backend.deleteContact(contact.id) }) } }
    func toggleVerified(_ contact: Contact) { if !locked { change(nil, { try Backend.setVerified(contact.id, contact.fingerprint, !contact.verified) }) } }
    func togglePin(_ contact: Contact) { if !locked { change(nil, { try Backend.setPinned(contact.id, !contact.pinned) }) } }

    func useContact(_ contact: Contact) {
        if locked { return }
        selected = [contact.id]
        switchTo(.encrypt)
    }
    func toggleContact(_ contact: Contact) {
        if locked { return }
        if selected.contains(contact.id) { selected.removeAll { $0 == contact.id } } else if !atRecipientLimit { selected.append(contact.id) }
        unverifiedWarned = false
        edited("contacts")
    }
    func encryptSelected() { if !locked && !selected.isEmpty { switchTo(.encrypt) } }
    func clearSelection() { if !locked { selected = [] } }
    func toggleOwn() {
        if !locked && (ownSelected || !atRecipientLimit) { ownSelected.toggle(); unverifiedWarned = false; edited("contacts") }
    }
    func closeRecipients() { if !locked { closeRecipientsNow(); invalidField = nil; failure = nil } }
    private func closeRecipientsNow() { choosingRecipients = false; ownSelected = false; otherKeys = []; unverifiedWarned = false }
    func removeOtherKey(_ path: String) { if !locked { otherKeys.removeAll { $0 == path } } }
    /// "Other public key file…": a one-off recipient for this file, never saved.
    func addOtherKey() {
        if locked || atRecipientLimit { return }
        choosing = true
        defer { choosing = false }
        if let value = chooseOpen(), value != ownPublicKey, !otherKeys.contains(value) { otherKeys.append(value); edited("contacts") }
    }

    func cancel() {
        if !busy || cancelling || cancelPending { return }
        let id = jobId
        cancelPending = true
        Task {
            let accepted = (try? await native { Bridge.cancel(id) }) ?? false
            if accepted && busy && jobId == id { cancelling = true }
            cancelPending = false
        }
    }

    /// The window's close button and Quit: a running operation is stopped and finishes first.
    func mayClose() -> Bool {
        if Bridge.deferClose() { closeRequested = true; return false }
        clearSecrets()
        return true
    }
    private func closeIfRequested() {
        if closeRequested && Bridge.closeReady() { clearSecrets(); NSApp.terminate(nil) }
    }

    // MARK: Fixed states for the screenshots

    func demoContacts() {
        func fake(_ seed: UInt8) -> String { (0..<32).map { String(format: "%02X", UInt8(truncatingIfNeeded: Int(seed) &+ $0 &* 37)) }.joined(separator: ":") }
        contacts = [
            Contact(id: "bob", fingerprint: fake(0x4b), name: "bob.key", note: "Bob", pinned: true),
            Contact(id: "alice", fingerprint: fake(0xa1), name: "alice.key", note: "Alice", verified: true),
            Contact(id: "carol", fingerprint: fake(0x1e), name: "carol.key", note: "Carol"),
        ]
        contactsLoaded = true
    }
    func showDemo(_ name: String) {
        demo = true; loaded = true
        signKeyPath = "/Users/shawn/Keys/private.key.enc"; ownPublicKey = "/Users/shawn/Keys/public.key"
        demoContacts()
        choosingRecipients = false; ownSelected = false; selected = []; otherKeys = []; unverifiedWarned = false
        result = nil; failure = nil; invalidField = nil
        input = ""; output = ""; password = ""
        switch name {
        case "encrypt", "encrypt-recipients", "encrypt-warning":
            screen = .encrypt
            input = "/Users/shawn/Documents/report.pdf"; output = "/Users/shawn/Documents/report.pdf.nkem"; password = "demo-password"
            if name != "encrypt" { choosingRecipients = true; ownSelected = true; selected = ["alice"] }
            if name == "encrypt-warning" { selected = ["alice", "carol"]; unverifiedWarned = true }
        case "decrypt":
            screen = .decrypt; keyPath = signKeyPath
            input = "/Users/shawn/Downloads/report.pdf.nkem"; output = "/Users/shawn/Downloads/report.pdf"
        case "keys": screen = .keygen; keyMode = .generate
        case "fingerprint":
            screen = .fingerprint; keyPath = ownPublicKey
            result = Outcome(fingerprint: contacts[1].fingerprint); result?.canVerify = true
        case "contacts": screen = .contacts
        default: screen = .settings
        }
    }
}

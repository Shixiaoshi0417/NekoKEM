import AVFoundation
import SwiftUI
import UIKit

/// The pages opened from the contact list, as on Android: a contact's own
/// page, and fingerprint verification under it.
enum ContactRoute: Hashable {
    case detail(String)
    case verify(String)
}

/// The fingerprint in groups of four hexadecimal digits, four groups a row (Android's grid).
private func fingerprintRows(_ fingerprint: String) -> [String] {
    let digits = Array(fingerprint.replacingOccurrences(of: ":", with: ""))
    let groups = stride(from: 0, to: digits.count, by: 4).map { String(digits[$0..<min($0 + 4, digits.count)]) }
    return stride(from: 0, to: groups.count, by: 4).map { groups[$0..<min($0 + 4, groups.count)].joined(separator: " ") }
}

/// The first two groups and the last one, enough to tell contacts apart at a glance.
private func shortFingerprint(_ fingerprint: String) -> String {
    let digits = Array(fingerprint.replacingOccurrences(of: ":", with: ""))
    let groups = stride(from: 0, to: digits.count, by: 4).map { String(digits[$0..<min($0 + 4, digits.count)]) }
    guard groups.count > 3 else { return groups.joined(separator: " ") }
    return groups.prefix(2).joined(separator: " ") + " … " + groups[groups.count - 1]
}

/// Saved contacts: one short row each, which opens the contact's page. Rows
/// swipe to pin or delete; the selection for encrypting sits above the list.
struct PhoneContactsPage: View {
    @ObservedObject var state: AppState
    @State private var adding = false
    @State private var deleting: Contact?

    var body: some View {
        List {
            ContactListStatus(state: state)
            if !state.selected.isEmpty {
                Section {
                    Text(state.t("selectedCount").replacingOccurrences(of: "{count}", with: String(state.selected.count))).font(.headline)
                    Button(state.t("encryptSelected")) { state.encryptSelected() }.disabled(state.locked)
                    Button(state.t("clearSelection")) { state.clearSelection() }.disabled(state.locked)
                }
            }
            if !state.contacts.isEmpty {
                Section {
                    ForEach(state.contacts) { contact in
                        ContactListRow(state: state, contact: contact)
                            .swipeActions(edge: .leading) {
                                Button { state.togglePin(contact) } label: {
                                    Label(state.t(contact.pinned ? "unpinContact" : "pinContact"), systemImage: contact.pinned ? "pin.slash" : "pin")
                                }
                                .tint(.orange)
                            }
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) { deleting = contact } label: { Label(state.t("deleteContact"), systemImage: "trash") }
                            }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(state.t("navContacts"))
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { adding = true } label: { Label(state.t("addContact"), systemImage: "plus") }.disabled(!state.editable)
            }
        }
        .navigationDestination(for: ContactRoute.self) { route in
            switch route {
            case .detail(let id): ContactDetailPage(state: state, id: id)
            case .verify(let id): ContactVerifyPage(state: state, id: id)
            }
        }
        .refreshable { await state.refreshContacts() }
        .sheet(isPresented: $adding) { AddContactSheet(state: state) { adding = false } }
        .confirmationDialog(state.t("deletePrompt"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button(state.t("confirmDelete"), role: .destructive) { if let contact = deleting { state.removeContact(contact) }; deleting = nil }
            Button(state.t("cancel"), role: .cancel) { deleting = nil }
        }
    }
}

/// Load failures, unreadable entries, the last change's outcome, or that nothing is saved yet.
private struct ContactListStatus: View {
    @ObservedObject var state: AppState

    var body: some View {
        let listFeedback = state.feedback == .list && (state.failure != nil || state.result != nil)
        let empty = state.contactsLoaded && state.contactsFailure == nil && state.contacts.isEmpty
        if state.contactsFailure != nil || state.unreadable > 0 || listFeedback || empty {
            Section {
                if let failure = state.contactsFailure { Text(state.t(failure)).foregroundStyle(danger) }
                if state.unreadable > 0 { Text(state.t("contactUnreadable").replacingOccurrences(of: "{count}", with: String(state.unreadable))).foregroundStyle(danger) }
                if listFeedback, let failure = state.failure { Text(state.t(failure)).foregroundStyle(danger) }
                else if listFeedback { Text(state.t(state.successMessage)) }
                if empty { Text(state.t("noContacts")).foregroundStyle(.secondary) }
            }
        }
    }
}

/// Selection, name, short fingerprint and the verification mark.
private struct ContactListRow: View {
    @ObservedObject var state: AppState
    var contact: Contact

    var body: some View {
        let chosen = state.selected.contains(contact.id)
        HStack(spacing: 12) {
            Toggle("", isOn: Binding(get: { chosen }, set: { _ in state.toggleContact(contact) }))
                .checkbox().labelsHidden()
                .disabled(state.locked || (!chosen && state.atRecipientLimit))
                .accessibilityLabel("\(state.t("selectContact")) · \(state.label(contact))")
            NavigationLink(value: ContactRoute.detail(contact.id)) {
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 8) {
                            Text(state.label(contact)).fontWeight(.semibold).lineLimit(1)
                            if contact.pinned { Text(state.t("pinnedMark")).font(.caption).foregroundStyle(Color.accentColor) }
                        }
                        Text(shortFingerprint(contact.fingerprint)).font(.system(.caption, design: .monospaced)).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    VerifiedMark(verified: contact.verified)
                }
            }
            .disabled(state.locked)
        }
    }
}

/// One contact: who it is, then what can be done with it.
private struct ContactDetailPage: View {
    @ObservedObject var state: AppState
    let id: String
    @State private var editingNote = false
    @State private var draft = ""
    @State private var confirmingDelete = false

    var body: some View {
        if let contact = state.contacts.first(where: { $0.id == id }) {
            let chosen = state.selected.contains(contact.id)
            List {
                Section {
                    HStack(alignment: .top, spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(state.label(contact)).font(.title2.weight(.semibold))
                            Text("\(state.t("sourceFile")): \(contact.name.isEmpty ? state.t("pastedKey") : contact.name)").font(.subheadline).foregroundStyle(.secondary)
                            Text(state.t(contact.canVerify ? "contactVerifies" : "contactLegacy")).font(.caption)
                                .foregroundStyle(contact.canVerify ? Color.accentColor : Color.secondary)
                        }
                        Spacer()
                        VerifiedMark(verified: contact.verified).font(.title2)
                    }
                    .padding(.vertical, 4)
                }
                if state.feedback == .list, let failure = state.failure {
                    Section { Text(state.t(failure)).foregroundStyle(danger) }
                }
                Section {
                    Toggle(state.t("selectContact"), isOn: Binding(get: { chosen }, set: { _ in state.toggleContact(contact) }))
                        .disabled(state.locked || (!chosen && state.atRecipientLimit))
                    NavigationLink(value: ContactRoute.verify(contact.id)) { Label(state.t("verifyFingerprint"), systemImage: "checkmark.shield") }
                    Button { state.useContact(contact) } label: { Label(state.t("useContact"), systemImage: "lock.doc") }
                        .disabled(!state.editable)
                    Button { draft = contact.note; editingNote = true } label: { Label(state.t("editNote"), systemImage: "pencil") }
                        .disabled(state.locked)
                    Button { state.togglePin(contact) } label: {
                        Label(state.t(contact.pinned ? "unpinContact" : "pinContact"), systemImage: contact.pinned ? "pin.slash" : "pin")
                    }
                    .disabled(state.locked)
                }
                Section {
                    Button(role: .destructive) { confirmingDelete = true } label: { Label(state.t("deleteContact"), systemImage: "trash") }
                        .disabled(state.locked)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(state.label(contact))
            .navigationBarTitleDisplayMode(.inline)
            .alert(state.t("editNote"), isPresented: $editingNote) {
                TextField(state.t("note"), text: $draft)
                Button(state.t("saveNote")) { state.noteDraft = draft; state.saveNote(contact) }
                Button(state.t("cancel"), role: .cancel) {}
            }
            .confirmationDialog(state.t("deletePrompt"), isPresented: $confirmingDelete, titleVisibility: .visible) {
                Button(state.t("confirmDelete"), role: .destructive) { state.removeContact(contact) }
                Button(state.t("cancel"), role: .cancel) {}
            }
        } else {
            // Deleted: back to the list.
            Color.clear.onAppear { state.contactPath = [] }
        }
    }
}

/// What a scanned code said about the contact.
private enum ScanOutcome: Identifiable {
    case match, mismatch(String), invalid
    var id: String {
        switch self {
        case .match: return "match"
        case .mismatch(let scanned): return "mismatch-" + scanned
        case .invalid: return "invalid"
        }
    }
}

/// The fingerprint card shared with Android and Signal: the QR code to scan,
/// the fingerprint in groups, and the button that marks it compared.
private struct ContactVerifyPage: View {
    @ObservedObject var state: AppState
    let id: String
    @State private var scanning = false
    @State private var outcome: ScanOutcome?

    var body: some View {
        if let contact = state.contacts.first(where: { $0.id == id }) {
            ScrollView {
                VStack(spacing: 0) {
                    Button { scanning = true } label: {
                        VStack(spacing: 12) {
                            if let image = FingerprintQr.image(contact.fingerprint) {
                                Image(decorative: image, scale: 1).interpolation(.none).resizable().aspectRatio(1, contentMode: .fit)
                            }
                            Text(state.t("scanFingerprint")).font(.subheadline).foregroundStyle(Color(white: 0.24))
                        }
                        .padding(EdgeInsets(top: 24, leading: 24, bottom: 16, trailing: 24))
                        .frame(maxWidth: 176)
                        .background(Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .disabled(state.locked)
                    VStack(spacing: 6) {
                        ForEach(fingerprintRows(contact.fingerprint), id: \.self) { row in Text(row) }
                    }
                    .font(.system(.title3, design: .monospaced))
                    .foregroundStyle(.white)
                    .textSelection(.enabled)
                    .padding(.top, 40).padding(.bottom, 32)
                    Button { state.setVerified(contact, !contact.verified) } label: {
                        Text(state.t(contact.verified ? "unmarkVerified" : "markVerified"))
                            .foregroundStyle(Color(white: 0.11))
                            .padding(.horizontal, 28).padding(.vertical, 16)
                            .background(Color.white, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(state.locked)
                }
                .frame(maxWidth: .infinity)
                .padding(EdgeInsets(top: 24, leading: 24, bottom: 24, trailing: 24))
                .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                .padding(16)
            }
            .navigationTitle(state.t("verifyFingerprint"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    ShareLink(item: contact.fingerprint) { Label(state.t("shareFingerprint"), systemImage: "square.and.arrow.up") }
                }
            }
            .fullScreenCover(isPresented: $scanning) {
                ScannerScreen(state: state, name: state.label(contact)) { text in
                    scanning = false
                    let result = Self.check(text, contact)
                    outcome = result
                    switch result {
                    case .match: state.setVerified(contact, true)
                    case .mismatch: state.setVerified(contact, false)
                    default: break
                    }
                } cancel: { scanning = false }
            }
            .alert(item: $outcome) { outcome in
                let name = state.label(contact)
                switch outcome {
                case .match:
                    return Alert(title: Text(state.t("scanMatchTitle")), message: Text(state.t("scanMatch").replacingOccurrences(of: "{name}", with: name)))
                case .mismatch(let scanned):
                    let lines = [state.t("scanMismatch").replacingOccurrences(of: "{name}", with: name),
                                 state.t("scanSavedLine").replacingOccurrences(of: "{name}", with: contact.fingerprint),
                                 state.t("scanScannedLine").replacingOccurrences(of: "{name}", with: scanned)]
                    return Alert(title: Text(state.t("scanMismatchTitle")), message: Text(lines.joined(separator: "\n\n")))
                case .invalid:
                    return Alert(title: Text(state.t("scanInvalidTitle")), message: Text(state.t("scanInvalid")))
                }
            }
        } else {
            Color.clear.onAppear { state.contactPath = [] }
        }
    }

    /// NEKOKEM-FP1: and 64 upper-case hexadecimal digits, as Android and the desktop apps show it.
    private static func check(_ text: String, _ contact: Contact) -> ScanOutcome {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let prefix = "NEKOKEM-FP1:"
        guard value.hasPrefix(prefix) else { return .invalid }
        let digits = Array(value.dropFirst(prefix.count))
        guard digits.count == 64, digits.allSatisfy({ "0123456789ABCDEF".contains($0) }) else { return .invalid }
        let scanned = stride(from: 0, to: 64, by: 2).map { String(digits[$0..<$0 + 2]) }.joined(separator: ":")
        return FingerprintQr.isFingerprint(contact.fingerprint) && scanned == contact.fingerprint.uppercased() ? .match : .mismatch(scanned)
    }
}

/// Adding a contact from a key file or pasted text, with an optional note.
private struct AddContactSheet: View {
    @ObservedObject var state: AppState
    let done: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("", selection: Binding(get: { state.contactPaste ? 1 : 0 }, set: { state.changeContactSource(paste: $0 == 1) })) {
                        Text(state.t("path")).tag(0)
                        Text(state.t("paste")).tag(1)
                    }
                    .pickerStyle(.segmented).labelsHidden().disabled(!state.editable)
                    .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                }
                Section {
                    if state.contactPaste { PasteRow(state: state, label: state.t("paste"), name: "contactKeyText", text: $state.contactKeyText) }
                    else { PathRow(state: state, field: .contactKeyPath, label: state.t("publicKey")) }
                    TextField(state.t("note"), text: $state.note)
                        .foregroundStyle(state.invalidField == "note" ? danger : Color.primary)
                        .disabled(!state.editable).accessibilityLabel(state.t("note"))
                }
                UpgradeSection(state: state)
                FeedbackSections(state: state)
            }
            .navigationTitle(state.t("addContact"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(state.t("cancel")) { done() }.disabled(state.locked) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(state.t(state.contactBusy ? "working" : "saveContact")) { state.submit() }.fontWeight(.semibold).disabled(!state.editable)
                }
            }
        }
        // Saved: back to the list, where the new contact is.
        .onChange(of: state.highlighted) { highlighted in
            if highlighted != nil && state.failure == nil && state.successMessage == "contactSaved" { done() }
        }
    }
}

/// The camera, full screen, until it reads one QR code.
private struct ScannerScreen: View {
    @ObservedObject var state: AppState
    let name: String
    let found: (String) -> Void
    let cancel: () -> Void
    @State private var problem: String?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if problem == nil {
                QrScanner(found: found) { denied in problem = denied ? "scanPermissionDenied" : "scanCameraUnavailable" }
                    .ignoresSafeArea()
            }
            VStack(spacing: 12) {
                HStack {
                    Button(state.t("cancel")) { cancel() }.foregroundStyle(.white)
                    Spacer()
                }
                Text(state.t("scanTitle")).font(.headline).foregroundStyle(.white)
                Text(state.t(problem ?? "scanHint").replacingOccurrences(of: "{name}", with: name))
                    .font(.subheadline).foregroundStyle(.white).multilineTextAlignment(.center)
                Spacer()
            }
            .padding(20)
        }
    }
}

/// AVFoundation's QR reader. The camera is asked for here, when a scan starts;
/// frames are read in memory and never stored.
private struct QrScanner: UIViewControllerRepresentable {
    let found: (String) -> Void
    let unavailable: (Bool) -> Void

    func makeUIViewController(context: Context) -> Controller {
        let controller = Controller()
        controller.found = found
        controller.unavailable = unavailable
        return controller
    }
    func updateUIViewController(_ controller: Controller, context: Context) {}

    final class Controller: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var found: ((String) -> Void)?
        var unavailable: ((Bool) -> Void)?
        private let session = AVCaptureSession()
        private var preview: AVCaptureVideoPreviewLayer?
        private var done = false

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: configure()
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    DispatchQueue.main.async { if granted { self.configure() } else { self.unavailable?(true) } }
                }
            default: unavailable?(true)
            }
        }

        private func configure() {
            guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input) else { unavailable?(false); return }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { unavailable?(false); return }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            layer.frame = view.bounds
            view.layer.addSublayer(layer)
            preview = layer
            let session = self.session
            DispatchQueue.global(qos: .userInitiated).async { session.startRunning() }
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            preview?.frame = view.bounds
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            let session = self.session
            DispatchQueue.global(qos: .userInitiated).async { session.stopRunning() }
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !done, let text = objects.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first else { return }
            done = true
            found?(text)
        }
    }
}

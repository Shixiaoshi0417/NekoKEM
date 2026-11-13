import Foundation

/// The commands of desktop/bridge/src/lib.rs, typed for Swift.
enum Kind: String { case keygen, encrypt, decrypt, fingerprint, upgrade, exportLegacy = "export-legacy" }

struct OperationRequest {
    var id: String
    var kind: Kind
    var input = "", output = "", keyPath = "", publicPath = "", privatePath = ""
    var password = "", confirmation = "", keyText = ""
    var paste = false
    var contacts: [String] = [], keyPaths: [String] = []
    var replace = false, overwrite = false
    var signKeyPath = ""
    var unsigned = false, requireSignature = false
    // Encryption and decryption write to a private staged file; saveOutput moves it to the chosen place.
    var stage = false

    var json: [String: Any] {
        ["id": id, "kind": kind.rawValue, "input": input, "output": output, "keyPath": keyPath,
         "publicPath": publicPath, "privatePath": privatePath, "password": password, "confirmation": confirmation,
         "keyText": keyText, "paste": paste, "contacts": contacts, "keyPaths": keyPaths, "replace": replace,
         "overwrite": overwrite, "signKeyPath": signKeyPath, "unsigned": unsigned, "requireSignature": requireSignature,
         "stage": stage]
    }
}

struct Signature { var status: String; var fingerprint: String?; var contact: String?; var signerKey: String? }

struct Outcome {
    var output: String? = nil
    var fingerprint: String? = nil
    var recipients: [String] = []
    var format: Int? = nil
    var signature: Signature? = nil
    var legacyFingerprint: String? = nil
    var canVerify: Bool? = nil

    init(fingerprint: String? = nil) { self.fingerprint = fingerprint }
    init(_ value: Any) {
        let fields = value as? [String: Any] ?? [:]
        output = fields["output"] as? String
        fingerprint = fields["fingerprint"] as? String
        recipients = fields["recipients"] as? [String] ?? []
        format = fields["format"] as? Int
        legacyFingerprint = fields["legacyFingerprint"] as? String
        canVerify = fields["canVerify"] as? Bool
        if let signed = fields["signature"] as? [String: Any] {
            signature = Signature(status: signed["status"] as? String ?? "unsigned", fingerprint: signed["fingerprint"] as? String,
                                  contact: signed["contact"] as? String, signerKey: signed["signerKey"] as? String)
        }
    }
}

struct Contact: Identifiable, Equatable {
    var id: String
    var fingerprint: String
    var name: String
    var note: String
    var canVerify: Bool
    var legacyFingerprint: String?
    var pinned = false
    var verified = false

    init(id: String, fingerprint: String, name: String, note: String, canVerify: Bool = true, pinned: Bool = false, verified: Bool = false) {
        self.id = id; self.fingerprint = fingerprint; self.name = name; self.note = note
        self.canVerify = canVerify; self.pinned = pinned; self.verified = verified
    }
    init(_ value: Any) {
        let fields = value as? [String: Any] ?? [:]
        id = fields["id"] as? String ?? ""
        fingerprint = fields["fingerprint"] as? String ?? ""
        name = fields["name"] as? String ?? ""
        note = fields["note"] as? String ?? ""
        canVerify = fields["canVerify"] as? Bool ?? false
        legacyFingerprint = fields["legacyFingerprint"] as? String
        pinned = fields["pinned"] as? Bool ?? false
        verified = fields["verified"] as? Bool ?? false
    }
}

enum UpdateStatus: Equatable { case available(String), current, failed }
struct UpdatePreferences { var auto = true; var lastCheck: Int64 = 0; var ignored = "" }
/// Kept in desktop.json beside the contacts. Unsigned output is never saved.
struct DesktopPreferences { var requireSignature = false; var signKeyPath = ""; var publicKeyPath = ""; var sidebarOpen = true; var style = "miuix" }

// Same limit as Core's NEKOKEM_MAX_RECIPIENTS for one NKEM v4/v5 file.
let maxRecipients = 64

/// Typed calls; each blocks, so they run off the main thread.
enum Backend {
    static func settings() throws -> (language: String, selection: String, version: String) {
        let fields = try Bridge.call("get_settings") as? [String: Any] ?? [:]
        return (fields["language"] as? String ?? "en", fields["selection"] as? String ?? "system", fields["version"] as? String ?? "")
    }
    static func setLanguage(_ tag: String) throws -> String {
        try Bridge.call(["set_language": ["language": tag]]) as? String ?? "en"
    }
    static func run(_ request: OperationRequest, progress: @escaping (UInt64, UInt64) -> Void) throws -> Outcome {
        Outcome(try Bridge.call(["run_operation": ["request": request.json]], progress: progress))
    }
    static func saveOutput(_ destination: String) throws { _ = try Bridge.call(["save_output": ["destination": destination]]) }
    static func discardOutput() throws { _ = try Bridge.call("discard_output") }
    static func contacts() throws -> (contacts: [Contact], unreadable: Int) {
        let fields = try Bridge.call("list_contacts") as? [String: Any] ?? [:]
        return ((fields["contacts"] as? [Any] ?? []).map(Contact.init), fields["unreadable"] as? Int ?? 0)
    }
    static func saveContact(keyPath: String, keyText: String, paste: Bool, note: String, confirmUpgrade: String = "") throws -> Contact {
        Contact(try Bridge.call(["save_contact": ["contact": ["keyPath": keyPath, "keyText": keyText, "paste": paste, "note": note, "confirmUpgrade": confirmUpgrade]]]))
    }
    static func updateNote(_ id: String, _ note: String) throws { _ = try Bridge.call(["update_contact_note": ["id": id, "note": note]]) }
    static func setPinned(_ id: String, _ pinned: Bool) throws { _ = try Bridge.call(["set_contact_pinned": ["id": id, "pinned": pinned]]) }
    static func setVerified(_ id: String, _ fingerprint: String, _ verified: Bool) throws {
        _ = try Bridge.call(["set_contact_verified": ["id": id, "fingerprint": fingerprint, "verified": verified]])
    }
    static func deleteContact(_ id: String) throws { _ = try Bridge.call(["delete_contact": ["id": id]]) }
    static func checkUpdate() -> UpdateStatus {
        guard let fields = try? Bridge.call("check_update") as? [String: Any] else { return .failed }
        switch fields["status"] as? String {
        case "available": return (fields["version"] as? String).map(UpdateStatus.available) ?? .failed
        case "current": return .current
        default: return .failed
        }
    }
    static func openReleasePage(_ version: String) throws { _ = try Bridge.call(["open_release_page": ["version": version]]) }
    static func updatePreferences() throws -> UpdatePreferences {
        let fields = try Bridge.call("update_preferences") as? [String: Any] ?? [:]
        return UpdatePreferences(auto: fields["auto"] as? Bool ?? true, lastCheck: (fields["lastCheck"] as? NSNumber)?.int64Value ?? 0, ignored: fields["ignored"] as? String ?? "")
    }
    static func save(_ value: UpdatePreferences) throws {
        _ = try Bridge.call(["save_update_preferences": ["preferences": ["auto": value.auto, "lastCheck": value.lastCheck, "ignored": value.ignored]]])
    }
    static func desktopPreferences() throws -> DesktopPreferences {
        let fields = try Bridge.call("desktop_preferences") as? [String: Any] ?? [:]
        return DesktopPreferences(requireSignature: fields["requireSignature"] as? Bool ?? false, signKeyPath: fields["signKeyPath"] as? String ?? "",
                                  publicKeyPath: fields["publicKeyPath"] as? String ?? "", sidebarOpen: fields["sidebarOpen"] as? Bool ?? true,
                                  style: fields["style"] as? String ?? "miuix")
    }
    static func save(_ value: DesktopPreferences) throws {
        _ = try Bridge.call(["save_desktop_preferences": ["preferences": ["requireSignature": value.requireSignature, "signKeyPath": value.signKeyPath,
            "publicKeyPath": value.publicKeyPath, "sidebarOpen": value.sidebarOpen, "style": value.style]]])
    }
}

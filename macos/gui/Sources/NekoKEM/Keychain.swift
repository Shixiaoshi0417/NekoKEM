import Foundation
import LocalAuthentication
import Security

/// The private key's password in the user's login keychain, when they ask for
/// it to be saved there. NekoKEM keeps no copy of its own: the item is a
/// generic password that Keychain Access lists under the service name below,
/// one per private key file. Reading it back first asks for Touch ID or the
/// login password. That check is NekoKEM's own: the item is in the login
/// keychain, which any process of the user can ask for, and macOS shows its
/// own access prompt for programs other than NekoKEM. The app is signed
/// ad hoc, so after an update macOS may ask again before NekoKEM reads it.
enum PrivateKeyKeychain {
    static let service = "NekoKEM private key password"

    /// Items are named by the private key file's full path. On iOS the path
    /// inside the app's container, which moves with each install.
    static func account(_ path: String) -> String {
        let full = URL(fileURLWithPath: path).standardizedFileURL.path
        #if os(iOS)
        let home = URL(fileURLWithPath: NSHomeDirectory()).standardizedFileURL.path
        if full.hasPrefix(home + "/") { return "~" + full.dropFirst(home.count) }
        #endif
        return full
    }

    private static func query(_ path: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account(path)]
    }

    /// Saves the password as a new item, replacing any saved for this key file.
    /// An existing item is never updated in place: another program of the same
    /// user could have created it first, with an access list that lets that
    /// program read it. A new item is readable only by NekoKEM until the user
    /// allows more. If an item comes back between the delete and the add, the
    /// save fails rather than write into it.
    static func save(_ path: String, _ password: String) -> Bool {
        var data = Data(password.utf8)
        defer { data.resetBytes(in: 0..<data.count) }
        let removed = SecItemDelete(query(path) as CFDictionary)
        if removed != errSecSuccess && removed != errSecItemNotFound { return false }
        var item = query(path)
        item[kSecValueData as String] = data
        item[kSecAttrLabel as String] = "NekoKEM (\((path as NSString).lastPathComponent))"
        // Never synced to iCloud Keychain.
        item[kSecAttrSynchronizable as String] = kCFBooleanFalse
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }

    enum Read { case password(String), missing, failed }

    static func read(_ path: String) -> Read {
        var item = query(path)
        item[kSecReturnData as String] = true
        item[kSecMatchLimit as String] = kSecMatchLimitOne
        var found: CFTypeRef?
        let status = SecItemCopyMatching(item as CFDictionary, &found)
        if status == errSecItemNotFound { return .missing }
        guard status == errSecSuccess, var data = found as? Data else { return .failed }
        defer { data.resetBytes(in: 0..<data.count) }
        guard let password = String(data: data, encoding: .utf8) else { return .failed }
        return .password(password)
    }

    /// Touch ID, Apple Watch or the login password. When none can be checked
    /// the password is not filled in, and the user types it.
    static func authenticate(_ reason: String) async -> Bool {
        let context = LAContext()
        var unavailable: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &unavailable) else { return false }
        return (try? await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
    }
}

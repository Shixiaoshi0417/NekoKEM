import Foundation
import LocalAuthentication
import Security

/// The private key's password in the user's login keychain, when they ask for
/// it to be saved there. NekoKEM keeps no copy of its own: the item is a
/// generic password that Keychain Access lists under the service name below,
/// one per private key file. Reading it back first asks for Touch ID or the
/// login password.
enum PrivateKeyKeychain {
    static let service = "NekoKEM private key password"

    /// Items are named by the private key file's full path.
    static func account(_ path: String) -> String { URL(fileURLWithPath: path).standardizedFileURL.path }

    private static func query(_ path: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account(path)]
    }

    /// Adds the password, or replaces the one saved for this key file.
    static func save(_ path: String, _ password: String) -> Bool {
        let data = Data(password.utf8)
        let update = SecItemUpdate(query(path) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return true }
        if update != errSecItemNotFound { return false }
        var item = query(path)
        item[kSecValueData as String] = data
        item[kSecAttrLabel as String] = "NekoKEM (\((path as NSString).lastPathComponent))"
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
        guard status == errSecSuccess, let data = found as? Data, let password = String(data: data, encoding: .utf8) else { return .failed }
        return .password(password)
    }

    /// Touch ID, Apple Watch or the login password. A Mac that can check none
    /// of them is left to the keychain's own protection.
    static func authenticate(_ reason: String) async -> Bool {
        let context = LAContext()
        var unavailable: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &unavailable) else { return true }
        return (try? await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
    }
}

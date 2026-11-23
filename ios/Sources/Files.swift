import Foundation
import UIKit
import UniformTypeIdentifiers

/// Where the iOS app keeps its files. Its Documents folder is the one the Files
/// app shows under On My iPhone › NekoKEM: what NekoKEM writes goes there, and
/// keys in Keys. Settings and contacts stay in Application Support.
enum AppFiles {
    static var documents: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }
    static var keys: URL { documents.appendingPathComponent("Keys", isDirectory: true) }
    private static var support: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0] }

    /// Before the first call into the bridge: the bridge finds its settings
    /// under XDG_CONFIG_HOME, as on Linux, and stages keys under
    /// XDG_RUNTIME_DIR, which must be a folder nobody else can enter.
    static func prepare() {
        let manager = FileManager.default
        for folder in [support, keys] { try? manager.createDirectory(at: folder, withIntermediateDirectories: true) }
        setenv("XDG_CONFIG_HOME", support.path, 1)
        let runtime = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true).appendingPathComponent("nekokem-runtime", isDirectory: true)
        try? manager.createDirectory(at: runtime, withIntermediateDirectories: true)
        if chmod(runtime.path, 0o700) == 0 { setenv("XDG_RUNTIME_DIR", runtime.path, 1) }
    }

    /// A new key file in Keys, under its usual name: an existing one is offered for replacing.
    static func keyPath(_ name: String) -> String { keys.appendingPathComponent(name).path }

    /// A name in Documents that is not taken yet: "report.pdf", then "report 2.pdf" and so on.
    static func freePath(_ name: String) -> String {
        let file = name as NSString
        let base = file.deletingPathExtension, suffix = file.pathExtension
        var number = 1
        while true {
            let candidate = number == 1 ? name : (suffix.isEmpty || base.isEmpty ? "\(name) \(number)" : "\(base) \(number).\(suffix)")
            let path = documents.appendingPathComponent(candidate).path
            if !FileManager.default.fileExists(atPath: path) { return path }
            number += 1
        }
    }

    /// Each install moves the app's container. A path saved by an earlier install
    /// is taken to the same place in this one.
    static func relocated(_ path: String) -> String {
        guard !path.isEmpty, !FileManager.default.fileExists(atPath: path) else { return path }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        guard let index = parts.firstIndex(of: "Application"), index > 0, parts[index - 1] == "Data", index + 2 < parts.count else { return path }
        let moved = ([NSHomeDirectory()] + parts[(index + 2)...].map(String.init)).joined(separator: "/")
        return FileManager.default.fileExists(atPath: moved) ? moved : path
    }

    /// Inside Documents, the path the Files app shows; anything else by its name.
    static func display(_ path: String) -> String {
        let root = documents.standardizedFileURL.path + "/"
        let full = URL(fileURLWithPath: path).standardizedFileURL.path
        return full.hasPrefix(root) ? String(full.dropFirst(root.count)) : (path as NSString).lastPathComponent
    }

    /// Moves a file the picker copied into a new folder of its own. Keys go to
    /// Application Support and stay; a file to encrypt or decrypt to the
    /// temporary folder, which the system empties.
    fileprivate static func adopt(_ picked: URL, keep: Bool) -> String? {
        let manager = FileManager.default
        let parent = keep ? support.appendingPathComponent("Imported", isDirectory: true)
                          : URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true).appendingPathComponent("Picked", isDirectory: true)
        let folder = parent.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let target = folder.appendingPathComponent(picked.lastPathComponent)
        do {
            try manager.createDirectory(at: folder, withIntermediateDirectories: true)
            try manager.moveItem(at: picked, to: target)
        } catch {
            do { try manager.copyItem(at: picked, to: target) } catch { return nil }
        }
        // Readable by NekoKEM alone, as a private key must be.
        _ = chmod(folder.path, 0o700)
        _ = chmod(target.path, 0o600)
        return target.path
    }
}

/// The system's document picker, opened over the app and awaited.
@MainActor
enum DocumentPicker {
    private static var current: Delegate?

    private final class Delegate: NSObject, UIDocumentPickerDelegate {
        let keep: Bool
        var continuation: CheckedContinuation<String?, Never>?
        init(keep: Bool) { self.keep = keep }
        func finish(_ value: String?) {
            continuation?.resume(returning: value)
            continuation = nil
            DocumentPicker.current = nil
        }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            finish(urls.first.flatMap { AppFiles.adopt($0, keep: keep) })
        }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finish(nil) }
    }

    /// The chosen file, copied into the app; nil when the picker was closed.
    static func open(keep: Bool) async -> String? {
        guard current == nil, let presenter = topController() else { return nil }
        return await withCheckedContinuation { continuation in
            let delegate = Delegate(keep: keep)
            delegate.continuation = continuation
            current = delegate
            // A copy: the original stays where it is, and nothing needs the picker's access afterwards.
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
            picker.allowsMultipleSelection = false
            picker.delegate = delegate
            presenter.present(picker, animated: true)
        }
    }

    private static func topController() -> UIViewController? {
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }
        var controller = window?.rootViewController
        while let presented = controller?.presentedViewController { controller = presented }
        return controller
    }
}

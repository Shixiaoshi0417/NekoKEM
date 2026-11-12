import CNekoKEM
import Foundation

/// A failure reported by the native layer; `code` is a key of the string catalog.
struct BridgeFailure: Error {
    let code: String
    var contact: String? = nil
    var fingerprint: String? = nil
}

// Core runs one operation at a time, so one handler is enough.
private let progressLock = NSLock()
private var progressHandler: ((UInt64, UInt64) -> Void)?
private let progressCallback: nekokem_desktop_progress = { processed, total in
    progressLock.lock()
    let handler = progressHandler
    progressLock.unlock()
    handler?(processed, total)
}

/// The Rust bridge: every call is one JSON request and one JSON reply.
enum Bridge {
    /// Blocks until Core finishes: call it off the main thread.
    static func call(_ request: Any, progress: ((UInt64, UInt64) -> Void)? = nil) throws -> Any {
        var body = try JSONSerialization.data(withJSONObject: request, options: [.fragmentsAllowed])
        // The request can hold a password: wipe this copy once the native layer has read it.
        defer { body.resetBytes(in: 0..<body.count) }
        if progress != nil {
            progressLock.lock(); progressHandler = progress; progressLock.unlock()
        }
        defer {
            if progress != nil { progressLock.lock(); progressHandler = nil; progressLock.unlock() }
        }
        let reply: UnsafeMutablePointer<CChar>? = body.withUnsafeBytes { bytes in
            nekokem_desktop_call(bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count, progress == nil ? nil : progressCallback)
        }
        guard let reply else { throw BridgeFailure(code: "internal") }
        defer { nekokem_desktop_free(reply) }
        let data = Data(bytes: reply, count: strlen(reply))
        guard let object = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) as? [String: Any] else {
            throw BridgeFailure(code: "internal")
        }
        if let error = object["error"] as? [String: Any] {
            throw BridgeFailure(code: error["code"] as? String ?? "internal", contact: error["contact"] as? String, fingerprint: error["fingerprint"] as? String)
        }
        return object["ok"] ?? NSNull()
    }

    static func cancel(_ id: String) -> Bool { id.withCString { nekokem_desktop_cancel($0) } != 0 }
    static func deferClose() -> Bool { nekokem_desktop_defer_close() != 0 }
    static func closeReady() -> Bool { nekokem_desktop_close_ready() != 0 }
}

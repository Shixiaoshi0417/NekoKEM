import SwiftUI
import UIKit

/// The iOS app: the same pages, state and Rust bridge as the macOS app
/// (macos/gui/Sources/NekoKEM), shown as tabs.
///   --demo <page>   fixed example data on one page, for the CI screenshots
///   --landscape     with --demo: turns the window to landscape first (iPad)
///   --self-test     generates keys, encrypts and decrypts a file in a temporary
///                   folder through the bridge, prints the outcome and exits
@main
struct NekoKEMApp: App {
    @StateObject private var state: AppState
    private var demo: String?

    init() {
        AppFiles.prepare()
        let state = AppState()
        _state = StateObject(wrappedValue: state)
        let arguments = CommandLine.arguments
        if let index = arguments.firstIndex(of: "--demo"), index + 1 < arguments.count {
            demo = arguments[index + 1]
        } else if arguments.contains("--self-test") {
            SelfTest.start()
        } else {
            state.start()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView(state: state)
                // A sheet asked for before the window is up is not presented: the page comes first.
                .task {
                    guard let demo else { return }
                    if CommandLine.arguments.contains("--landscape"),
                       let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
                        scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeRight)) { _ in }
                    }
                    try? await Task.sleep(nanoseconds: 500_000_000)
                    state.showDemo(demo)
                }
        }
    }
}

/// Checks on a simulator that Core works inside an iOS app's container: its
/// staging folders, private file checks and the bridge's settings.
enum SelfTest {
    static func start() {
        DispatchQueue.global(qos: .userInitiated).async {
            let message: String
            do { try run(); message = "NekoKEM iOS self-test: ok" } catch let failure as BridgeFailure {
                message = "NekoKEM iOS self-test: failed (\(failure.code))"
            } catch {
                message = "NekoKEM iOS self-test: failed (\(error))"
            }
            print(message)
            fflush(stdout)
            exit(message.hasSuffix(": ok") ? 0 : 1)
        }
    }

    private struct Mismatch: Error, CustomStringConvertible {
        let description: String
    }

    private static func run() throws {
        let manager = FileManager.default
        let folder = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true).appendingPathComponent("self-test-\(UUID().uuidString)", isDirectory: true)
        try manager.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? manager.removeItem(at: folder) }
        func path(_ name: String) -> String { folder.appendingPathComponent(name).path }

        _ = try Backend.settings()
        _ = try Backend.contacts()
        let password = "self-test-password"
        var keygen = OperationRequest(id: UUID().uuidString, kind: .keygen)
        keygen.publicPath = path("public.key"); keygen.privatePath = path("private.key.enc")
        keygen.password = password; keygen.confirmation = password
        _ = try Backend.run(keygen) { _, _ in }

        var plain = Data(count: 300_000)
        for index in plain.indices { plain[index] = UInt8(truncatingIfNeeded: index &* 31 &+ 7) }
        try plain.write(to: URL(fileURLWithPath: path("report.pdf")))

        var encrypt = OperationRequest(id: UUID().uuidString, kind: .encrypt)
        encrypt.input = path("report.pdf"); encrypt.keyPath = keygen.publicPath
        encrypt.signKeyPath = keygen.privatePath; encrypt.password = password; encrypt.stage = true
        let encrypted = try Backend.run(encrypt) { _, _ in }
        try Backend.saveOutput(path("report.pdf.nkem"))
        guard encrypted.format == 5 else { throw Mismatch(description: "format \(String(describing: encrypted.format))") }

        var decrypt = OperationRequest(id: UUID().uuidString, kind: .decrypt)
        decrypt.input = path("report.pdf.nkem"); decrypt.keyPath = keygen.privatePath
        decrypt.password = password; decrypt.stage = true
        let decrypted = try Backend.run(decrypt) { _, _ in }
        try Backend.saveOutput(path("decrypted.pdf"))
        guard decrypted.signature != nil else { throw Mismatch(description: "no signature status") }
        guard try Data(contentsOf: URL(fileURLWithPath: path("decrypted.pdf"))) == plain else { throw Mismatch(description: "decrypted bytes differ") }
    }
}

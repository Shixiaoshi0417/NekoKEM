// swift-tools-version:5.9
// The native macOS app: SwiftUI over the same Rust bridge and C17 Core as the
// Compose desktop app. scripts/build-app.sh builds the bridge's static library
// and passes its directory in NEKOKEM_BRIDGE_DIR.
import Foundation
import PackageDescription

let bridge = ProcessInfo.processInfo.environment["NEKOKEM_BRIDGE_DIR"] ?? "../../desktop/bridge/target/aarch64-apple-darwin/release/static"

let package = Package(
    name: "NekoKEM",
    platforms: [.macOS(.v13)],
    targets: [
        .target(name: "CNekoKEM"),
        .executableTarget(
            name: "NekoKEM",
            dependencies: ["CNekoKEM"],
            linkerSettings: [
                .unsafeFlags(["-L", bridge]),
                .linkedLibrary("nekokem_native"),
                .linkedLibrary("iconv"),
                .linkedFramework("CoreFoundation"),
            ]
        ),
    ]
)

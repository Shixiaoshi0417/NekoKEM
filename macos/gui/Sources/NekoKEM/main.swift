import AppKit
import SwiftUI

/// AppKit owns the window so that closing waits for a running operation and
/// the screenshot run can drive the same window the user sees.
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    private let state = AppState()
    private var window: NSWindow!
    private let screenshots: String?

    init(screenshots: String?) { self.screenshots = screenshots }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.mainMenu = menu()
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 960, height: 680),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "NekoKEM"
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.contentView = NSHostingView(rootView: ContentView(state: state))
        window.center()
        window.setFrameAutosaveName("NekoKEM")
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        if let screenshots { Screenshots(state: state, window: window, directory: screenshots).run() } else { state.start() }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
    func windowShouldClose(_ sender: NSWindow) -> Bool { state.mayClose() }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply { state.mayClose() ? .terminateNow : .terminateCancel }

    // Text fields need the Edit menu for copy and paste.
    private func menu() -> NSMenu {
        let main = NSMenu()
        func add(_ title: String, _ items: [NSMenuItem]) {
            let item = NSMenuItem()
            let submenu = NSMenu(title: title)
            for entry in items { submenu.addItem(entry) }
            item.submenu = submenu
            main.addItem(item)
        }
        func item(_ title: String, _ action: Selector, _ key: String, shift: Bool = false) -> NSMenuItem {
            let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
            if shift { item.keyEquivalentModifierMask = [.command, .shift] }
            return item
        }
        add("NekoKEM", [
            item("About NekoKEM", #selector(NSApplication.orderFrontStandardAboutPanel(_:)), ""),
            .separator(),
            item("Hide NekoKEM", #selector(NSApplication.hide(_:)), "h"),
            .separator(),
            item("Quit NekoKEM", #selector(NSApplication.terminate(_:)), "q"),
        ])
        add("Edit", [
            item("Undo", Selector(("undo:")), "z"),
            item("Redo", Selector(("redo:")), "z", shift: true),
            .separator(),
            item("Cut", #selector(NSText.cut(_:)), "x"),
            item("Copy", #selector(NSText.copy(_:)), "c"),
            item("Paste", #selector(NSText.paste(_:)), "v"),
            item("Select All", #selector(NSText.selectAll(_:)), "a"),
        ])
        add("Window", [
            item("Minimize", #selector(NSWindow.performMiniaturize(_:)), "m"),
            item("Close", #selector(NSWindow.performClose(_:)), "w"),
        ])
        return main
    }
}

/// `NekoKEM --screenshots <directory>` shows fixed example data page by page
/// and saves one PNG of the window for each; CI publishes them for review.
@MainActor
final class Screenshots {
    private let state: AppState
    private let window: NSWindow
    private let directory: URL
    private var names = ["encrypt", "encrypt-recipients", "encrypt-warning", "decrypt", "keys", "fingerprint", "contacts", "settings"]

    init(state: AppState, window: NSWindow, directory: String) {
        self.state = state; self.window = window; self.directory = URL(fileURLWithPath: directory, isDirectory: true)
    }

    func run() {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        window.setContentSize(NSSize(width: 960, height: 680))
        window.center()
        window.level = .floating
        next()
    }

    private func next() {
        guard !names.isEmpty else { exit(0) }
        let name = names.removeFirst()
        state.showDemo(name)
        // Let the page and any sheet finish drawing.
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            capture(directory.appendingPathComponent(name + ".png"))
            next()
        }
    }

    private func capture(_ file: URL) {
        // The screen region shows sheets too; the view's own drawing is the fallback.
        if let screen = window.screen {
            let frame = window.frame
            let region = "\(Int(frame.minX)),\(Int(screen.frame.height - frame.maxY)),\(Int(frame.width)),\(Int(frame.height))"
            let tool = Process()
            tool.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
            tool.arguments = ["-x", "-R", region, file.path]
            if (try? tool.run()) != nil {
                tool.waitUntilExit()
                if tool.terminationStatus == 0, FileManager.default.fileExists(atPath: file.path) { return }
            }
        }
        guard let view = window.contentView, let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return }
        view.cacheDisplay(in: view.bounds, to: bitmap)
        try? bitmap.representation(using: .png, properties: [:])?.write(to: file)
    }
}

let arguments = CommandLine.arguments
let screenshots = arguments.firstIndex(of: "--screenshots").flatMap { $0 + 1 < arguments.count ? arguments[$0 + 1] : nil }
let application = NSApplication.shared
let delegate = AppDelegate(screenshots: screenshots)
application.delegate = delegate
application.setActivationPolicy(.regular)
application.run()

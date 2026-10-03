"""Check a visible native window, local WKWebView load and real settings IPC."""
from pathlib import Path
import argparse
import json
import os
import plistlib
import subprocess
import time

WINDOW_PROBE = r"""
import Foundation
import CoreGraphics
guard CommandLine.arguments.count == 2, let pid = Int32(CommandLine.arguments[1]) else { exit(2) }
let options: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
guard let windows = CGWindowListCopyWindowInfo(options, kCGNullWindowID) as? [[String: Any]] else { exit(3) }
for window in windows {
    guard let owner = window[kCGWindowOwnerPID as String] as? Int32, owner == pid,
          let layer = window[kCGWindowLayer as String] as? Int, layer == 0,
          let bounds = window[kCGWindowBounds as String] as? [String: Any],
          let width = bounds["Width"] as? Double, let height = bounds["Height"] as? Double,
          width >= 760, height >= 620 else { continue }
    let evidence: [String: Any] = ["owner_pid": owner, "width": width, "height": height]
    let data = try JSONSerialization.data(withJSONObject: evidence, options: [.sortedKeys])
    print(String(decoding: data, as: UTF8.self))
    exit(0)
}
exit(4)
"""
NORMAL_CLOSE = r"""
import AppKit
guard CommandLine.arguments.count == 2, let pid = Int32(CommandLine.arguments[1]),
      let application = NSRunningApplication(processIdentifier: pid), application.terminate() else { exit(2) }
"""


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("--log", type=Path, required=True)
    args = parser.parse_args()
    with (args.app / "Contents/Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    executable = args.app / "Contents/MacOS" / info["CFBundleExecutable"]
    with args.log.open("wb") as stream:
        environment = os.environ.copy()
        environment["NEKOKEM_NATIVE_STARTUP_EVIDENCE"] = "1"
        process = subprocess.Popen([str(executable)], stdout=stream, stderr=subprocess.STDOUT, env=environment)
        try:
            deadline = time.monotonic() + 40
            while time.monotonic() < deadline:
                assert process.poll() is None, "Native macOS GUI failed during startup; inspect the startup log"
                log = args.log.read_text(encoding="utf-8", errors="replace")
                if all(f"NekoKEM native startup: {stage}" in log for stage in ("page-loaded", "settings-ready")):
                    break
                time.sleep(0.2)
            else:
                raise AssertionError("Real local WKWebView load/settings IPC did not become ready")
            visible = subprocess.run(["xcrun", "swift", "-e", WINDOW_PROBE, str(process.pid)], capture_output=True, text=True, timeout=30)
            assert visible.returncode == 0, f"Visible native NekoKEM window not found: {visible.stdout}{visible.stderr}"
            evidence = json.loads(visible.stdout)
            assert evidence["owner_pid"] == process.pid
            print(f"Actual visible native macOS window: {evidence}")
            print("Actual local WKWebView page load and real get_settings IPC passed")
            subprocess.run(["xcrun", "swift", "-e", NORMAL_CLOSE, str(process.pid)], check=True, timeout=30)
            assert process.wait(timeout=20) == 0, "Idle macOS GUI did not close normally"
            print("Actual idle macOS GUI normal application close passed")
        finally:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

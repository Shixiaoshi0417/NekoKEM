"""Validate the native Apple Silicon application before it is packaged."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import plistlib
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def output(*command):
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)


def verify(app, expected_version, expected_source):
    contents = app / "Contents"
    with (contents / "Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    assert info["CFBundleIdentifier"] == "com.shixiaoshi0417.nekokem.desktop"
    assert info["CFBundleShortVersionString"] == expected_version
    assert info["LSMinimumSystemVersion"] == "11.0"
    executable = contents / "MacOS" / info["CFBundleExecutable"]
    assert output("lipo", "-archs", str(executable)).strip() == "arm64"
    headers = output("otool", "-hv", str(executable))
    assert "PIE" in headers, "Application must retain ASLR/PIE"
    commands = output("otool", "-l", str(executable))
    versions = re.findall(r"\bminos\s+([\d.]+)", commands)
    assert versions and all(value in ("11.0", "11.0.0") for value in versions), versions
    imports = output("otool", "-L", str(executable))
    libraries = [line.strip().split(" (", 1)[0] for line in imports.splitlines()[1:]]
    assert libraries and all(value.startswith(("/System/Library/", "/usr/lib/")) for value in libraries), libraries
    assert not any("libcrypto" in value or "libssl" in value for value in libraries)
    subprocess.run(["codesign", "--verify", "--deep", "--strict", str(app)], check=True)
    signature = output("codesign", "--display", "--verbose=4", str(app))
    assert "Signature=adhoc" in signature, signature
    assert "flags=" in signature and "runtime" in signature, "Ad-hoc signature must retain hardened runtime"
    entitlements = output("codesign", "--display", "--entitlements", ":-", str(app))
    assert "com.apple.security.get-task-allow" not in entitlements
    icon_name = info["CFBundleIconFile"]
    if not icon_name.endswith(".icns"):
        icon_name += ".icns"
    icon = contents / "Resources" / icon_name
    assert icon.read_bytes() == (ROOT / "desktop/src-tauri/icons/icon.icns").read_bytes()
    openssl_license = contents / "Resources/licenses/OpenSSL-LICENSE.txt"
    prefix = Path(os.environ["NEKOKEM_OPENSSL_PREFIX"])
    assert openssl_license.read_bytes() == (prefix / "LICENSE.txt").read_bytes(), "Bundled OpenSSL license differs from verified prefix"
    assert (contents / "Resources/licenses/NekoKEM-LICENSE.txt").read_bytes() == (ROOT / "LICENSE").read_bytes()
    assert re.fullmatch(r"[a-f0-9]{40}", expected_source), "Full source commit SHA is required"
    return {
        "version": expected_version,
        "source_sha": expected_source,
        "target": "aarch64-apple-darwin",
        "minimum_macos": "11.0",
        "openssl": "4.0.3-static",
        "app_executable_sha256": hashlib.sha256(executable.read_bytes()).hexdigest(),
        "icon_sha256": hashlib.sha256(icon.read_bytes()).hexdigest(),
        "openssl_license_sha256": hashlib.sha256(openssl_license.read_bytes()).hexdigest(),
        "signature": "ad-hoc-hardened-runtime",
        "notarized": False,
        "macho_dependencies": libraries,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    args = parser.parse_args()
    metadata = verify(args.app, args.version, args.source_sha)
    args.metadata.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print("arm64 macOS 11.0 GUI, static OpenSSL, square icon and ad-hoc signature verified")

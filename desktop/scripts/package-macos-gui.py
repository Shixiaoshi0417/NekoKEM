"""Package only the verified native macOS app and its verified Tauri DMG."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("check_gui_macos", ROOT / "desktop/tests/check_gui_macos.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1048576), b""):
            checksum.update(block)
    return checksum.hexdigest()


def package(bundle, output, version, source):
    app = bundle / "macos/NekoKEM.app"
    metadata = checker.verify(app, version, source)
    candidates = sorted((bundle / "dmg").glob(f"NekoKEM_{version}_aarch64.dmg"))
    assert len(candidates) == 1, "Expected exactly one Tauri arm64 DMG from this build"
    dmg = candidates[0]
    subprocess.run(["hdiutil", "verify", str(dmg)], check=True)
    # Check the app inside the built DMG, including its sealed resources. This
    # rejects a DMG containing an app from before signing or a different build.
    with tempfile.TemporaryDirectory(prefix="nekokem-gui-dmg-") as temporary:
        mount = Path(temporary) / "mount"
        mount.mkdir(mode=0o700)
        attached = False
        try:
            subprocess.run(["hdiutil", "attach", "-readonly", "-nobrowse", "-mountpoint", str(mount), str(dmg)], check=True)
            attached = True
            mounted = checker.verify(mount / "NekoKEM.app", version, source)
            assert mounted == metadata, "DMG does not contain the verified signed app"
        finally:
            if attached:
                subprocess.run(["hdiutil", "detach", str(mount)], check=True)
    output.mkdir(parents=True, exist_ok=True)
    gui_zip = output / "NekoKEM-macos-arm64-GUI.zip"
    public_dmg = output / "NekoKEM-macos-arm64-GUI.dmg"
    assert not gui_zip.exists() and not public_dmg.exists(), "Use a clean output directory"
    with tempfile.TemporaryDirectory(prefix="nekokem-gui-package-") as temporary:
        root = Path(temporary) / "NekoKEM-macos-arm64-GUI"
        root.mkdir(mode=0o700)
        shutil.copytree(app, root / app.name, symlinks=True)
        shutil.copy2(ROOT / "desktop/README.md", root / "README.md")
        shutil.copy2(ROOT / "LICENSE", root / "LICENSE")
        shutil.copy2(ROOT / "macos/SECURITY-DESIGN.md", root / "SECURITY-DESIGN.md")
        shutil.copy2(ROOT / "desktop/package-lock.json", root / "package-lock.json")
        shutil.copy2(ROOT / "desktop/src-tauri/Cargo.lock", root / "Cargo.lock")
        (root / "build-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        # ZIP preserves the signed bundle and its symlinks; no signing credentials
        # or user data are present in this staging directory.
        sums = [f"{digest(path)}  {path.relative_to(root).as_posix()}" for path in sorted(root.rglob("*")) if path.is_file() and not path.is_symlink()]
        (root / "SHA256SUMS.txt").write_text("\n".join(sums) + "\n", encoding="utf-8")
        subprocess.run(["ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", str(root), str(gui_zip)], check=True)
    shutil.copy2(dmg, public_dmg)
    (output / "gui-build-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    (output / "gui-SHA256SUMS.txt").write_text(f"{digest(gui_zip)}  {gui_zip.name}\n{digest(public_dmg)}  {public_dmg.name}\n", encoding="utf-8")
    print(f"Verified packages: {gui_zip.name}, {public_dmg.name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-sha", required=True)
    args = parser.parse_args()
    package(args.bundle_dir.resolve(), args.output.resolve(), args.version, args.source_sha)

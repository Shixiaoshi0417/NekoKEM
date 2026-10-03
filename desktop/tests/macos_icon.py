"""Pack the existing transparent square PNG artwork into an ICNS container.

No pixels, backgrounds, or corners are edited. --check rejects a stale export.
"""
from pathlib import Path
import argparse
import struct

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "desktop/src-tauri/icons/icon.icns"


def expected_icon():
    entries = []
    for kind, filename in (
        (b"icp5", "32x32.png"),
        (b"ic07", "128x128.png"),
        (b"ic08", "128x128@2x.png"),
        (b"ic11", "32x32.png"),
        (b"ic13", "128x128@2x.png"),
    ):
        png = (ROOT / "windows/icons" / filename).read_bytes()
        assert png.startswith(b"\x89PNG\r\n\x1a\n")
        entries.append(kind + struct.pack(">I", len(png) + 8) + png)
    payload = b"".join(entries)
    return b"icns" + struct.pack(">I", len(payload) + 8) + payload


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        assert OUTPUT.read_bytes() == expected_icon(), "ICNS differs from existing square PNG artwork"
        print("macOS ICNS contains the unchanged transparent square PNG artwork")
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_bytes(expected_icon())

#!/bin/bash
set -Eeuo pipefail

sdk_root=${1:?SDK root required}
api=${2:?API level required}
[[ "$api" == 26 || "$api" == 29 || "$api" == 35 ]] || { echo 'Unsupported test API' >&2; exit 1; }
sdkmanager_path=$(find "$sdk_root/cmdline-tools" -maxdepth 3 -type f -name sdkmanager -print -quit)
[[ -n "$sdkmanager_path" ]] || { echo 'sdkmanager not found' >&2; exit 1; }
package="system-images;android-$api;google_apis;x86_64"
image_dir="$sdk_root/system-images/android-$api/google_apis/x86_64"

verify_image() {
    python3 - "$image_dir" "$package" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
directory, package = Path(sys.argv[1]), sys.argv[2]
try:
    root = ET.parse(directory / 'package.xml').getroot()
    installed = any(node.tag.rsplit('}', 1)[-1] == 'localPackage' and
                    node.get('path') == package for node in root.iter())
    assert installed and (directory / 'system.img').stat().st_size > 0
except (OSError, ET.ParseError, AssertionError) as error:
    print(f'Incomplete emulator image: {error}', file=sys.stderr)
    sys.exit(1)
PY
}

# sdkmanager verifies Google's archive checksums. Retry installation only;
# device tests and their assertions must never be retried or suppressed here.
for attempt in 1 2 3; do
    if timeout 300s "$sdkmanager_path" --sdk_root="$sdk_root" --install "$package" --channel=0 && verify_image; then
        exit 0
    fi
    echo "Emulator image installation failed (attempt $attempt/3): $package" >&2
    if [[ "$attempt" != 3 ]]; then sleep 5; fi
done
echo 'Emulator image installation exhausted retries' >&2
exit 1

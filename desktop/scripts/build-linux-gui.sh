#!/usr/bin/env bash
# Build native Linux bundles with the target architecture's loader dependency.
set -Eeuo pipefail
[[ $# == 1 && $(uname -s) == Linux ]] || { echo 'Usage: build-linux-gui.sh <x86_64|aarch64> (native Linux only)' >&2; exit 2; }
architecture=$1
[[ "$architecture" == x86_64 || "$architecture" == aarch64 ]] || exit 2
[[ $(uname -m) == "$architecture" ]] || { echo 'Use the native target runner' >&2; exit 2; }
repository=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
cd "$repository/desktop"
linux_bundle_config=$(python3 - "$architecture" <<'PY'
import json
from pathlib import Path
import sys
config = json.loads(Path('src-tauri/tauri.linux.conf.json').read_text())
if sys.argv[1] == 'aarch64':
    # AArch64's ELF also lists its dynamic loader in DT_NEEDED. Keep this
    # architecture-specific capability out of x86_64 RPMs.
    config['bundle']['linux']['rpm']['depends'].append('ld-linux-aarch64.so.1()(64bit)')
print(json.dumps(config))
PY
)
npm run tauri -- build --target "$architecture-unknown-linux-gnu" \
    --config "$linux_bundle_config" --bundles deb,rpm -- --locked

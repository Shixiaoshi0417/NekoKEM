#!/usr/bin/env bash
# Native Apple Silicon dependency used by both the CLI and Tauri application.
set -Eeuo pipefail

readonly OPENSSL_VERSION=4.0.3
readonly OPENSSL_SHA256=325b5c806167c13b40b1ffeadfe0248197c00eccc4cf123ec1e28d2d2fd216d9
readonly MINIMUM_MACOS=11.0
if [[ $# != 1 || $(uname -s) != Darwin || $(uname -m) != arm64 ]]; then
    printf 'Usage: %s <prefix> (native macOS arm64 only)\n' "$0" >&2
    exit 2
fi
for tool in xcrun curl perl make tar shasum python3; do
    command -v "$tool" >/dev/null || { printf 'Required tool missing: %s\n' "$tool" >&2; exit 1; }
done
prefix=$1
mkdir -p "$prefix"
prefix=$(cd "$prefix" && pwd -P)
cc=$(xcrun --find clang)
ar=$(xcrun --find ar)
ranlib=$(xcrun --find ranlib)
sdk=$(xcrun --sdk macosx --show-sdk-path)
sdk_version=$(xcrun --sdk macosx --show-sdk-version)
compiler=$("$cc" --version | head -n 1)
jobs=${NEKOKEM_BUILD_JOBS:-2}
[[ "$jobs" =~ ^[1-9][0-9]*$ ]] || { echo 'NEKOKEM_BUILD_JOBS must be a positive integer' >&2; exit 2; }
build_root=$(mktemp -d "${TMPDIR:-/tmp}/nekokem-macos-openssl.XXXXXX")
trap 'rm -rf -- "$build_root"' EXIT
expected_manifest="$build_root/manifest.json"
python3 - "$expected_manifest" "$compiler" "$sdk_version" <<'PY'
import json
from pathlib import Path
import sys
Path(sys.argv[1]).write_text(json.dumps({
    'openssl_version': '4.0.3',
    'openssl_source_sha256': '325b5c806167c13b40b1ffeadfe0248197c00eccc4cf123ec1e28d2d2fd216d9',
    'target': 'darwin64-arm64-cc', 'minimum_macos': '11.0',
    'compiler': sys.argv[2], 'sdk_version': sys.argv[3],
    'configure': ['no-shared', 'no-module', 'no-dso', 'no-tests', 'no-apps',
                  'no-docs', 'no-sock', 'no-zlib', 'no-zstd'],
    'cflags': ['-O3', '-arch', 'arm64', '-mmacosx-version-min=11.0',
               '-fstack-protector-strong', '-D_FORTIFY_SOURCE=3'],
}, sort_keys=True, indent=2) + '\n')
PY
if [[ -f "$prefix/lib/libcrypto.a" ]]; then
    cmp "$expected_manifest" "$prefix/nekokem-build-manifest.json" || {
        echo 'Cached OpenSSL compiler/SDK/configuration does not match this build' >&2
        exit 1
    }
else
    archive="$build_root/openssl.tar.gz"
    source_dir="$build_root/source"
    mkdir -p "$source_dir"
    curl --fail --location --silent --show-error --proto '=https' --tlsv1.2 --retry 3 \
        -o "$archive" "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
    printf '%s  %s\n' "$OPENSSL_SHA256" "$archive" | shasum -a 256 -c -
    tar -xzf "$archive" -C "$source_dir" --strip-components=1 --no-same-owner
    (
        cd "$source_dir"
        export CC="$cc" AR="$ar" RANLIB="$ranlib" SDKROOT="$sdk"
        export MACOSX_DEPLOYMENT_TARGET="$MINIMUM_MACOS"
        export CFLAGS="-O3 -arch arm64 -mmacosx-version-min=$MINIMUM_MACOS -fstack-protector-strong -D_FORTIFY_SOURCE=3"
        perl ./Configure darwin64-arm64-cc no-shared no-module no-dso no-tests \
            no-apps no-docs no-sock no-zlib no-zstd --prefix="$prefix" --libdir=lib
        make --silent -j"$jobs"
        make --silent install_dev
    )
    cp "$source_dir/LICENSE.txt" "$prefix/LICENSE.txt"
    cp "$expected_manifest" "$prefix/nekokem-build-manifest.json"
fi
python3 - "$prefix" <<'PY'
from pathlib import Path
import re
import subprocess
import sys
prefix = Path(sys.argv[1])
for name in ['lib/libcrypto.a', 'include/openssl/evp.h', 'LICENSE.txt']:
    assert (prefix/name).is_file() and (prefix/name).stat().st_size, name
assert re.search(r'^#\s*define OPENSSL_VERSION_STR "4\.0\.3"',
                 (prefix/'include/openssl/opensslv.h').read_text(), re.M)
arches = subprocess.check_output(['xcrun', 'lipo', '-archs', str(prefix/'lib/libcrypto.a')], text=True).strip()
assert arches == 'arm64', f'OpenSSL is not native arm64: {arches}'
print('Pinned static OpenSSL 4.0.3 arm64 and configuration verified')
PY

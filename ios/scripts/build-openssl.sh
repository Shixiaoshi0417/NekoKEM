#!/usr/bin/env bash
# The pinned static OpenSSL for the iOS app: arm64 iPhone and iPad devices, or
# the arm64 simulator that CI takes the screenshots on.
#   build-openssl.sh <prefix> device|simulator
set -Eeuo pipefail

readonly OPENSSL_VERSION=4.1.0
readonly OPENSSL_SHA256=50c17baf54cb008f6ce42d50deb4b28050a1aad711636cabd4b241c7e90cc2fd
readonly MINIMUM_IOS=16.0
if [[ $# != 2 || ( $2 != device && $2 != simulator ) || $(uname -s) != Darwin || $(uname -m) != arm64 ]]; then
    printf 'Usage: %s <prefix> device|simulator (native macOS arm64 only)\n' "$0" >&2
    exit 2
fi
for tool in xcrun curl perl make tar shasum python3; do
    command -v "$tool" >/dev/null || { printf 'Required tool missing: %s\n' "$tool" >&2; exit 1; }
done
platform=$2
if [[ $platform == device ]]; then
    sdk_name=iphoneos target=ios64-xcrun minimum=-mios-version-min=$MINIMUM_IOS
else
    sdk_name=iphonesimulator target=iossimulator-arm64-xcrun minimum=-mios-simulator-version-min=$MINIMUM_IOS
fi
prefix=$1
mkdir -p "$prefix"
prefix=$(cd "$prefix" && pwd -P)
sdk=$(xcrun --sdk "$sdk_name" --show-sdk-path)
sdk_version=$(xcrun --sdk "$sdk_name" --show-sdk-version)
compiler=$(xcrun --sdk "$sdk_name" clang --version | head -n 1)
jobs=${NEKOKEM_BUILD_JOBS:-3}
[[ "$jobs" =~ ^[1-9][0-9]*$ ]] || { echo 'NEKOKEM_BUILD_JOBS must be a positive integer' >&2; exit 2; }
cflags="-O3 -arch arm64 $minimum -fstack-protector-strong -D_FORTIFY_SOURCE=3"
build_root=$(mktemp -d "${TMPDIR:-/tmp}/nekokem-ios-openssl.XXXXXX")
trap 'rm -rf -- "$build_root"' EXIT
expected_manifest="$build_root/manifest.json"
python3 - "$expected_manifest" "$compiler" "$sdk_name" "$sdk_version" "$target" "$cflags" <<'PY'
import json
from pathlib import Path
import sys
Path(sys.argv[1]).write_text(json.dumps({
    'openssl_version': '4.1.0',
    'openssl_source_sha256': '50c17baf54cb008f6ce42d50deb4b28050a1aad711636cabd4b241c7e90cc2fd',
    'target': sys.argv[5], 'minimum_ios': '16.0',
    'compiler': sys.argv[2], 'sdk': sys.argv[3], 'sdk_version': sys.argv[4],
    'configure': ['no-shared', 'no-module', 'no-dso', 'no-tests', 'no-apps',
                  'no-docs', 'no-sock', 'no-zlib', 'no-zstd'],
    'cflags': sys.argv[6].split(),
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
        export SDKROOT="$sdk" IPHONEOS_DEPLOYMENT_TARGET="$MINIMUM_IOS" CFLAGS="$cflags"
        perl ./Configure "$target" no-shared no-module no-dso no-tests \
            no-apps no-docs no-sock no-zlib no-zstd --prefix="$prefix" --libdir=lib
        make --silent -j"$jobs"
        make --silent install_dev
    )
    cp "$source_dir/LICENSE.txt" "$prefix/LICENSE.txt"
    cp "$expected_manifest" "$prefix/nekokem-build-manifest.json"
fi
python3 - "$prefix" "$platform" <<'PY'
from pathlib import Path
import re
import subprocess
import sys
prefix, platform = Path(sys.argv[1]), sys.argv[2]
for name in ['lib/libcrypto.a', 'include/openssl/evp.h', 'LICENSE.txt']:
    assert (prefix/name).is_file() and (prefix/name).stat().st_size, name
assert re.search(r'^#\s*define OPENSSL_VERSION_STR "4\.1\.0"',
                 (prefix/'include/openssl/opensslv.h').read_text(), re.M)
arches = subprocess.check_output(['xcrun', 'lipo', '-archs', str(prefix/'lib/libcrypto.a')], text=True).strip()
assert arches == 'arm64', f'OpenSSL is not arm64: {arches}'
# Every object must be built for the chosen platform, not for macOS.
loads = subprocess.check_output(['xcrun', 'otool', '-l', str(prefix/'lib/libcrypto.a')], text=True)
platforms = set(re.findall(r'^\s*platform (\S+)$', loads, re.M))
expected = {'device': {'2', 'IOS'}, 'simulator': {'7', 'IOSSIMULATOR'}}[platform]
assert platforms and platforms <= expected, f'OpenSSL objects are for platform {platforms}'
print(f'Pinned static OpenSSL 4.1.0 arm64 for the iOS {platform} verified')
PY

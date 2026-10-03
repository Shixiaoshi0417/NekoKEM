#!/usr/bin/env bash
# Native, position-independent static dependency for the Linux desktop only.
set -Eeuo pipefail

readonly openssl_version=4.0.3
readonly openssl_sha256=325b5c806167c13b40b1ffeadfe0248197c00eccc4cf123ec1e28d2d2fd216d9
if [[ $# != 2 || $(uname -s) != Linux ]]; then
    printf 'Usage: %s <x86_64|aarch64> <prefix> (native Linux only)\n' "$0" >&2
    exit 2
fi
architecture=$1
case "$architecture" in
    x86_64) configure_target=linux-x86_64 ;;
    aarch64) configure_target=linux-aarch64 ;;
    *) echo 'Unsupported Linux desktop architecture' >&2; exit 2 ;;
esac
[[ $(uname -m) == "$architecture" ]] || { echo 'Use the native target runner' >&2; exit 2; }
for tool in gcc ar ranlib curl perl make tar sha256sum python3 readelf; do
    command -v "$tool" >/dev/null || { printf 'Required tool missing: %s\n' "$tool" >&2; exit 1; }
done
prefix=$2
mkdir -p "$prefix"
prefix=$(cd "$prefix" && pwd -P)
repository=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
temporary=$(mktemp -d "${TMPDIR:-/tmp}/nekokem-linux-gui-openssl.XXXXXX")
trap 'rm -rf -- "$temporary"' EXIT
jobs=${NEKOKEM_BUILD_JOBS:-2}
[[ "$jobs" =~ ^[1-9][0-9]*$ ]] || { echo 'Invalid NEKOKEM_BUILD_JOBS' >&2; exit 2; }
compiler=$(gcc -dumpfullversion -dumpversion)
libc=$(getconf GNU_LIBC_VERSION)
python3 - "$temporary/manifest.json" "$architecture" "$configure_target" "$compiler" "$libc" <<'PY'
import json
from pathlib import Path
import sys
Path(sys.argv[1]).write_text(json.dumps({
    'openssl_version': '4.0.3',
    'openssl_source_sha256': '325b5c806167c13b40b1ffeadfe0248197c00eccc4cf123ec1e28d2d2fd216d9',
    'architecture': sys.argv[2], 'target': sys.argv[3],
    'compiler': sys.argv[4], 'libc': sys.argv[5],
    'configure': ['no-shared', 'no-module', 'no-dso', 'no-tests', 'no-apps',
                  'no-docs', 'no-sock', 'no-zlib', 'no-zstd'],
    'cflags': ['-O3', '-fPIC', '-fstack-protector-strong', '-D_FORTIFY_SOURCE=3'],
}, sort_keys=True, indent=2)+'\n')
PY
if [[ -f "$prefix/lib/libcrypto.a" ]]; then
    cmp "$temporary/manifest.json" "$prefix/nekokem-build-manifest.json" || {
        echo 'Cached dependency does not match the compiler/libc/PIC recipe' >&2
        exit 1
    }
else
    archive="$temporary/openssl.tar.gz"
    source="$temporary/source"
    mkdir -p "$source"
    curl --fail --location --silent --show-error --proto '=https' --tlsv1.2 --retry 3 \
        -o "$archive" "https://github.com/openssl/openssl/releases/download/openssl-$openssl_version/openssl-$openssl_version.tar.gz"
    printf '%s  %s\n' "$openssl_sha256" "$archive" | sha256sum -c -
    tar -xzf "$archive" -C "$source" --strip-components=1 --no-same-owner
    (
        cd "$source"
        export CC=gcc AR=ar RANLIB=ranlib
        export CFLAGS='-O3 -fPIC -fstack-protector-strong -D_FORTIFY_SOURCE=3'
        perl ./Configure "$configure_target" no-shared no-module no-dso no-tests \
            no-apps no-docs no-sock no-zlib no-zstd --prefix="$prefix" --libdir=lib
        make --silent -j"$jobs"
        make --silent install_dev
    )
    install -m 0644 "$source/LICENSE.txt" "$prefix/LICENSE.txt"
    install -m 0644 "$temporary/manifest.json" "$prefix/nekokem-build-manifest.json"
fi
python3 - "$prefix" "$architecture" <<'PY'
from pathlib import Path
import re
import subprocess
import sys
prefix=Path(sys.argv[1])
for name in ['lib/libcrypto.a','include/openssl/evp.h','LICENSE.txt']:
    assert (prefix/name).is_file() and (prefix/name).stat().st_size, name
assert re.search(r'^#\s*define OPENSSL_VERSION_STR "4\.0\.3"',
                 (prefix/'include/openssl/opensslv.h').read_text(), re.M)
machines=set(re.findall(r'Machine:\s+(.+)',subprocess.check_output(
    ['readelf','-h',str(prefix/'lib/libcrypto.a')],text=True)))
expected='AArch64' if sys.argv[2]=='aarch64' else 'Advanced Micro Devices X86-64'
assert machines=={expected}, machines
PY
# A build-only release still verifies the actual runtime and Argon2 threads.
gcc -std=c17 -O2 -D_FORTIFY_SOURCE=3 -fstack-protector-strong -fPIE -pie \
    -Wl,-z,relro,-z,now,-z,noexecstack -I"$prefix/include" \
    "$repository/core/tests/openssl_version_tests.c" "$prefix/lib/libcrypto.a" \
    -ldl -pthread -o "$temporary/dependency-probe"
"$temporary/dependency-probe"
printf 'Verified native %s static PIC OpenSSL %s with assembly and threads\n' "$architecture" "$openssl_version"

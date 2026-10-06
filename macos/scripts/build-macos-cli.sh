#!/usr/bin/env bash
# Native arm64 CLI; static OpenSSL plus macOS system libraries only.
set -Eeuo pipefail

build_tests=${NEKOKEM_BUILD_TESTS:-1}
if [[ "$build_tests" != 0 && "$build_tests" != 1 ]]; then
    echo 'NEKOKEM_BUILD_TESTS must be 0 or 1' >&2
    exit 2
fi
if [[ $# -gt 1 || $(uname -s) != Darwin || $(uname -m) != arm64 ]]; then
    printf 'Usage: %s [output-directory] (native macOS arm64 only)\n' "$0" >&2
    exit 2
fi
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
output=${1:-"$repo_root/macos/out"}
mkdir -p "$output"
output=$(cd "$output" && pwd -P)
for tool in xcrun codesign python3 install shasum; do
    command -v "$tool" >/dev/null || { printf 'Required tool missing: %s\n' "$tool" >&2; exit 1; }
done
build_root=$(mktemp -d "${TMPDIR:-/tmp}/nekokem-macos-cli.XXXXXX")
trap 'rm -rf -- "$build_root"' EXIT
prefix=${NEKOKEM_OPENSSL_PREFIX:-"$build_root/openssl"}
bash "$repo_root/macos/scripts/build-openssl.sh" "$prefix"
prefix=$(cd "$prefix" && pwd -P)
cc=$(xcrun --find clang)
ar=$(xcrun --find ar)
export SDKROOT
SDKROOT=$(xcrun --sdk macosx --show-sdk-path)
export MACOSX_DEPLOYMENT_TARGET=11.0
flags=(-std=c17 -O2 -arch arm64 -mmacosx-version-min=11.0 -isysroot "$SDKROOT"
       -D_POSIX_C_SOURCE=200809L -D_DARWIN_C_SOURCE=1 -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=3
       -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Wformat=2
       -Wstrict-prototypes -Werror -fstack-protector-strong -fPIE
       -I"$prefix/include" -I"$repo_root/core/include" -I"$repo_root/core/src"
       -I"$repo_root/linux/src")
link=(-Wl,-pie,-dead_strip)
libs=("$prefix/lib/libcrypto.a" -pthread -framework CoreFoundation)
core=(nekokem key_management nekokem_v3 nekokem_v4 kem hybrid aes file file_v3 file_v4 secure_mem private_key)
sources=()
for name in "${core[@]}"; do sources+=("$repo_root/core/src/$name.c"); done
cli=("$repo_root/linux/src/main.c" "$repo_root/linux/src/cli.c" "$repo_root/linux/src/i18n.c")

# Required even in build-only mode: verify the actual linked runtime and threads.
version_probe="$build_root/openssl-version-check"
"$cc" "${flags[@]}" "${link[@]}" "$repo_root/core/tests/openssl_version_tests.c" \
    "${libs[@]}" -o "$version_probe"
"$version_probe"
"$cc" "${flags[@]}" -DNDEBUG "${link[@]}" "${cli[@]}" "${sources[@]}" \
    "${libs[@]}" -o "$output/nekokem"
xcrun strip -x "$output/nekokem"
codesign --force --sign - --timestamp=none --options runtime "$output/nekokem"
python3 "$repo_root/macos/tests/check_macho.py" "$output/nekokem"
[[ $("$output/nekokem" --version) == 'NekoKEM 3.3.2' ]] || {
    echo 'CLI version does not match 3.3.2' >&2; exit 1;
}

if [[ "$build_tests" == 1 ]]; then
    sanitized="$build_root/sanitized"
    mkdir -p "$sanitized"
    sanitize=(-O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined
              -fno-sanitize-recover=undefined)
    objects=()
    fault_objects=()
    for index in "${!sources[@]}"; do
        object="$sanitized/${core[$index]}.o"
        "$cc" "${flags[@]}" "${sanitize[@]}" -c "${sources[$index]}" -o "$object"
        objects+=("$object")
        if [[ ${core[$index]} == file ]]; then
            fault_object="$sanitized/file-fault.o"
            "$cc" "${flags[@]}" "${sanitize[@]}" -DNEKOKEM_TEST_FAULT_INJECTION \
                -c "${sources[$index]}" -o "$fault_object"
            fault_objects+=("$fault_object")
        else
            fault_objects+=("$object")
        fi
    done
    "$ar" rcs "$sanitized/libcore.a" "${objects[@]}"
    "$ar" rcs "$sanitized/libcore-fault.a" "${fault_objects[@]}"
    export ASAN_OPTIONS=halt_on_error=1
    export UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1
    # Darwin's ASan does not support LSan. Linux retains its existing LSan gate.
    for test in parser file_security gcm_limit aes_stream hybrid_kdf key_management \
        version_rejection progress input_boundary pem_interaction openssl_version \
        multi_recipient; do
        test_flags=(-DNEKOKEM_TEST_REGRESSION=1)
        library="$sanitized/libcore.a"
        if [[ "$test" == file_security ]]; then
            test_flags=(-DNEKOKEM_TEST_FAULT_INJECTION)
            library="$sanitized/libcore-fault.a"
        fi
        "$cc" "${flags[@]}" "${sanitize[@]}" "${test_flags[@]}" "${link[@]}" \
            "$repo_root/core/tests/${test}_tests.c" "$library" "${libs[@]}" \
            -o "$sanitized/${test}-tests"
        if [[ "$test" == pem_interaction ]]; then
            python3 "$repo_root/core/tests/pem_interaction_tests.py" "$sanitized/${test}-tests"
        else
            "$sanitized/${test}-tests"
        fi
    done
    "$cc" "${flags[@]}" "${sanitize[@]}" "${link[@]}" "${cli[@]}" \
        "$sanitized/libcore.a" "${libs[@]}" -o "$sanitized/nekokem"
    python3 "$repo_root/linux/tests/i18n_tests.py" "$sanitized/nekokem"
    python3 "$repo_root/macos/tests/system_language_tests.py" "$sanitized/nekokem"
    python3 "$repo_root/macos/tests/cli_tests.py" "$sanitized/nekokem"
    # Exercise the packaged, optimized and signed executable as well.
    python3 "$repo_root/linux/tests/i18n_tests.py" "$output/nekokem"
    python3 "$repo_root/macos/tests/system_language_tests.py" "$output/nekokem"
    python3 "$repo_root/macos/tests/cli_tests.py" "$output/nekokem"
fi

install -m 0644 "$prefix/LICENSE.txt" "$output/OPENSSL-LICENSE.txt"
python3 - "$output" "$repo_root" "$cc" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
root, repo, cc = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
metadata = {
    'source_sha': os.environ.get('GITHUB_SHA') or subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip(),
    'run_id': os.environ.get('GITHUB_RUN_ID'), 'platform': 'macos-arm64',
    'version': '3.3.2', 'minimum_macos': '11.0', 'openssl_version': '4.0.3',
    'openssl_source_sha256': '325b5c806167c13b40b1ffeadfe0248197c00eccc4cf123ec1e28d2d2fd216d9',
    'compiler': subprocess.check_output([cc, '--version'], text=True).splitlines()[0],
    'sdk_version': subprocess.check_output(['xcrun', '--sdk', 'macosx', '--show-sdk-version'], text=True).strip(),
    'signing': 'ad-hoc', 'developer_id_signed': False, 'notarized': False,
    'apk_signing_material_used': False,
    'exe_sha256': hashlib.sha256((root/'nekokem').read_bytes()).hexdigest(),
}
(root/'build-metadata.json').write_text(json.dumps(metadata, indent=2)+'\n')
PY
source_date_epoch=${SOURCE_DATE_EPOCH:-$(git -C "$repo_root" log -1 --format=%ct)}
[[ "$source_date_epoch" =~ ^[0-9]+$ ]] || { echo 'Invalid SOURCE_DATE_EPOCH' >&2; exit 2; }
python3 "$repo_root/macos/scripts/package-cli.py" "$output" "$repo_root" "$source_date_epoch"

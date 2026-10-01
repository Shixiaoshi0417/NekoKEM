#!/usr/bin/env bash
# Run in MSYS2 UCRT64. Produces a native x64 EXE with static OpenSSL/runtime.
set -Eeuo pipefail
readonly OPENSSL_VERSION=3.5.6
readonly OPENSSL_SHA256=deae7c80cba99c4b4f940ecadb3c3338b13cb77418409238e57d7f31f2a3b736
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
output=${1:-"$repo_root/windows/out"}
mkdir -p -- "$output"
output=$(cd -- "$output" && pwd -P)
if [[ ${MSYSTEM:-} != UCRT64 ]]; then
    echo 'Use the MSYS2 UCRT64 shell (native Windows x64)' >&2
    exit 1
fi
build_root=$(mktemp -d)
trap 'rm -rf -- "$build_root"' EXIT
prefix=${NEKOKEM_OPENSSL_PREFIX:-"$build_root/openssl"}
manifest="OpenSSL $OPENSSL_VERSION $OPENSSL_SHA256 mingw64 $(gcc -dumpfullversion) no-shared no-module no-dso no-tests no-apps no-docs no-sock no-zlib no-zstd"
if [[ ! -f "$prefix/lib/libcrypto.a" ]]; then
    archive="$build_root/openssl.tar.gz"
    source="$build_root/source"
    mkdir -p "$source" "$prefix"
    curl --fail --location --silent --show-error --proto '=https' --tlsv1.2 --retry 3 \
        -o "$archive" "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
    printf '%s  %s\n' "$OPENSSL_SHA256" "$archive" | sha256sum -c -
    tar -xzf "$archive" -C "$source" --strip-components=1 --no-same-owner
    (
        cd "$source"
        perl ./Configure mingw64 no-shared no-module no-dso no-tests no-apps no-docs \
            no-sock no-zlib no-zstd --prefix="$prefix" --libdir=lib
        make --silent -j2
        make --silent install_dev
    )
    cp "$source/LICENSE.txt" "$prefix/LICENSE.txt"
    printf '%s\n' "$manifest" > "$prefix/nekokem-build-manifest.txt"
fi
[[ $(cat "$prefix/nekokem-build-manifest.txt") == "$manifest" ]] || {
    echo 'Prebuilt OpenSSL configuration/compiler mismatch' >&2
    exit 1
}
grep -Eq '^# *define OPENSSL_VERSION_STR "3\.5\.6"' "$prefix/include/openssl/opensslv.h"
cp "$prefix/LICENSE.txt" "$output/OPENSSL-LICENSE.txt"
flags=(-std=c17 -O2 -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Wformat=2 \
       -Wstrict-prototypes -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=3 \
       -D_WIN32_WINNT=0x0A00 -D__USE_MINGW_ANSI_STDIO=1 \
       -I"$prefix/include" -I"$repo_root/core/include" -I"$repo_root/core/src" -I"$repo_root/linux/src")
link=(-static -Wl,--dynamicbase,--nxcompat,--high-entropy-va,--no-insert-timestamp)
libs=("$prefix/lib/libcrypto.a" -lcrypt32 -lbcrypt -ladvapi32 -lshell32 -lole32 -luuid -lws2_32)
core=(nekokem key_management nekokem_v3 kem hybrid aes file file_v3 secure_mem private_key)
sources=()
for name in "${core[@]}"; do sources+=("$repo_root/core/src/$name.c"); done
gcc "${flags[@]}" -fanalyzer -fsyntax-only "${sources[@]}" \
    "$repo_root/linux/src/cli.c" "$repo_root/linux/src/i18n.c" "$repo_root/windows/src/main.c"
windres -I"$repo_root/windows/icons" "$repo_root/windows/icons/app.rc" "$output/app-icon.o"
gcc "${flags[@]}" "${link[@]}" -municode \
    "$repo_root/windows/src/main.c" "$repo_root/linux/src/main.c" \
    "$repo_root/linux/src/cli.c" "$repo_root/linux/src/i18n.c" \
    "${sources[@]}" "$output/app-icon.o" "${libs[@]}" -o "$output/nekokem.exe"
for test in hybrid_kdf gcm_limit parser; do
    gcc "${flags[@]}" "${link[@]}" "$repo_root/core/tests/${test}_tests.c" \
        "${sources[@]}" "${libs[@]}" -o "$output/${test}_tests.exe"
done
gcc "${flags[@]}" "${link[@]}" -DNEKOKEM_TEST_FAULT_INJECTION \
    "$repo_root/windows/tests/core_tests.c" "${sources[@]}" "${libs[@]}" -o "$output/windows_core_tests.exe"
gcc "${flags[@]}" "${link[@]}" "$repo_root/windows/tests/console_tests.c" \
    "${sources[@]}" "${libs[@]}" -o "$output/console_tests.exe"
objdump -p "$output/nekokem.exe" > "$output/pe-headers.txt"
python "$repo_root/windows/tests/check_pe.py" "$output/nekokem.exe" "$output/pe-headers.txt"
python - "$output" <<'PYMETA'
import hashlib, json, os
from pathlib import Path
import subprocess, sys
root=Path(sys.argv[1])
metadata={'source_sha':os.environ.get('GITHUB_SHA') or subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
          'run_id':os.environ.get('GITHUB_RUN_ID'), 'platform':'windows-x86_64',
          'openssl_version':'3.5.6', 'compiler':subprocess.check_output(['gcc','--version'],text=True).splitlines()[0],
          'authenticode_signed':False, 'apk_signing_material_used':False,
          'exe_sha256':hashlib.sha256((root/'nekokem.exe').read_bytes()).hexdigest()}
(root/'build-metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
PYMETA
cp "$repo_root/windows/README.md" "$repo_root/windows/SECURITY-DESIGN.md" "$repo_root/LICENSE" "$output/"
(cd "$output" && sha256sum nekokem.exe > SHA256SUMS.txt)

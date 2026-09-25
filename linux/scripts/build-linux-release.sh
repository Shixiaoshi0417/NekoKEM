#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

readonly OPENSSL_VERSION="3.5.6"
readonly OPENSSL_SHA256="deae7c80cba99c4b4f940ecadb3c3338b13cb77418409238e57d7f31f2a3b736"
readonly OPENSSL_URL="https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz"

usage() {
    printf 'Usage: %s <x86_64|aarch64> [output-directory]\n' "$0" >&2
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
    usage
    exit 2
fi

target_arch=$1
case "$target_arch" in
    x86_64)
        openssl_target="linux-x86_64"
        expected_machine="Advanced Micro Devices X86-64"
        default_cross_prefix="x86_64-linux-gnu-"
        default_qemu="qemu-x86_64"
        ;;
    aarch64)
        openssl_target="linux-aarch64"
        expected_machine="AArch64"
        default_cross_prefix="aarch64-linux-gnu-"
        default_qemu="qemu-aarch64"
        ;;
    *)
        usage
        exit 2
        ;;
esac

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
repo_root=$(cd -- "$script_dir/../.." && pwd -P)
output_dir=${2:-"$repo_root/dist"}
mkdir -p -- "$output_dir"
output_dir=$(cd -- "$output_dir" && pwd -P)

host_arch=$(uname -m)
case "$host_arch" in
    amd64) host_arch="x86_64" ;;
    arm64) host_arch="aarch64" ;;
esac

if [[ "$host_arch" == "$target_arch" ]]; then
    tool_prefix=""
else
    tool_prefix=${NEKOKEM_CROSS_PREFIX:-$default_cross_prefix}
fi

cc=${NEKOKEM_CC:-"${tool_prefix}gcc"}
ar=${NEKOKEM_AR:-"${tool_prefix}ar"}
ranlib=${NEKOKEM_RANLIB:-"${tool_prefix}ranlib"}
strip_tool=${NEKOKEM_STRIP:-"${tool_prefix}strip"}
readelf_tool=${NEKOKEM_READELF:-"${tool_prefix}readelf"}
detected_jobs=$(nproc)
if (( detected_jobs > 2 )); then
    detected_jobs=2
fi
build_jobs=${NEKOKEM_BUILD_JOBS:-$detected_jobs}

if [[ ! "$build_jobs" =~ ^[1-9][0-9]*$ ]]; then
    printf 'NEKOKEM_BUILD_JOBS must be a positive integer\n' >&2
    exit 2
fi

for tool in "$cc" "$ar" "$ranlib" "$strip_tool" "$readelf_tool" \
    curl perl make tar gzip sha256sum install mktemp; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        printf 'Required build tool not found: %s\n' "$tool" >&2
        exit 1
    fi
done

tmp_base=${TMPDIR:-/tmp}
build_root=$(mktemp -d "$tmp_base/nekokem-linux-release.XXXXXX")
cleanup_build() {
    local build_name
    build_name=$(basename -- "$build_root")
    if [[ -n "$build_root" && -d "$build_root" &&
          "$build_name" == nekokem-linux-release.* ]]; then
        rm -rf -- "$build_root"
    fi
}
trap cleanup_build EXIT HUP INT TERM

if [[ -n ${NEKOKEM_OPENSSL_PREFIX:-} ]]; then
    openssl_prefix=$(cd -- "$NEKOKEM_OPENSSL_PREFIX" && pwd -P)
    printf 'Using prebuilt static OpenSSL from %s\n' "$openssl_prefix"
else
    openssl_archive="$build_root/openssl-${OPENSSL_VERSION}.tar.gz"
    openssl_source="$build_root/openssl-source"
    openssl_prefix="$build_root/openssl-prefix"
    mkdir -p -- "$openssl_source" "$openssl_prefix"

    printf 'Downloading OpenSSL %s for %s...\n' "$OPENSSL_VERSION" "$target_arch"
    curl --fail --location --silent --show-error --proto '=https' --tlsv1.2 \
        --retry 3 \
        --output "$openssl_archive" "$OPENSSL_URL"
    printf '%s  %s\n' "$OPENSSL_SHA256" "$openssl_archive" | sha256sum -c -
    tar --extract --gzip --file "$openssl_archive" \
        --directory "$openssl_source" --strip-components=1 --no-same-owner

    printf 'Building static OpenSSL %s (%s)...\n' \
        "$OPENSSL_VERSION" "$target_arch"
    (
        cd -- "$openssl_source"
        CC="$cc" AR="$ar" RANLIB="$ranlib" \
            perl ./Configure "$openssl_target" \
                no-shared no-module no-dso no-tests no-apps no-docs \
                no-sock no-zlib no-zstd \
                --prefix="$openssl_prefix" --openssldir=/etc/ssl \
                --libdir=lib
        make --silent -j"$build_jobs"
        make --silent install_dev
    )
fi

openssl_library="$openssl_prefix/lib/libcrypto.a"
if [[ ! -s "$openssl_library" || ! -f "$openssl_prefix/include/openssl/evp.h" ]]; then
    printf 'Static OpenSSL build did not produce the expected files\n' >&2
    exit 1
fi

build_objects="$build_root/objects"
binary="$build_root/nekokem"
mkdir -p -- "$build_objects"

sources=(
    linux/src/main.c
    linux/src/cli.c
    core/src/nekokem.c
    core/src/key_management.c
    core/src/nekokem_v3.c
    core/src/kem.c
    core/src/hybrid.c
    core/src/aes.c
    core/src/file.c
    core/src/file_v3.c
    core/src/secure_mem.c
    core/src/private_key.c
)
cpp_flags=(
    -D_POSIX_C_SOURCE=200809L
    -D_FORTIFY_SOURCE=3
    -I"$openssl_prefix/include"
    -I"$repo_root/core/include"
    -I"$repo_root/core/src"
)
c_flags=(
    -std=c17
    -O2
    -DNDEBUG
    -Wall
    -Wextra
    -Wpedantic
    -Wconversion
    -Wshadow
    -Wformat=2
    -Wstrict-prototypes
    -Werror
    -fstack-protector-strong
    -fPIE
    -ffunction-sections
    -fdata-sections
)
objects=()

printf 'Building NekoKEM CLI (%s)...\n' "$target_arch"
for source in "${sources[@]}"; do
    object_name=${source//\//_}
    object="$build_objects/${object_name%.c}.o"
    "$cc" "${cpp_flags[@]}" "${c_flags[@]}" \
        -c "$repo_root/$source" -o "$object"
    objects+=("$object")
done

"$cc" -static-pie -Wl,-z,relro,-z,now,--gc-sections \
    -o "$binary" "${objects[@]}" "$openssl_library" -ldl -pthread
"$strip_tool" --strip-unneeded "$binary"
chmod 0755 "$binary"

if ! "$readelf_tool" -h "$binary" | grep -F "$expected_machine" >/dev/null; then
    printf 'Built ELF machine does not match requested architecture %s\n' \
        "$target_arch" >&2
    exit 1
fi
if "$readelf_tool" -l "$binary" | grep -F 'INTERP' >/dev/null; then
    printf 'Release executable unexpectedly contains a dynamic interpreter\n' >&2
    exit 1
fi
if "$readelf_tool" -d "$binary" 2>/dev/null | grep -F '(NEEDED)' >/dev/null; then
    printf 'Release executable unexpectedly contains dynamic dependencies\n' >&2
    exit 1
fi

if [[ "$host_arch" == "$target_arch" ]]; then
    run_command=("$binary")
else
    qemu_command=${NEKOKEM_QEMU:-$default_qemu}
    if ! command -v "$qemu_command" >/dev/null 2>&1; then
        printf 'Cross-built binary requires %s for the smoke test\n' \
            "$qemu_command" >&2
        exit 1
    fi
    run_command=("$qemu_command" "$binary")
fi

test_root=$(mktemp -d "$build_root/smoke.XXXXXX")
password='test-only-linux-release-password'
printf 'NekoKEM Linux release test\nBinary:\000\001\377\n' > "$test_root/input.bin"
(
    cd -- "$test_root"
    export OPENSSL_CONF=/dev/null
    export OPENSSL_MODULES="$test_root/no-external-modules"
    export LD_LIBRARY_PATH="$test_root/no-shared-libraries"
    test "$("${run_command[@]}" --version)" = "NekoKEM 3.1.1"
    printf '%s\n%s\n' "$password" "$password" | \
        "${run_command[@]}" keygen
    test -s keys/public.key
    test -s keys/private.key.enc
    test ! -e keys/private.key
    test "$(dd if=keys/private.key.enc bs=1 count=4 status=none)" = NKPR
    "${run_command[@]}" encrypt hybrid input.bin output.nkem keys/public.key
    test "$(dd if=output.nkem bs=1 count=4 status=none)" = NKEM
    test "$(od -An -tu1 -j4 -N1 output.nkem | tr -d ' ')" = 3
    printf '%s\n' "$password" | \
        "${run_command[@]}" decrypt hybrid output.nkem recovered.bin \
            keys/private.key.enc
    cmp input.bin recovered.bin
    sha256sum input.bin recovered.bin
)
unset password
printf 'Hybrid NKEM v3 smoke test passed (%s).\n' "$target_arch"

package_name="NekoKEM-linux-${target_arch}"
stage_parent="$build_root/stage"
package_dir="$stage_parent/$package_name"
mkdir -p -- "$package_dir"
install -m 0755 "$binary" "$package_dir/nekokem"
install -m 0644 "$repo_root/linux/packaging/README" "$package_dir/README"
install -m 0644 "$repo_root/LICENSE" "$package_dir/LICENSE"
(
    cd -- "$package_dir"
    sha256sum LICENSE README nekokem > SHA256SUMS
    sha256sum -c SHA256SUMS
)

source_date_epoch=${SOURCE_DATE_EPOCH:-$(git -C "$repo_root" log -1 --format=%ct)}
if [[ ! "$source_date_epoch" =~ ^[0-9]+$ ]]; then
    printf 'SOURCE_DATE_EPOCH must be a non-negative integer\n' >&2
    exit 2
fi
archive_tmp="$build_root/${package_name}.tar.gz"
tar --sort=name --mtime="@$source_date_epoch" --owner=0 --group=0 \
    --numeric-owner --create --file=- --directory "$stage_parent" \
    "$package_name" | gzip -9 -n > "$archive_tmp"

verify_dir="$build_root/archive-verify"
mkdir -p -- "$verify_dir"
tar --extract --gzip --file "$archive_tmp" --directory "$verify_dir" \
    --no-same-owner
(
    cd -- "$verify_dir/$package_name"
    sha256sum -c SHA256SUMS
)

final_archive="$output_dir/${package_name}.tar.gz"
install -m 0644 "$archive_tmp" "$final_archive"
printf 'Created %s\n' "$final_archive"
sha256sum "$final_archive"

#!/bin/sh

set -eu

OPENSSL_VERSION=4.0.3
OPENSSL_SHA256=325b5c806167c13b40b1ffeadfe0248197c00eccc4cf123ec1e28d2d2fd216d9
ANDROID_API=26
NDK_VERSION=28.2.13676358

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
abi="${1:-arm64-v8a}"
case "$abi" in
    arm64-v8a) openssl_target=android-arm64; compiler_prefix=aarch64 ;;
    x86_64) openssl_target=android-x86_64; compiler_prefix=x86_64 ;;
    *) echo "Unsupported Android ABI: $abi" >&2; exit 1 ;;
esac
output_root="$project_dir/app/src/main/cpp/third_party/openssl/$abi"

if [ -z "${ANDROID_NDK_ROOT:-}" ]; then
    if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
        echo "Set ANDROID_NDK_ROOT or ANDROID_SDK_ROOT" >&2
        exit 1
    fi
    ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
fi

if [ ! -d "$ANDROID_NDK_ROOT" ]; then
    echo "Android NDK not found: $ANDROID_NDK_ROOT" >&2
    exit 1
fi
if [ "$(basename -- "$ANDROID_NDK_ROOT")" != "$NDK_VERSION" ]; then
    echo "NDK r28c ($NDK_VERSION) is required" >&2
    exit 1
fi
if [ -e "$output_root" ]; then
    echo "OpenSSL output already exists: $output_root" >&2
    echo "Remove it explicitly before rebuilding" >&2
    exit 1
fi

build_root=$(mktemp -d "${TMPDIR:-/tmp}/nekokem-openssl-android.XXXXXX")
cleanup()
{
    if [ -n "${build_root:-}" ] && [ -d "$build_root" ]; then
        rm -rf -- "$build_root"
    fi
}
trap cleanup EXIT INT TERM

archive="$build_root/openssl-$OPENSSL_VERSION.tar.gz"
source_url="https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"

curl -fL --retry 3 -o "$archive" "$source_url"
printf '%s  %s\n' "$OPENSSL_SHA256" "$archive" | sha256sum -c -
tar -xzf "$archive" -C "$build_root"

toolchain_root="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
toolchain_bin="$toolchain_root/bin"
ndk_sysroot="$toolchain_root/sysroot"
install_root="$build_root/install"

if [ ! -x "$toolchain_bin/${compiler_prefix}-linux-android${ANDROID_API}-clang" ]; then
    echo "NDK $abi clang not found under $toolchain_bin" >&2
    exit 1
fi

cd "$build_root/openssl-$OPENSSL_VERSION"
export ANDROID_NDK_ROOT
PATH="$toolchain_bin:$PATH"
export PATH

CFLAGS="--sysroot=$ndk_sysroot" \
    ./Configure "$openssl_target" "-D__ANDROID_API__=$ANDROID_API" \
        no-shared no-tests no-apps no-docs no-legacy \
        --prefix="$install_root" --openssldir="$install_root/ssl" \
        --libdir=lib
make -j"${JOBS:-4}" build_sw
make install_sw
install -m 0644 LICENSE.txt "$install_root/LICENSE.txt"

mkdir -p "$(dirname -- "$output_root")"
mv "$install_root" "$output_root"
echo "Installed OpenSSL $OPENSSL_VERSION for $abi at $output_root"

#!/bin/sh

set -eu

OPENSSL_VERSION=3.5.6
OPENSSL_SHA256=deae7c80cba99c4b4f940ecadb3c3338b13cb77418409238e57d7f31f2a3b736
ANDROID_API=26
NDK_VERSION=28.2.13676358

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
output_root="$project_dir/app/src/main/cpp/third_party/openssl/arm64-v8a"

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

if [ ! -x "$toolchain_bin/aarch64-linux-android${ANDROID_API}-clang" ]; then
    echo "NDK arm64 clang not found under $toolchain_bin" >&2
    exit 1
fi

cd "$build_root/openssl-$OPENSSL_VERSION"
export ANDROID_NDK_ROOT
PATH="$toolchain_bin:$PATH"
export PATH

CFLAGS="--sysroot=$ndk_sysroot" \
    ./Configure android-arm64 "-D__ANDROID_API__=$ANDROID_API" \
        no-shared no-tests no-apps no-docs no-legacy \
        --prefix="$install_root" --openssldir="$install_root/ssl" \
        --libdir=lib
make -j"${JOBS:-4}" build_sw
make install_sw
install -m 0644 LICENSE.txt "$install_root/LICENSE.txt"

mkdir -p "$(dirname -- "$output_root")"
mv "$install_root" "$output_root"
echo "Installed OpenSSL $OPENSSL_VERSION for arm64-v8a at $output_root"

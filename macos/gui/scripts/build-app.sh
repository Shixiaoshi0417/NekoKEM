#!/usr/bin/env bash
# Builds NekoKEM.app for Apple Silicon: the Rust bridge with the C17 Core as a
# static library, then the SwiftUI app linked against it, ad-hoc signed.
#   NEKOKEM_OPENSSL_PREFIX=<pinned static OpenSSL> build-app.sh <output directory>
set -Eeuo pipefail

if [[ $# != 1 || $(uname -s) != Darwin || $(uname -m) != arm64 ]]; then
    printf 'Usage: %s <output directory> (native macOS arm64 only)\n' "$0" >&2
    exit 2
fi
: "${NEKOKEM_OPENSSL_PREFIX:?Build the pinned static OpenSSL first (macos/scripts/build-openssl.sh)}"
root=$(cd "$(dirname "$0")/../../.." && pwd -P)
mkdir -p "$1"
output=$(cd "$1" && pwd -P)
version=$(sed -n 's/^version = "\(.*\)"$/\1/p' "$root/desktop/bridge/Cargo.toml" | head -n 1)
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Cannot read the bridge version' >&2; exit 1; }
export MACOSX_DEPLOYMENT_TARGET=${MACOSX_DEPLOYMENT_TARGET:-11.0}

cargo build --locked --release --target aarch64-apple-darwin --manifest-path "$root/desktop/bridge/Cargo.toml"
# Only the static library: beside the dylib the linker would pick the dylib.
bridge="$root/desktop/bridge/target/aarch64-apple-darwin/release"
mkdir -p "$bridge/static"
cp "$bridge/libnekokem_native.a" "$bridge/static/libnekokem_native.a"

NEKOKEM_BRIDGE_DIR="$bridge/static" swift build -c release --arch arm64 --package-path "$root/macos/gui"
binary="$(NEKOKEM_BRIDGE_DIR="$bridge/static" swift build -c release --arch arm64 --package-path "$root/macos/gui" --show-bin-path)/NekoKEM"
[[ -x "$binary" ]] || { echo 'The Swift build produced no executable' >&2; exit 1; }

app="$output/NekoKEM.app"
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$binary" "$app/Contents/MacOS/NekoKEM"
cp "$root/desktop-compose/src/main/resources/strings.json" "$app/Contents/Resources/strings.json"
cp "$NEKOKEM_OPENSSL_PREFIX/LICENSE.txt" "$app/Contents/Resources/OpenSSL-LICENSE.txt"
cp "$root/LICENSE" "$app/Contents/Resources/LICENSE"

# The icon set from the project's icon.
iconset=$(mktemp -d "${TMPDIR:-/tmp}/nekokem-icon.XXXXXX")/NekoKEM.iconset
mkdir -p "$iconset"
for size in 16 32 128 256 512; do
    sips -z "$size" "$size" "$root/icon.png" --out "$iconset/icon_${size}x${size}.png" >/dev/null
    sips -z "$((size * 2))" "$((size * 2))" "$root/icon.png" --out "$iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$iconset" -o "$app/Contents/Resources/NekoKEM.icns"
rm -rf "$(dirname "$iconset")"

cat > "$app/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>NekoKEM</string>
    <key>CFBundleDisplayName</key><string>NekoKEM</string>
    <key>CFBundleIdentifier</key><string>com.shixiaoshi0417.nekokem.desktop</string>
    <key>CFBundleExecutable</key><string>NekoKEM</string>
    <key>CFBundleIconFile</key><string>NekoKEM</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>$version</string>
    <key>CFBundleVersion</key><string>$version</string>
    <key>LSMinimumSystemVersion</key><string>13.0</string>
    <key>LSApplicationCategoryType</key><string>public.app-category.utilities</string>
    <key>NSHighResolutionCapable</key><true/>
    <key>NSPrincipalClass</key><string>NSApplication</string>
</dict>
</plist>
PLIST
plutil -lint "$app/Contents/Info.plist"
codesign --force --sign - --timestamp=none "$app"
codesign --verify --strict "$app"
# libcrypto is linked in: the app must not load an OpenSSL from the system.
if otool -L "$app/Contents/MacOS/NekoKEM" | grep -Eq 'libcrypto|libssl'; then
    echo 'NekoKEM.app links a dynamic OpenSSL' >&2
    exit 1
fi
printf '%s\n' "$app"

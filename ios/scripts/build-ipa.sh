#!/usr/bin/env bash
# Builds NekoKEM.app for iOS: the Rust bridge with the C17 Core as a static
# library, then the SwiftUI sources shared with the macOS app plus ios/Sources,
# compiled with swiftc (no Xcode project).
#   NEKOKEM_OPENSSL_PREFIX=<pinned static OpenSSL> build-ipa.sh <output directory> device|simulator
# device: an arm64 iPhone and iPad app, not signed, packed as
#   NekoKEM-iOS-arm64-unsigned.ipa with ios-SHA256SUMS.txt. Sideloading tools
#   (AltStore, SideStore, TrollStore and the like) sign it on installation.
# simulator: NekoKEM.app for the arm64 simulator, signed ad hoc as the
#   simulator requires, for the CI self-test and screenshots.
set -Eeuo pipefail

if [[ $# != 2 || ( $2 != device && $2 != simulator ) || $(uname -s) != Darwin || $(uname -m) != arm64 ]]; then
    printf 'Usage: %s <output directory> device|simulator (native macOS arm64 only)\n' "$0" >&2
    exit 2
fi
: "${NEKOKEM_OPENSSL_PREFIX:?Build the pinned static OpenSSL first (ios/scripts/build-openssl.sh)}"
platform=$2
root=$(cd "$(dirname "$0")/../.." && pwd -P)
mkdir -p "$1"
output=$(cd "$1" && pwd -P)
version=$(sed -n 's/^version = "\(.*\)"$/\1/p' "$root/desktop/bridge/Cargo.toml" | head -n 1)
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Cannot read the bridge version' >&2; exit 1; }
source_sha=${NEKOKEM_SOURCE_SHA:-$(git -C "$root" rev-parse HEAD)}
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]] || { echo 'NEKOKEM_SOURCE_SHA must be a full commit SHA' >&2; exit 1; }
readonly minimum=16.0
export IPHONEOS_DEPLOYMENT_TARGET=$minimum
if [[ $platform == device ]]; then
    rust_target=aarch64-apple-ios sdk_name=iphoneos swift_target=arm64-apple-ios$minimum
    supported=iPhoneOS platform_name=IOS platform_id=2
else
    rust_target=aarch64-apple-ios-sim sdk_name=iphonesimulator swift_target=arm64-apple-ios$minimum-simulator
    supported=iPhoneSimulator platform_name=IOSSIMULATOR platform_id=7
fi
sdk=$(xcrun --sdk "$sdk_name" --show-sdk-path)
sdk_version=$(xcrun --sdk "$sdk_name" --show-sdk-version)

# Only the static library: the cdylib is for the Compose desktop app.
cargo rustc --locked --release --lib --crate-type staticlib --target "$rust_target" \
    --manifest-path "$root/desktop/bridge/Cargo.toml"
bridge="$root/desktop/bridge/target/$rust_target/release"
mkdir -p "$bridge/static"
cp "$bridge/libnekokem_native.a" "$bridge/static/libnekokem_native.a"

work=$(mktemp -d "${TMPDIR:-/tmp}/nekokem-ios.XXXXXX")
trap 'rm -rf -- "$work"' EXIT
# SwiftPM makes this module map for the macOS app; swiftc needs it written out.
mkdir -p "$work/modules/CNekoKEM"
cat > "$work/modules/CNekoKEM/module.modulemap" <<MAP
module CNekoKEM {
    header "$root/macos/gui/Sources/CNekoKEM/include/nekokem_desktop.h"
    export *
}
MAP
sources=()
for file in "$root"/macos/gui/Sources/NekoKEM/*.swift "$root"/ios/Sources/*.swift; do
    # main.swift starts the macOS app; ios/Sources has the iOS entry point.
    [[ $(basename "$file") == main.swift && $file == "$root/macos/"* ]] || sources+=("$file")
done

app="$output/NekoKEM.app"
rm -rf "$app"
mkdir -p "$app"
xcrun --sdk "$sdk_name" swiftc -swift-version 5 -O -whole-module-optimization -parse-as-library \
    -module-name NekoKEM -target "$swift_target" -sdk "$sdk" \
    -I "$work/modules/CNekoKEM" -L "$bridge/static" -lnekokem_native -liconv \
    -Xlinker -dead_strip -o "$app/NekoKEM" "${sources[@]}"

cp "$root/desktop-compose/src/main/resources/strings.json" "$app/strings.json"
cp "$NEKOKEM_OPENSSL_PREFIX/LICENSE.txt" "$app/OpenSSL-LICENSE.txt"
cp "$root/LICENSE" "$app/LICENSE"
printf 'APPL????' > "$app/PkgInfo"
# The icon at every size the home screen, Spotlight and Settings use.
for spec in 20:2 20:3 29:2 29:3 40:2 40:3 60:2 60:3 76:1 76:2 83.5:2; do
    points=${spec%%:*} scale=${spec##*:}
    pixels=$(python3 -c "print(round($points * $scale))")
    name="AppIcon${points}x${points}"
    [[ $scale == 1 ]] || name="$name@${scale}x"
    sips -z "$pixels" "$pixels" "$root/icon.png" --out "$app/$name.png" >/dev/null
done
python3 - "$app/build-metadata.json" "$source_sha" "$version" "$platform" "$sdk_version" <<'PY'
import json
import sys
with open(sys.argv[1], 'w', encoding='utf-8') as stream:
    json.dump({'source_sha': sys.argv[2], 'version': sys.argv[3], 'platform': sys.argv[4],
               'sdk_version': sys.argv[5], 'minimum_ios': '16.0', 'signed': False},
              stream, sort_keys=True, indent=2)
    stream.write('\n')
PY

cat > "$app/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key><string>en</string>
    <key>CFBundleDisplayName</key><string>NekoKEM</string>
    <key>CFBundleExecutable</key><string>NekoKEM</string>
    <key>CFBundleIdentifier</key><string>com.shixiaoshi0417.nekokem.ios</string>
    <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
    <key>CFBundleName</key><string>NekoKEM</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>$version</string>
    <key>CFBundleVersion</key><string>$version</string>
    <key>CFBundleSupportedPlatforms</key><array><string>$supported</string></array>
    <key>CFBundleLocalizations</key><array><string>en</string><string>zh-Hans</string><string>zh-Hant</string><string>ja</string><string>ko</string></array>
    <key>CFBundleIcons</key><dict>
        <key>CFBundlePrimaryIcon</key><dict>
            <key>CFBundleIconFiles</key><array><string>AppIcon20x20</string><string>AppIcon29x29</string><string>AppIcon40x40</string><string>AppIcon60x60</string></array>
            <key>CFBundleIconName</key><string>AppIcon</string>
        </dict>
    </dict>
    <key>CFBundleIcons~ipad</key><dict>
        <key>CFBundlePrimaryIcon</key><dict>
            <key>CFBundleIconFiles</key><array><string>AppIcon20x20</string><string>AppIcon29x29</string><string>AppIcon40x40</string><string>AppIcon76x76</string><string>AppIcon83.5x83.5</string></array>
            <key>CFBundleIconName</key><string>AppIcon</string>
        </dict>
    </dict>
    <key>DTPlatformName</key><string>$sdk_name</string>
    <key>DTPlatformVersion</key><string>$sdk_version</string>
    <key>DTSDKName</key><string>$sdk_name$sdk_version</string>
    <key>LSRequiresIPhoneOS</key><true/>
    <key>MinimumOSVersion</key><string>$minimum</string>
    <key>NSFaceIDUsageDescription</key><string>NekoKEM fills in the private key password saved in Keychain after Face ID.</string>
    <key>UIApplicationSceneManifest</key><dict>
        <key>UIApplicationSupportsMultipleScenes</key><false/>
    </dict>
    <key>UIApplicationSupportsIndirectInputEvents</key><true/>
    <key>UIDeviceFamily</key><array><integer>1</integer><integer>2</integer></array>
    <key>UIFileSharingEnabled</key><true/>
    <key>LSSupportsOpeningDocumentsInPlace</key><true/>
    <key>UILaunchScreen</key><dict/>
    <key>UIRequiredDeviceCapabilities</key><array><string>arm64</string></array>
    <key>UISupportedInterfaceOrientations</key><array>
        <string>UIInterfaceOrientationPortrait</string>
        <string>UIInterfaceOrientationLandscapeLeft</string>
        <string>UIInterfaceOrientationLandscapeRight</string>
    </array>
    <key>UISupportedInterfaceOrientations~ipad</key><array>
        <string>UIInterfaceOrientationPortrait</string>
        <string>UIInterfaceOrientationPortraitUpsideDown</string>
        <string>UIInterfaceOrientationLandscapeLeft</string>
        <string>UIInterfaceOrientationLandscapeRight</string>
    </array>
</dict>
</plist>
PLIST
plutil -lint "$app/Info.plist"

# The executable is for the chosen platform, and libcrypto is linked in.
xcrun vtool -show-build "$app/NekoKEM" | tee "$work/build.txt"
grep -Eq "^ *platform ($platform_name|$platform_id)$" "$work/build.txt" || {
    echo "NekoKEM is not built for the iOS $platform" >&2
    exit 1
}
if xcrun otool -L "$app/NekoKEM" | grep -Eq 'libcrypto|libssl|libnekokem'; then
    echo 'NekoKEM links a dynamic OpenSSL or bridge' >&2
    exit 1
fi
[[ $(xcrun lipo -archs "$app/NekoKEM") == arm64 ]] || { echo 'NekoKEM is not arm64 only' >&2; exit 1; }

if [[ $platform == simulator ]]; then
    codesign --force --sign - --timestamp=none "$app"
    codesign --verify --strict "$app"
    printf '%s\n' "$app"
    exit 0
fi

# Unsigned: no signature beyond the one the linker adds to every arm64 binary,
# no provisioning profile and no entitlements.
[[ ! -e "$app/_CodeSignature" && ! -e "$app/embedded.mobileprovision" ]]
ipa="$output/NekoKEM-iOS-arm64-unsigned.ipa"
rm -f "$ipa"
mkdir -p "$work/package/Payload"
cp -R "$app" "$work/package/Payload/"
# Fixed times and order, so the same build packs to the same bytes.
find "$work/package/Payload" -exec touch -h -t 202001010000 {} +
(cd "$work/package" && find Payload | LC_ALL=C sort | zip -q -X -D -@ "$ipa")
python3 - "$ipa" "$version" <<'PY'
import plistlib
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    names = archive.namelist()
    assert all(name.startswith('Payload/NekoKEM.app/') for name in names), names
    info = plistlib.loads(archive.read('Payload/NekoKEM.app/Info.plist'))
    assert info['CFBundleShortVersionString'] == sys.argv[2], info
    assert 'Payload/NekoKEM.app/NekoKEM' in names and 'Payload/NekoKEM.app/strings.json' in names
    assert not any('_CodeSignature' in name or name.endswith('.mobileprovision') for name in names)
print('NekoKEM-iOS-arm64-unsigned.ipa: Payload/NekoKEM.app checked')
PY
(cd "$output" && shasum -a 256 NekoKEM-iOS-arm64-unsigned.ipa > ios-SHA256SUMS.txt)
printf '%s\n' "$ipa"

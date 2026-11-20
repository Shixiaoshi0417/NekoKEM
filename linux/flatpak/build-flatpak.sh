#!/usr/bin/env bash
# Builds the NekoKEM .flatpak bundle from one architecture's CLI and GUI release
# archives, with the manifest in this directory. Nothing is compiled: the
# archives are unpacked into the Flatpak as they are.
#   build-flatpak.sh ARCH VERSION CLI.tar.gz GUI.tar.gz OUTPUT.flatpak
# Needs flatpak and flatpak-builder; the runtime and SDK come from Flathub.
set -Eeuo pipefail

arch=$1
version=$2
cli=$3
gui=$4
bundle=$5
here=$(cd "$(dirname "$0")" && pwd)
app_id=io.github.shixiaoshi0417.NekoKEM

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

python3 "$here/../../scripts/update_flatpak_manifest.py" "$version" \
    --local "$work/manifest" --arch "$arch" --cli "$cli" --gui "$gui"

flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
# Runners and containers have no FUSE for rofiles-fuse.
flatpak-builder --user --arch="$arch" --install-deps-from=flathub --disable-rofiles-fuse \
    --force-clean --default-branch=stable --repo="$work/repo" --state-dir="$work/state" \
    "$work/build" "$work/manifest/$app_id.json"
# The bundle names Flathub, so installing it also installs the runtime from there.
flatpak build-bundle --arch="$arch" \
    --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo \
    "$work/repo" "$bundle" "$app_id" stable
test -s "$bundle"

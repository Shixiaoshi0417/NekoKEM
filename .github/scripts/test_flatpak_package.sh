#!/usr/bin/env bash
# Installs a NekoKEM .flatpak bundle for the current user, checks what it
# exports and opens its GUI under Xvfb, in English and in Chinese.
#   test_flatpak_package.sh BUNDLE VERSION OUTPUT_DIR
# Needs flatpak, xvfb-run, xdotool, xprop, ImageMagick's import,
# desktop-file-validate and appstreamcli.
set -Eeuo pipefail

bundle=$1
version=$2
output=$3
app_id=io.github.shixiaoshi0417.NekoKEM
mkdir -p "$output"

flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak install --user --noninteractive --assumeyes "$bundle"
flatpak info --user "$app_id" | tee "$output/flatpak-info.txt"

# The CLI is in the same Flatpak.
test "$(flatpak run --user --command=nekokem "$app_id" --version)" = "NekoKEM $version"

# The menu entry, icon and AppStream data are exported for the desktop and software centres.
exports="$HOME/.local/share/flatpak/exports/share"
desktop="$exports/applications/$app_id.desktop"
desktop-file-validate "$desktop"
test -s "$exports/icons/hicolor/128x128/apps/$app_id.png"
location=$(flatpak info --user --show-location "$app_id")
metainfo="$location/files/share/metainfo/$app_id.metainfo.xml"
appstreamcli validate --no-net --explain "$metainfo"
grep -q "<release version=\"$version\"" "$metainfo"

# The menu entry's command opens the window, which carries the entry's WM_CLASS.
wm_class=$(sed -n 's/^StartupWMClass=//p' "$desktop")
for language in en_US zh_CN; do
    export LANG="$language.UTF-8"
    xvfb-run -a -s '-screen 0 1280x1024x24' bash -c '
        set -Eeuo pipefail
        trap '\''echo "GUI check failed at: $BASH_COMMAND" >&2; cat "$1/gui-$2.log" >&2'\'' ERR
        flatpak run --user "$0" > "$1/gui-$2.log" 2>&1 &
        pid=$!
        for attempt in $(seq 1 90); do
            if xdotool search --name "^NekoKEM$" > /dev/null 2>&1; then break; fi
            kill -0 "$pid" || { cat "$1/gui-$2.log"; exit 1; }
            sleep 1
        done
        windows=$(xdotool search --name "^NekoKEM$") || true
        [[ -n "$windows" ]] || { echo "No NekoKEM window" >&2; cat "$1/gui-$2.log"; exit 1; }
        for attempt in $(seq 1 20); do
            classes=$(for window in $(xdotool search --name "^NekoKEM$"); do xprop -id "$window" WM_CLASS || true; done)
            if [[ "$classes" == *"\"$3\""* ]]; then break; fi
            sleep 1
        done
        [[ "$classes" == *"\"$3\""* ]] || { echo "WM_CLASS is $classes, not $3" >&2; exit 1; }
        sleep 3
        import -window root "$1/flatpak-$2.png"
        kill -0 "$pid"
        kill -TERM "$pid"
        wait "$pid" || true
    ' "$app_id" "$output" "$language" "$wm_class"
done

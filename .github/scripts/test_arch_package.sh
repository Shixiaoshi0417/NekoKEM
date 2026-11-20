#!/usr/bin/env bash
# Runs as root in an archlinux container: builds linux/arch/PKGBUILD with makepkg
# from this run's CLI and GUI archives, installs it with pacman (which pulls the
# dependencies from the Arch repositories) and opens the installed GUI.
#   test_arch_package.sh PACKAGE_DIR OUTPUT_DIR
# PACKAGE_DIR holds the PKGBUILD rendered for this run's archives, the archives
# under their versioned source names, and the .SRCINFO the repository script wrote.
set -Eeuo pipefail

package=$1
output=$2
mkdir -p "$output"

pacman -Syu --noconfirm --needed base-devel namcap xorg-server-xvfb xdotool xorg-xprop imagemagick \
    desktop-file-utils > /dev/null

# makepkg refuses to run as root.
useradd --create-home builder
cp -r "$package" /home/builder/package
chown -R builder /home/builder/package
cd /home/builder/package

# The .SRCINFO the repository script writes is what makepkg itself writes.
su builder -c 'makepkg --printsrcinfo' > /tmp/makepkg.SRCINFO
diff -u .SRCINFO /tmp/makepkg.SRCINFO

# shellcheck disable=SC2016
mapfile -t depends < <(su builder -c 'source PKGBUILD && printf "%s\n" "${depends[@]}"')
pacman -S --noconfirm --needed --asdeps "${depends[@]}" > /dev/null
su builder -c 'makepkg --noconfirm'
# Informational: a repackaged application with its own Java runtime under /opt
# always draws the java-runtime and ELF-in-/opt findings.
namcap PKGBUILD nekokem-bin-*.pkg.tar.zst | tee "$output/namcap.txt" || true

pacman -U --noconfirm nekokem-bin-*.pkg.tar.zst
version=$(su builder -c 'source PKGBUILD && echo "$pkgver"')
test "$(nekokem --version)" = "NekoKEM $version"
test -s /opt/nekokem/lib/app/resources/libnekokem_native.so
test -x /usr/bin/nekokem-gui
test -s /usr/share/icons/hicolor/128x128/apps/nekokem.png
test -s /usr/share/licenses/nekokem-bin/OpenSSL-LICENSE.txt
desktop-file-validate /usr/share/applications/nekokem.desktop
pacman -Qi noto-fonts-cjk curl > /dev/null

# The menu entry's command opens the window, in English and in Chinese
# (rendered with the Noto Sans CJK the package depends on).
for language in en_US zh_CN; do
    export XDG_CONFIG_HOME="/tmp/config-$language" LANG="$language.UTF-8"
    mkdir -p "$XDG_CONFIG_HOME"
    command=$(sed -n 's/^Exec=//p' /usr/share/applications/nekokem.desktop)
    xvfb-run -a -s '-screen 0 1280x1024x24' bash -c '
        set -Eeuo pipefail
        trap '\''echo "GUI check failed at: $BASH_COMMAND" >&2; cat "$1/gui-$2.log" >&2'\'' ERR
        "$0" > "$1/gui-$2.log" 2>&1 &
        pid=$!
        for attempt in $(seq 1 60); do
            if xdotool search --name "^NekoKEM$" > /dev/null 2>&1; then break; fi
            kill -0 "$pid" || { cat "$1/gui-$2.log"; exit 1; }
            sleep 1
        done
        windows=$(xdotool search --name "^NekoKEM$") || true
        [[ -n "$windows" ]] || { echo "No NekoKEM window" >&2; cat "$1/gui-$2.log"; exit 1; }
        # The window belongs to the menu entry, so desktops group it under its icon.
        # The title can appear a moment before WM_CLASS is set.
        for attempt in $(seq 1 20); do
            classes=$(for window in $(xdotool search --name "^NekoKEM$"); do xprop -id "$window" WM_CLASS || true; done)
            if [[ "$classes" == *"\"$3\""* ]]; then break; fi
            sleep 1
        done
        [[ "$classes" == *"\"$3\""* ]] || { echo "WM_CLASS is $classes, not $3" >&2; exit 1; }
        sleep 3
        import -window root "$1/arch-$2.png"
        kill -0 "$pid"
        kill -TERM "$pid"
        wait "$pid" || true
    ' "$command" "$output" "$language" "$(sed -n 's/^StartupWMClass=//p' /usr/share/applications/nekokem.desktop)"
done

#!/usr/bin/env bash
# Run inside a fresh native Fedora container; only installation uses root.
set -Eeuo pipefail
[[ $# == 3 && $(id -u) == 0 ]] || { echo 'Usage: install_rpm_fedora.sh <rpm> <evidence-directory> <desktop-uid>' >&2; exit 2; }
package=$1
evidence=$2
desktop_uid=$3
[[ -f "$package" && "$evidence" == /evidence && "$desktop_uid" =~ ^[1-9][0-9]*$ ]] || exit 2
dnf --assumeyes install "$package" python3 xorg-x11-server-Xvfb xorg-x11-xauth \
    xdotool openbox dbus-daemon shadow-utils util-linux
[[ $(rpm -q --queryformat '%{ARCH}' neko-kem) == "$(uname -m)" ]]
rpm --verify neko-kem
useradd --create-home --user-group --uid "$desktop_uid" --shell /bin/bash nekokem-rpm-ci
install -d -m 0700 -o "$desktop_uid" -g "$(id -g nekokem-rpm-ci)" "$evidence"
runuser -u nekokem-rpm-ci -- dbus-run-session -- xvfb-run -a -s '-screen 0 1280x1024x24' \
    python3 /nekokem-tests/launch_linux.py /usr/bin/nekokem-gui --log "$evidence/rpm-startup.log"
rpm --verify neko-kem
printf 'Actual native Fedora %s RPM installation, package integrity and sandboxed window passed\n' "$(uname -m)"

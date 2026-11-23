#!/usr/bin/env bash
# Runs the simulator build of NekoKEM.app on an iPhone simulator: the bridge
# self-test, a normal start that reads the settings, and one screenshot of
# every page in light and dark appearance.
#   simulator-check.sh <NekoKEM.app for the simulator> <screenshot directory>
set -Eeuo pipefail

if [[ $# != 2 || ! -d $1 ]]; then
    printf 'Usage: %s <NekoKEM.app> <screenshot directory>\n' "$0" >&2
    exit 2
fi
app=$1
mkdir -p "$2"
screens=$(cd "$2" && pwd -P)
readonly bundle=com.shixiaoshi0417.nekokem.ios
readonly pages=(files-empty files encrypt-recipients encrypt-warning files-password files-done keys keys-export-private fingerprint contacts contacts-selected settings)

# A 6.7-inch iPhone where the runner has one, as the Android screenshots use.
device=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
devices = [d for runtime, items in json.load(sys.stdin)["devices"].items()
           if "iOS" in runtime for d in items if d["name"].startswith("iPhone")]
for wanted in ("iPhone 16 Plus", "iPhone 17 Pro Max", "iPhone 16 Pro Max", "iPhone 15 Plus"):
    for d in devices:
        if d["name"] == wanted:
            print(d["udid"], d["name"], sep="\t"); sys.exit()
d = devices[-1]
print(d["udid"], d["name"], sep="\t")')
udid=${device%%$'\t'*}
printf 'Simulator: %s\n' "${device#*$'\t'}"
xcrun simctl boot "$udid" 2>/dev/null || true
xcrun simctl bootstatus "$udid" -b
xcrun simctl status_bar "$udid" override --time 9:41 --dataNetwork wifi --wifiBars 3 \
    --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100 || true
xcrun simctl install "$udid" "$app"

# Waits until [log] has [line], or the time is up.
wait_for() {
    local log=$1 line=$2
    for _ in $(seq 1 120); do
        grep -Fq "$line" "$log" 2>/dev/null && return 0
        sleep 0.5
    done
    cat "$log" 2>/dev/null || true
    return 1
}

log="$screens/self-test.log"
xcrun simctl launch --terminate-running-process --stdout="$log" --stderr="$log" "$udid" "$bundle" --self-test
wait_for "$log" 'NekoKEM iOS self-test:'
cat "$log"
grep -Fq 'NekoKEM iOS self-test: ok' "$log"

log="$screens/startup.log"
SIMCTL_CHILD_NEKOKEM_NATIVE_STARTUP_EVIDENCE=1 \
    xcrun simctl launch --terminate-running-process --stdout="$log" --stderr="$log" "$udid" "$bundle"
wait_for "$log" 'NekoKEM native startup: settings-ready'
cat "$log"

for appearance in light dark; do
    xcrun simctl ui "$udid" appearance "$appearance"
    for page in "${pages[@]}"; do
        xcrun simctl launch --terminate-running-process "$udid" "$bundle" --demo "$page" >/dev/null
        # Let the page and any sheet finish drawing.
        sleep 3
        xcrun simctl io "$udid" screenshot --type=png "$screens/$appearance-$page.png" >/dev/null
        test -s "$screens/$appearance-$page.png"
    done
done
xcrun simctl terminate "$udid" "$bundle" || true
printf 'Screenshots in %s\n' "$screens"

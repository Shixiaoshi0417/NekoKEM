#!/bin/bash
set -Eeuo pipefail

# Repeats the in-process JNI, callback, SAF, contacts and UI phases against the
# R8-minified, non-debuggable build. Phases that need run-as or emulator root
# stay with the debug run in run_android_device_tests.sh.
report_dir=${1:?report directory required}
mkdir -p "$report_dir"
collect_evidence() {
    adb exec-out screencap -p > "$report_dir/final-screen.png" || true
    adb logcat -d -s AndroidRuntime > "$report_dir/android-runtime.txt" || true
    adb logcat -d -b all > "$report_dir/logcat.txt" || true
}
trap 'collect_evidence || true' EXIT
apk_root=android/app/build/outputs/apk
# The debug run ends by restarting adbd as root and changing the system locale,
# display size and density; wait until the package manager answers again.
wait_for_package_manager() {
    timeout 60s adb wait-for-device
    for attempt in $(seq 1 60); do
        if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] &&
            adb shell pm path android > /dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    echo 'The package manager did not become ready' >&2
    return 1
}
# An install can still lose its binder pipe while system_server settles; the
# APK is unchanged between attempts, so only the transport is retried.
install_apk() {
    for attempt in 1 2 3; do
        wait_for_package_manager
        if adb install "$@"; then return 0; fi
        sleep 5
    done
    return 1
}
wait_for_package_manager
# The debug run left a debuggable install with app data; start from a clean one.
adb uninstall com.shixiaoshi0417.nekokem || true
install_apk "$apk_root/minified/app-minified.apk"
install_apk -r "$apk_root/androidTest/minified/app-minified-androidTest.apk"
if adb shell run-as com.shixiaoshi0417.nekokem true 2>/dev/null; then
    echo 'The minified device-test APK must not be debuggable' >&2
    exit 1
fi
runner=com.shixiaoshi0417.nekokem.test/com.shixiaoshi0417.nekokem.TemporaryKeyInstrumentation
for phase in persist restart full; do
    adb shell am force-stop com.shixiaoshi0417.nekokem
    arguments=()
    if [[ "$phase" != full ]]; then arguments=(-e languagePhase "$phase"); fi
    adb shell am instrument -w "${arguments[@]}" "$runner" | tee "$report_dir/$phase.txt"
    grep -Fx 'INSTRUMENTATION_RESULT: result=temporary-key-tests-passed' "$report_dir/$phase.txt"
    grep -Fx 'INSTRUMENTATION_CODE: -1' "$report_dir/$phase.txt"
done
collect_evidence
trap - EXIT

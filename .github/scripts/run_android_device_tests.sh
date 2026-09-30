#!/bin/bash
set -Eeuo pipefail

report_dir=${1:?report directory required}
mkdir -p "$report_dir"
collect_evidence() {
    adb exec-out run-as com.shixiaoshi0417.nekokem tar -czf - -C cache i18n-screens > "$report_dir/screens.tar.gz" || true
    adb exec-out screencap -p > "$report_dir/final-screen.png" || true
    adb logcat -d -s AndroidRuntime > "$report_dir/android-runtime.txt" || true
}
# Collection cannot change test status; every phase still requires both success markers.
trap collect_evidence EXIT
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
runner=com.shixiaoshi0417.nekokem.test/com.shixiaoshi0417.nekokem.TemporaryKeyInstrumentation
for phase in persist restart full; do
    adb shell am force-stop com.shixiaoshi0417.nekokem
    arguments=()
    if [[ "$phase" != full ]]; then arguments=(-e languagePhase "$phase"); fi
    adb shell am instrument -w "${arguments[@]}" "$runner" | tee "$report_dir/$phase.txt"
    grep -Fx 'INSTRUMENTATION_RESULT: result=temporary-key-tests-passed' "$report_dir/$phase.txt"
    grep -Fx 'INSTRUMENTATION_CODE: -1' "$report_dir/$phase.txt"
done

# Change the disposable emulator's actual OS language between app processes.
# App production permissions and filesystem checks remain unchanged.
adb root
adb wait-for-device
for system_locale in ja-JP de-DE; do
    adb shell settings put system system_locales "$system_locale"
    adb shell setprop persist.sys.locale "$system_locale"
    adb shell setprop sys.boot_completed 0
    adb shell stop
    adb shell start
    booted=0
    for attempt in $(seq 1 120); do
        if [[ $(adb shell getprop sys.boot_completed | tr -d '\r') == 1 ]]; then booted=1; break; fi
        sleep 1
    done
    [[ "$booted" == 1 ]]
    language=${system_locale%%-*}
    adb shell am instrument -w -e languagePhase system -e expectedSystemLanguage "$language" "$runner" | tee "$report_dir/system-$language.txt"
    grep -Fx 'INSTRUMENTATION_RESULT: result=temporary-key-tests-passed' "$report_dir/system-$language.txt"
    grep -Fx 'INSTRUMENTATION_CODE: -1' "$report_dir/system-$language.txt"
done

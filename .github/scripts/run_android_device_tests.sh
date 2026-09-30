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

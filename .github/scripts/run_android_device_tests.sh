#!/bin/bash
set -Eeuo pipefail

report_dir=${1:?report directory required}
mkdir -p "$report_dir"
collect_evidence() {
    # Compress on the host: older Android tar may close the adb stream before its
    # gzip child finishes, leaving a truncated archive despite a zero exit code.
    adb exec-out run-as com.shixiaoshi0417.nekokem tar -cf - -C cache i18n-screens |
        gzip > "$report_dir/screens.tar.gz" || return 1
    adb exec-out screencap -p > "$report_dir/final-screen.png" || true
    adb logcat -d -s AndroidRuntime > "$report_dir/android-runtime.txt" || true
    adb logcat -d -b all > "$report_dir/logcat.txt" || true
    adb shell dumpsys activity activities > "$report_dir/activities.txt" || true
    adb shell dumpsys activity lastanr > "$report_dir/last-anr.txt" || true
    adb shell dumpsys window > "$report_dir/windows.txt" || true
}
# Failure collection preserves test status; successful runs verify the complete archive.
trap 'collect_evidence || true' EXIT
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

# The helper exists only in the test APK and runs outside the app as emulator root.
# The production manifest does not request CHANGE_CONFIGURATION or hidden API access.
adb root
adb wait-for-device
adb push android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /data/local/tmp/nekokem-locale-tests.apk
for system_locale in ja-JP de-DE; do
    language=${system_locale%%-*}
    screenshot_prefix=large-dark
    if [[ "$language" == de ]]; then
        adb shell cmd uimode night no
        adb shell wm size 720x1280
        adb shell wm density 320
        screenshot_prefix=small-light
    fi
    adb shell run-as com.shixiaoshi0417.nekokem rm -f cache/i18n-system-ready
    timeout 90s adb shell am instrument -w -e languagePhase system -e expectedSystemLanguage "$language" -e screenshotPrefix "$screenshot_prefix" "$runner" > "$report_dir/system-$language.txt" &
    instrument_pid=$!
    ready=0
    for attempt in $(seq 1 60); do
        if adb shell "run-as com.shixiaoshi0417.nekokem sh -c 'test -f cache/i18n-system-ready'"; then ready=1; break; fi
        sleep 1
    done
    [[ "$ready" == 1 ]]
    adb shell CLASSPATH=/data/local/tmp/nekokem-locale-tests.apk app_process /system/bin com.shixiaoshi0417.nekokem.SystemLocaleControl "$system_locale"
    wait "$instrument_pid"
    cat "$report_dir/system-$language.txt"
    grep -Fx 'INSTRUMENTATION_RESULT: result=temporary-key-tests-passed' "$report_dir/system-$language.txt"
    grep -Fx 'INSTRUMENTATION_CODE: -1' "$report_dir/system-$language.txt"
done

# Repeat the UI language/layout assertions at 360x640 dp in light mode.
# JNI/SAF gates already executed in full above; this phase exercises the UI only.
adb shell am force-stop com.shixiaoshi0417.nekokem
adb shell am instrument -w -e languagePhase layout -e screenshotPrefix small-light "$runner" | tee "$report_dir/layout-small-light.txt"
grep -Fx 'INSTRUMENTATION_RESULT: result=temporary-key-tests-passed' "$report_dir/layout-small-light.txt"
grep -Fx 'INSTRUMENTATION_CODE: -1' "$report_dir/layout-small-light.txt"

collect_evidence
python3 - "$report_dir/screens.tar.gz" <<'PY'
import sys
import tarfile
with tarfile.open(sys.argv[1], 'r:gz') as archive:
    for prefix in ('large-dark', 'small-light'):
        for name in ('zh-CN', 'zh-TW', 'en', 'ja', 'ko', 'settings', 'language-picker'):
            image = archive.extractfile(f'i18n-screens/{prefix}-{name}.png')
            assert image is not None and image.read(8) == b'\x89PNG\r\n\x1a\n'
    for name in ('large-dark-system-ja', 'small-light-system-de'):
        image = archive.extractfile(f'i18n-screens/{name}.png')
        assert image is not None and image.read(8) == b'\x89PNG\r\n\x1a\n'
    # Consume the complete gzip stream, including its trailer.
    archive.getmembers()
print('All 16 rendered language/layout screenshots archived')
PY
gzip -t "$report_dir/screens.tar.gz"
trap - EXIT

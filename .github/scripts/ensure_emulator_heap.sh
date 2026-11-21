#!/bin/bash
# The emulator's qemu-props service sets dalvik.vm.heapsize (576m) during boot,
# racing the zygote. When the zygote starts first, ART falls back to a 16 MiB
# heap and system_server later dies with OutOfMemoryError ("System has
# crashed") partway through the device tests. Detect that order in the boot
# log and restart the framework, so the zygote reads the final heap size.
# Newer images pass the heap size as a boot property (androidboot.*), which is
# set before the zygote starts; they have no qemu-props line and need nothing.
set -Eeuo pipefail

boot_log=$(adb logcat -d -b all -s AndroidRuntime:D qemu-props:I)
zygote_line=$(grep -n 'START com.android.internal.os.ZygoteInit' <<<"$boot_log" | head -n 1 | cut -d: -f1 || true)
heap_line=$(grep -n 'qemu-props: received: dalvik.vm.heapsize=' <<<"$boot_log" | head -n 1 | cut -d: -f1 || true)
heap_size=$(adb shell getprop dalvik.vm.heapsize | tr -d '\r')
echo "dalvik.vm.heapsize=$heap_size, zygote start line ${zygote_line:-none}, qemu-props heap line ${heap_line:-none}"

if [[ -z "$heap_line" ]]; then
    echo 'qemu-props did not set dalvik.vm.heapsize; no start-order race'
    exit 0
fi
if [[ -n "$zygote_line" && "$heap_line" -lt "$zygote_line" ]]; then
    echo 'The zygote started after dalvik.vm.heapsize was set'
    exit 0
fi
if [[ -z "$heap_size" ]]; then
    echo 'dalvik.vm.heapsize is still unset after boot' >&2
    exit 1
fi

# A zygote start missing from the log is treated like an early start.
echo 'The zygote may have started before dalvik.vm.heapsize was set; restarting the framework'
old_pid=$(adb shell pidof system_server | tr -d '\r')
timeout 30s adb root || true
timeout 60s adb wait-for-device
adb logcat -c
adb shell stop
adb shell setprop sys.boot_completed 0 || true
adb shell start
for attempt in $(seq 1 120); do
    pid=$(adb shell pidof system_server 2>/dev/null | tr -d '\r' || true)
    completed=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    if [[ -n "$pid" && "$pid" != "$old_pid" && "$completed" == 1 ]] &&
        adb shell service check activity 2>/dev/null | grep -q ': found'; then
        break
    fi
    if [[ "$attempt" == 120 ]]; then
        echo 'The framework did not finish restarting within 240 seconds' >&2
        exit 1
    fi
    sleep 2
done
adb logcat -d -b all -s AndroidRuntime:D | grep -q 'START com.android.internal.os.ZygoteInit' || {
    echo 'The zygote did not restart' >&2
    exit 1
}
timeout 30s adb unroot || true
timeout 60s adb wait-for-device
echo "system_server restarted as pid $pid with dalvik.vm.heapsize=$heap_size"

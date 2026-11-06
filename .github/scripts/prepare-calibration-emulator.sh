#!/usr/bin/env bash
# Disposable AOSP API29 test fixture only. Never run this against physical hardware.
set -euo pipefail
serial="${ANDROID_SERIAL:-emulator-5554}"
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Refusing a non-emulator fixture target.' >&2; exit 2; }
adb_command=(adb -s "$serial")
[[ "$("${adb_command[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
  echo 'Fixture target is not virtual Android.' >&2; exit 2;
}
reports="${CALIBRATION_SETUP_REPORTS:-app/build/reports/androidTests}"
mkdir -p "$reports"
diagnostics() {
  result=$?
  "${adb_command[@]}" logcat -d -b crash > "$reports/emulator-setup-crash.log" 2>&1 || true
  "${adb_command[@]}" shell getprop > "$reports/emulator-properties.txt" 2>&1 || true
  exit "$result"
}
trap diagnostics EXIT

# The default API29 image omits heapgrowthlimit and ART falls back to 16 MiB, even
# with a Pixel2/2GiB profile. A tablet-sized 256MiB heap exercises all 496*100 pairs.
# This changes only this disposable emulator, never the APK's production heap flags.
"${adb_command[@]}" root
"${adb_command[@]}" wait-for-device
"${adb_command[@]}" shell setprop dalvik.vm.heapgrowthlimit 256m
"${adb_command[@]}" shell setprop sys.boot_completed 0
"${adb_command[@]}" shell stop
"${adb_command[@]}" shell start
booted=false
for _ in $(seq 1 120); do
  if [[ "$("${adb_command[@]}" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then booted=true; break; fi
  sleep 1
done
[[ "$booted" == true ]] || { echo 'Virtual Android framework restart timed out.' >&2; exit 1; }
[[ "$("${adb_command[@]}" shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r')" == 256m ]]

# API29's Wi-Fi usability metrics can crash system_server when telephony returns
# null cell information. These fixture tests use injected sensors, not guest Wi-Fi.
"${adb_command[@]}" shell svc wifi disable
[[ "$("${adb_command[@]}" shell settings get global wifi_on | tr -d '\r')" == 0 ]]
"${adb_command[@]}" shell settings put global window_animation_scale 0
"${adb_command[@]}" shell settings put global transition_animation_scale 0
"${adb_command[@]}" shell settings put global animator_duration_scale 0

if [[ "${1:-}" == --landscape ]]; then
  "${adb_command[@]}" shell wm size 1920x1080
  "${adb_command[@]}" shell wm density 240
  [[ "$("${adb_command[@]}" shell wm size | tr -d '\r' | tail -n 1)" == 'Override size: 1920x1080' ]]
  [[ "$("${adb_command[@]}" shell wm density | tr -d '\r' | tail -n 1)" == 'Override density: 240' ]]
fi

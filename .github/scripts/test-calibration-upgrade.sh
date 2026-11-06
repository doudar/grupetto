#!/usr/bin/env bash
set -euo pipefail

# Run only on the disposable CI emulator, never a connected bike or personal Android device.
# Usage (after the emulator has booted): bash .github/scripts/test-calibration-upgrade.sh v0.0.50
legacy_tag="${1:?Pass the released tag to test: v0.0.49 or v0.0.50}"
case "$legacy_tag" in
  v0.0.49)
    legacy_commit=5061774aee3eaf6540bf8f9aedf448e2c7cd81d5
    legacy_version_code=51
    ;;
  v0.0.50)
    legacy_commit=7a543d18629e41c9ccc5758f185a4ce7da4931b9
    legacy_version_code=52
    ;;
  *) echo "Unsupported legacy release: $legacy_tag" >&2; exit 1 ;;
esac

if [[ "${CI:-}" != true ]]; then
  echo "This destructive test fixture is restricted to a disposable CI emulator." >&2
  exit 1
fi
command -v adb >/dev/null
root="$(git rev-parse --show-toplevel)"
cd "$root"
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  serial="$ANDROID_SERIAL"
else
  mapfile -t emulators < <(adb devices | awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}')
  if [[ "${#emulators[@]}" != 1 ]]; then
    echo "Expected exactly one booted virtual emulator; refusing to select another device." >&2
    exit 1
  fi
  serial="${emulators[0]}"
fi
if [[ ! "$serial" =~ ^emulator-[0-9]+$ ]]; then
  echo "Refusing non-emulator Android serial: $serial" >&2
  exit 1
fi
adb_command=(adb -s "$serial")
if [[ "$("${adb_command[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo "Target is not a verified virtual Android emulator; no app data was changed." >&2
  exit 1
fi
if [[ "$("${adb_command[@]}" shell getprop sys.boot_completed | tr -d '\r')" != 1 ]]; then
  echo "The virtual emulator has not completed booting." >&2
  exit 1
fi

# Shallow PR checkouts need only the exact allowlisted release tag, not arbitrary remote history.
if ! git rev-parse --verify "refs/tags/$legacy_tag^{commit}" >/dev/null 2>&1; then
  git fetch --no-tags origin "refs/tags/$legacy_tag:refs/tags/$legacy_tag"
fi
if [[ "$(git rev-parse "refs/tags/$legacy_tag^{commit}")" != "$legacy_commit" ]]; then
  echo "Release tag $legacy_tag no longer matches its verified source commit." >&2
  exit 1
fi

reports="$root/app/build/reports/calibration-upgrade/$legacy_tag"
mkdir -p "$reports" "$HOME/.android"
if [[ ! -f "$HOME/.android/debug.keystore" ]]; then
  keytool -genkeypair -keystore "$HOME/.android/debug.keystore" -storetype JKS \
    -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
    -validity 2 -dname "CN=Android Debug,O=Android,C=US"
fi

# Both builds use this same disposable debug key; the test needs no release credentials.
temporary_directory="$(mktemp -d /tmp/grupetto-upgrade.XXXXXX)"
legacy_tree="$temporary_directory/legacy"
cleanup() {
  if [[ -d "$legacy_tree" ]]; then
    git -C "$root" worktree remove --force "$legacy_tree"
  fi
  rmdir "$temporary_directory"
}
trap cleanup EXIT
git worktree add --detach "$legacy_tree" "$legacy_commit"
fixture_directory="$legacy_tree/app/src/androidTest/java/com/spop/poverlay/calibration"
mkdir -p "$fixture_directory"
cp "$root/.github/fixtures/CalibrationUpgradeSeedTest.kt" "$fixture_directory/CalibrationUpgradeSeedTest.kt"

(
  cd "$legacy_tree"
  bash ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
) 2>&1 | tee "$reports/legacy-build.log"
bash ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest \
  2>&1 | tee "$reports/current-build.log"

app_package=com.spop.poverlay
test_package=com.spop.poverlay.test
runner="$test_package/androidx.test.runner.AndroidJUnitRunner"
run_instrumentation() {
  local class_name="$1"
  local log_path="$2"
  "${adb_command[@]}" shell am instrument -w -r \
    -e class "com.spop.poverlay.calibration.$class_name" -e upgradeTest true \
    -e legacyTag "$legacy_tag" -e legacyVersionCode "$legacy_version_code" \
    "$runner" 2>&1 | tee "$log_path"
  # adb can return zero even when instrumentation fails or is skipped; require one passing test.
  if ! grep -Eq '^OK \(1 test\)' "$log_path" ||
     ! grep -Eq '^INSTRUMENTATION_CODE: -1' "$log_path" ||
     grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed' "$log_path"; then
    echo "Instrumentation did not complete exactly one passing test: $class_name" >&2
    return 1
  fi
}

# A clean start is allowed only here, on the verified CI emulator. Never clear between APKs.
for package in "$test_package" "$app_package"; do
  if "${adb_command[@]}" shell pm path "$package" | grep -q '^package:'; then
    "${adb_command[@]}" uninstall "$package"
  fi
done
"${adb_command[@]}" install "$legacy_tree/app/build/outputs/apk/debug/app-debug.apk"
"${adb_command[@]}" install "$legacy_tree/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
run_instrumentation CalibrationUpgradeSeedTest "$reports/seed-instrumentation.log"
"${adb_command[@]}" exec-out run-as "$app_package" cat files/grupetto-calibration.json \
  > "$reports/legacy-calibration.json"
"${adb_command[@]}" shell dumpsys package "$app_package" > "$reports/legacy-package.txt"

"${adb_command[@]}" shell am force-stop "$app_package"
"${adb_command[@]}" install -r "$root/app/build/outputs/apk/debug/app-debug.apk"
"${adb_command[@]}" install -r "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
run_instrumentation CalibrationUpgradeVerificationTest "$reports/upgrade-instrumentation.log"
"${adb_command[@]}" exec-out run-as "$app_package" cat files/grupetto-calibration.json \
  > "$reports/upgraded-calibration.json"
"${adb_command[@]}" shell dumpsys package "$app_package" > "$reports/upgraded-package.txt"
echo "Verified in-place APK upgrade from $legacy_tag (build $legacy_version_code) to the current checkout."

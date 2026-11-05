#!/usr/bin/env python3
"""Exercise fixture safety boundaries and failure propagation without Android hardware."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("prepare-calibration-emulator.sh")
BASH = r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else shutil.which("bash")

def bash_path(path):
    value = str(path).replace("\\", "/")
    return "/" + value[0].lower() + value[2:] if len(value) > 1 and value[1] == ":" else value

class EmulatorFixtureTests(unittest.TestCase):
    def invoke(self, **changes):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            log = root / "adb.log"
            fake = root / "adb"
            fake.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$MOCK_LOG"
[[ "$1" == -s && "$2" == emulator-5554 ]] || exit 99
shift 2
case "$*" in
  'shell getprop ro.kernel.qemu') echo "${MOCK_QEMU:-1}" ;;
  'shell getprop sys.boot_completed') echo 1 ;;
  'shell getprop dalvik.vm.heapgrowthlimit') echo "${MOCK_HEAP:-256m}" ;;
  'shell settings get global wifi_on') echo 0 ;;
  'shell svc wifi disable') exit "${MOCK_WIFI_EXIT:-0}" ;;
  'shell wm size') echo 'Override size: 1920x1080' ;;
  'shell wm density') echo 'Override density: 240' ;;
esac
''', encoding="utf-8", newline="\n")
            fake.chmod(0o755)
            env = dict(os.environ, ANDROID_SERIAL="emulator-5554", MOCK_LOG=bash_path(log))
            env.update(changes)
            command = [BASH, "--noprofile", "--norc", "-c", 'PATH="$1:$PATH" bash "$2" --landscape',
                       "fixture-test", bash_path(root), bash_path(SCRIPT.resolve())]
            result = subprocess.run(command, env=env, cwd=root, text=True, capture_output=True, timeout=30)
            return result, log.read_text() if log.exists() else "", root.joinpath("app/build/reports/androidTests/emulator-setup-crash.log").exists()

    def test_verified_virtual_fixture_configures_heap_wifi_and_geometry(self):
        result, log, evidence = self.invoke()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-s emulator-5554 shell setprop dalvik.vm.heapgrowthlimit 256m", log)
        self.assertIn("-s emulator-5554 shell wm size 1920x1080", log)
        self.assertTrue(evidence)

    def test_non_emulator_target_is_rejected_before_any_adb_operation(self):
        result, log, _ = self.invoke(ANDROID_SERIAL="192.0.2.1:5555")
        self.assertEqual(2, result.returncode)
        self.assertEqual("", log)

    def test_non_virtual_device_is_read_only_and_rejected(self):
        result, log, _ = self.invoke(MOCK_QEMU="0")
        self.assertEqual(2, result.returncode)
        self.assertNotIn(" root", log)
        self.assertNotIn("setprop", log)

    def test_setup_failure_preserves_original_status_and_crash_evidence(self):
        result, log, evidence = self.invoke(MOCK_WIFI_EXIT="52")
        self.assertEqual(52, result.returncode)
        self.assertNotIn("shell wm size", log)
        self.assertTrue(evidence)

    def test_unapplied_heap_setting_fails_before_wifi_or_tests(self):
        result, log, evidence = self.invoke(MOCK_HEAP="16m")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("svc wifi", log)
        self.assertTrue(evidence)

if __name__ == "__main__":
    unittest.main()

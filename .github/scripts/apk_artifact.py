#!/usr/bin/env python3
"""Verify APKs, stage flat CI artifacts, and fail closed before publication."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

PRODUCTION_CERT = "61ae87b671ea3f4d8095440d4c826da6dc938e7a90bc8114bea9bb945b53a2bb"
PACKAGE = "com.spop.poverlay"
APK_NAME = "app-release.apk"
MANIFEST_NAME = "apk-verification.json"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def tool_output(tools, name, *arguments):
    suffix = ".bat" if os.name == "nt" and name == "apksigner" else ".exe" if os.name == "nt" else ""
    return subprocess.run([str(Path(tools) / (name + suffix)), *map(str, arguments)],
                          check=True, capture_output=True, text=True).stdout


def verify(apk, tools, mode, event, debug_certificate=None):
    apk = Path(apk)
    require(apk.is_file() and apk.stat().st_size > 0, "APK is missing or empty")
    require(mode in ("production", "debug-fallback"), "Unknown signing mode")
    if mode == "debug-fallback":
        require(event == "pull_request", "Debug fallback is only allowed for pull requests")
        require(re.fullmatch(r"[0-9a-f]{64}", debug_certificate or "") is not None,
                "Debug fallback requires the disposable key's certificate fingerprint")
        require(debug_certificate != PRODUCTION_CERT, "Production APK must not be labelled debug fallback")
    signing = tool_output(tools, "apksigner", "verify", "--verbose", "--print-certs", apk)
    # Build-tools versions use either "Signer #1" or "V2 Signer:" prefixes.
    signer_counts = re.findall(r"^Number of signers: (\d+)\s*$", signing, re.MULTILINE)
    certificates = {value.lower() for value in re.findall(
        r"^.* certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", signing, re.MULTILINE)}
    expected = PRODUCTION_CERT if mode == "production" else debug_certificate
    require(signer_counts == ["1"] and certificates == {expected},
            "APK signing certificate does not match the selected signing mode")
    badging = tool_output(tools, "aapt", "dump", "badging", apk)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
    require(package is not None and package[1] == PACKAGE, "Unexpected APK package")
    require(int(package[2]) > 51, "APK versionCode must be greater than installed Peloton code 51")
    return {"package": package[1], "version_code": int(package[2]), "version_name": package[3],
            "certificate_sha256": expected, "signing_mode": mode,
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}, signing + "\n" + badging.splitlines()[0] + "\n"


def stage(apk, destination, tools, mode, event, source_sha, debug_certificate=None):
    require(re.fullmatch(r"[0-9a-f]{40}", source_sha) is not None, "Invalid source commit")
    metadata, report = verify(apk, tools, mode, event, debug_certificate)
    destination = Path(destination)
    require(not destination.exists(), "Artifact staging destination must be new")
    destination.mkdir(parents=True)
    filename = APK_NAME if mode == "production" else "app-debug-fallback.apk"
    shutil.copyfile(apk, destination / filename)
    metadata.update({"source_sha": source_sha, "apk_name": filename,
                     "peloton_in_place_update": mode == "production"})
    (destination / MANIFEST_NAME).write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    label = ("Production-signed APK; eligible for a compatible in-place Peloton update.\n"
             if mode == "production" else
             "DEBUG-SIGNED FALLBACK: test artifact only. Not a release or an in-place update for production-signed Peloton apps.\n")
    (destination / "apk-verification.txt").write_text(label + report, encoding="utf-8")
    return metadata


def validate_release(directory, tools, source_sha, version_code, version_name):
    directory = Path(directory)
    apk = directory / APK_NAME
    require(apk.is_file() and apk.stat().st_size > 0, "Expected flat release APK is missing or empty")
    manifest = json.loads((directory / MANIFEST_NAME).read_text(encoding="utf-8"))
    require(manifest.get("apk_name") == APK_NAME and manifest.get("signing_mode") == "production"
            and manifest.get("peloton_in_place_update") is True, "Artifact is not a production release")
    require(manifest.get("source_sha") == source_sha, "Artifact source commit mismatch")
    actual, _ = verify(apk, tools, "production", "push")
    require(all(manifest.get(key) == value for key, value in actual.items()), "Artifact verification metadata mismatch")
    require(actual["version_code"] == int(version_code) and actual["version_name"] == version_name,
            "Artifact version differs from the allocated release")
    return apk.resolve()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("stage", "validate-release"))
    parser.add_argument("--tools", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--directory", required=True)
    parser.add_argument("--apk")
    parser.add_argument("--mode", choices=("production", "debug-fallback"))
    parser.add_argument("--event")
    parser.add_argument("--debug-certificate")
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--version-name")
    parser.add_argument("--output")
    args = parser.parse_args()
    if args.action == "stage":
        require(args.apk and args.mode and args.event, "Staging requires APK, signing mode and event")
        metadata = stage(args.apk, args.directory, args.tools, args.mode, args.event,
                         args.source_sha, args.debug_certificate)
        print(json.dumps(metadata, indent=2))
    else:
        require(args.version_code and args.version_name and args.output, "Release validation requires version and output file")
        apk = validate_release(args.directory, args.tools, args.source_sha, args.version_code, args.version_name)
        with open(args.output, "a", encoding="utf-8") as output:
            output.write(f"APK_PATH={apk}\n")
        print(f"Validated production release input: {apk}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"APK artifact validation failed: {error}")

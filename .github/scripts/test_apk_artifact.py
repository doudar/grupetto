#!/usr/bin/env python3
"""Regression tests for CI artifact layout and signing boundaries; no keys needed."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import apk_artifact as artifact

SOURCE = "a" * 40
DEBUG_CERT = "b" * 64


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.apk = self.root / "app/build/outputs/apk/release/app-release.apk"
        self.apk.parent.mkdir(parents=True)
        self.apk.write_bytes(b"verified APK fixture")
        self.tools = self.root / "tools"
        self.directory = self.root / "ci-artifact"

    def tool_results(self, certificate=artifact.PRODUCTION_CERT, package=artifact.PACKAGE, code=53):
        return [f"Number of signers: 1\nSigner #1 certificate SHA-256 digest: {certificate}\n",
                f"package: name='{package}' versionCode='{code}' versionName='v0.0.51'\n"]

    def stage(self, mode="production", event="pull_request", certificate=artifact.PRODUCTION_CERT,
              debug_certificate=None):
        with patch.object(artifact, "tool_output", side_effect=self.tool_results(certificate)):
            return artifact.stage(self.apk, self.directory, self.tools, mode, event, SOURCE, debug_certificate)

    def validate(self, directory=None, source=SOURCE, code=53, name="v0.0.51"):
        with patch.object(artifact, "tool_output", side_effect=self.tool_results()):
            return artifact.validate_release(directory or self.directory, self.tools, source, code, name)

    def test_flat_uploaded_directory_survives_zip_download_and_has_actual_release_path(self):
        self.stage()
        archive = self.root / "release.zip"
        with zipfile.ZipFile(archive, "w") as zipped:
            for path in self.directory.iterdir():
                zipped.write(path, path.relative_to(self.directory))
        with zipfile.ZipFile(archive) as zipped:
            self.assertEqual(sorted(zipped.namelist()),
                             ["apk-verification.json", "apk-verification.txt", "app-release.apk"])
            downloaded = self.root / "downloaded"
            zipped.extractall(downloaded)
        release_path = self.validate(downloaded)
        self.assertTrue(release_path.is_file())
        self.assertEqual(release_path, (downloaded / "app-release.apk").resolve())

    def test_old_nested_artifact_layout_fails_instead_of_publishing_a_tag(self):
        nested = self.directory / "app/build/outputs/apk/release"
        nested.mkdir(parents=True)
        (nested / "app-release.apk").write_bytes(self.apk.read_bytes())
        with self.assertRaisesRegex(ValueError, "flat release APK"):
            self.validate()

    def test_missing_or_empty_release_apk_is_rejected(self):
        self.stage()
        apk = self.directory / artifact.APK_NAME
        apk.unlink()
        with self.assertRaisesRegex(ValueError, "missing or empty"):
            self.validate()
        apk.touch()
        with self.assertRaisesRegex(ValueError, "missing or empty"):
            self.validate()

    def test_production_certificate_match_passes_for_release_and_peloton_preview(self):
        for event in ("push", "pull_request"):
            with self.subTest(event=event), patch.object(artifact, "tool_output", side_effect=self.tool_results()):
                metadata, _ = artifact.verify(self.apk, self.tools, "production", event)
                self.assertEqual(metadata["certificate_sha256"], artifact.PRODUCTION_CERT)

    def test_production_certificate_mismatch_is_always_rejected(self):
        for event in ("push", "pull_request"):
            with self.subTest(event=event), patch.object(artifact, "tool_output", side_effect=self.tool_results(DEBUG_CERT)):
                with self.assertRaisesRegex(ValueError, "signing certificate"):
                    artifact.verify(self.apk, self.tools, "production", event)

    def test_ci_build_tools_v2_signer_prefix_and_repeated_scheme_certificates(self):
        for signing in (f"Number of signers: 1\nV2 Signer: certificate SHA-256 digest: {artifact.PRODUCTION_CERT}\n",
                        f"Number of signers: 1\nV2 Signer: certificate SHA-256 digest: {artifact.PRODUCTION_CERT}\n"
                        f"V3 Signer: certificate SHA-256 digest: {artifact.PRODUCTION_CERT}\n"):
            results = [signing, self.tool_results()[1]]
            with self.subTest(signing=signing), patch.object(artifact, "tool_output", side_effect=results):
                metadata, _ = artifact.verify(self.apk, self.tools, "production", "pull_request")
                self.assertEqual(metadata["certificate_sha256"], artifact.PRODUCTION_CERT)

    def test_multiple_signers_cannot_pass_even_if_they_share_the_expected_certificate(self):
        signing = f"Number of signers: 2\nSigner #1 certificate SHA-256 digest: {artifact.PRODUCTION_CERT}\n"
        with patch.object(artifact, "tool_output", return_value=signing):
            with self.assertRaisesRegex(ValueError, "signing certificate"):
                artifact.verify(self.apk, self.tools, "production", "push")

    def test_no_secret_pr_fallback_is_verified_and_clearly_separate(self):
        metadata = self.stage("debug-fallback", certificate=DEBUG_CERT, debug_certificate=DEBUG_CERT)
        self.assertFalse(metadata["peloton_in_place_update"])
        self.assertEqual(metadata["apk_name"], "app-debug-fallback.apk")
        self.assertFalse((self.directory / artifact.APK_NAME).exists())
        self.assertIn("DEBUG-SIGNED FALLBACK", (self.directory / "apk-verification.txt").read_text())
        with self.assertRaisesRegex(ValueError, "release APK"):
            self.validate()

    def test_debug_fallback_requires_pr_event_and_matching_disposable_key(self):
        cases = [("push", DEBUG_CERT), ("workflow_dispatch", DEBUG_CERT), ("pull_request", None),
                 ("pull_request", artifact.PRODUCTION_CERT), ("pull_request", "c" * 64)]
        for event, expected in cases:
            with self.subTest(event=event, expected=expected), patch.object(
                    artifact, "tool_output", side_effect=self.tool_results(DEBUG_CERT)):
                with self.assertRaises(ValueError):
                    artifact.verify(self.apk, self.tools, "debug-fallback", event, expected)

    def test_unexpected_package_old_code_and_multiple_signers_are_rejected(self):
        cases = [self.tool_results(package="other.package"), self.tool_results(code=51),
                 [self.tool_results()[0] + f"Signer #2 certificate SHA-256 digest: {DEBUG_CERT}\n"]]
        for result in cases:
            with self.subTest(result=result), patch.object(artifact, "tool_output", side_effect=result):
                with self.assertRaises(ValueError):
                    artifact.verify(self.apk, self.tools, "production", "push")

    def test_release_rejects_tampered_bytes_source_version_and_debug_manifest(self):
        self.stage()
        for kwargs in ({"source": "c" * 40}, {"code": 54}, {"name": "v0.0.52"}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                self.validate(**kwargs)
        apk = self.directory / artifact.APK_NAME
        apk.write_bytes(b"modified APK bytes")
        with self.assertRaisesRegex(ValueError, "metadata mismatch"):
            self.validate()
        manifest_path = self.directory / artifact.MANIFEST_NAME
        manifest = json.loads(manifest_path.read_text())
        manifest["signing_mode"] = "debug-fallback"
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "not a production release"):
            self.validate()


class WorkflowContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = (Path(__file__).resolve().parents[1] / "workflows/android.yml").read_text()

    def test_release_validates_actual_input_before_any_publication_side_effect(self):
        release = self.workflow.split("\n  release:\n", 1)[1].split("\n  calibration-persistence-tests:", 1)[0]
        validation = release.index("name: Validate release input before publication")
        self.assertLess(release.index("name: Download release APK"), validation)
        for step in ("Commit version updates", "Create new tag", "Create GitHub Release", "Upload APK to Release"):
            self.assertLess(validation, release.index("name: " + step))
        self.assertIn("asset_path: ${{ steps.find_apk.outputs.APK_PATH }}", release)
        self.assertIn("path: release-artifact/", release)
        self.assertIn("--directory release-artifact", release)

    def test_fork_credentials_are_guarded_and_fallback_artifacts_cannot_be_released(self):
        production = self.workflow.split("- name: Configure production signing", 1)[1].split("- name:", 1)[0]
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", production)
        self.assertIn("echo \"mode=production\"", production)
        fallback = self.workflow.split("- name: Configure disposable debug fallback", 1)[1].split("- name:", 1)[0]
        self.assertNotIn("secrets.", fallback)
        self.assertIn("github.event_name == 'pull_request'", fallback)
        self.assertIn("'release-apk' || 'debug-fallback-apk'", self.workflow)
        self.assertIn("path: ci-artifact/", self.workflow)
        self.assertIn("if-no-files-found: error", self.workflow)
        self.assertNotIn("pull_request_target", self.workflow)


if __name__ == "__main__":
    unittest.main(verbosity=2)

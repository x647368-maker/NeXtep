#!/usr/bin/env python3
"""Fast offline contract checks; fail before spending CI time on SDK downloads."""
from pathlib import Path
import importlib.util
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "prepare-update.py"
BUILD = ROOT / "app" / "build.gradle.kts"
POLICY = ROOT / "app" / "src" / "main" / "kotlin" / "io" / "github" / "lujinxin" / "nextep" / "config" / "UpdatePolicy.kt"
NOTES = ROOT / "RELEASE_NOTES.md"
MODULE = ROOT / "app" / "src" / "main" / "resources" / "META-INF" / "xposed" / "module.prop"
spec = importlib.util.spec_from_file_location("atomic_prepare_update", SCRIPT)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseContract(unittest.TestCase):
    def version(self):
        gradle = BUILD.read_text()
        name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
        code = re.search(r'versionCode\s*=\s*(\d+)', gradle)
        self.assertIsNotNone(name, "Gradle versionName is missing")
        self.assertIsNotNone(code, "Gradle versionCode is missing")
        return name.group(1), int(code.group(1))

    def test_version_is_monotonic_and_channel_is_explicit(self):
        name, code = self.version()
        self.assertRegex(name, r"^\\d+\\.\\d+\\.\\d+(?:-dev)?$")
        self.assertGreaterEqual(code, 31)
        self.assertEqual("dev" if name.endswith("-dev") else "stable", release.release_channel(name))

    def test_app_updater_recognizes_dev_but_not_candidate_suffix(self):
        policy = POLICY.read_text()
        self.assertIn('enum class Channel { DEV, STABLE }', policy)
        self.assertIn('(-dev(?:-[A-Za-z0-9]+)?)?', policy)
        self.assertEqual("dev", release.release_channel("0.9.16-dev"))
        with self.assertRaises(ValueError):
            release.release_channel("0.9.15-candidate")

    def test_stable_release_is_not_labeled_development(self):
        self.assertEqual("stable", release.release_channel("0.9.16"))
        for value in ("0.9.16-candidate", "0.9.16-dev-rc1", "0.9.x", ""):
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.release_channel(value)

    def test_embedded_current_release_notes_are_packaged(self):
        gradle = BUILD.read_text()
        self.assertIn('from(rootProject.file("RELEASE_NOTES.md"))', gradle)
        self.assertIn('rename { "current-release.md" }', gradle)
        self.assertIn('assets.srcDir(embeddedNotesDir)', gradle)
        self.assertIn('dependsOn(embedCurrentReleaseNotes)', gradle)

    def test_signing_identity_is_not_changed(self):
        policy = POLICY.read_text()
        self.assertIn(release.CERT, policy)
        self.assertIn("io.github.x647368maker.atomicworkbench", policy)
        self.assertIn("io.github.x647368maker.atomicworkbench", BUILD.read_text())

    def test_xposed_metadata_matches_installed_app(self):
        name, code = self.version()
        fields = dict(line.split("=", 1) for line in MODULE.read_text().splitlines()
                      if "=" in line and not line.lstrip().startswith("#"))
        self.assertEqual(name, fields.get("version"))
        self.assertEqual(str(code), fields.get("versionCode"))

    def test_notes_identify_same_unverified_candidate(self):
        name, code = self.version()
        notes = NOTES.read_text()
        self.assertIn(name, notes)
        self.assertIn(str(code), notes)
        self.assertIn("真机", notes)


if __name__ == "__main__":
    unittest.main(verbosity=2)

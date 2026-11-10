#!/usr/bin/env python3
"""Network-free checks for the GitHub release feed publisher."""
import importlib.util
import pathlib
import unittest

SOURCE = pathlib.Path(__file__).with_name("publish-update-feed.py")
spec = importlib.util.spec_from_file_location("atomic_release_feed", SOURCE)
feed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(feed)


def manifest(code=31, channel="dev"):
    return {
        "schemaVersion": 1,
        "applicationId": feed.PACKAGE,
        "versionName": "0.9.16-dev" if channel == "dev" else "0.9.16",
        "versionCode": code,
        "channel": channel,
        "apkAsset": "StageManager-0.9.16.apk",
        "sha256": "a" * 64,
        "size": 31_000_000,
        "certificateSha256": feed.CERT,
        "releaseNotes": "修复切换动画，保留工作组",
    }


def release(item, tag, prerelease):
    return {
        "tag_name": tag, "draft": False, "prerelease": prerelease,
        "assets": [
            {"name": "atomic-update.json", "state": "uploaded"},
            {"name": item["apkAsset"], "size": item["size"], "state": "uploaded",
             "digest": "sha256:" + item["sha256"]},
        ],
    }


class PublishUpdateFeedTest(unittest.TestCase):
    def setUp(self):
        self.empty = {"schemaVersion": 1, "releases": []}
        self.meta = manifest()
        self.tag = "atomic-v0.9.16-dev"

    def test_first_release_is_indexed(self):
        result = feed.publish(self.meta, self.empty, self.tag, release(self.meta, self.tag, True))
        self.assertEqual(31, result["releases"][0]["versionCode"])

    def test_stable_and_dev_are_kept_separately(self):
        first = feed.publish(self.meta, self.empty, self.tag)
        stable = manifest(32, "stable")
        updated = feed.publish(stable, first, "atomic-v0.9.16")
        self.assertEqual({"dev", "stable"}, {x["channel"] for x in updated["releases"]})

    def test_republishing_is_idempotent(self):
        result = feed.publish(self.meta, self.empty, self.tag)
        self.assertEqual(result, feed.publish(self.meta, result, self.tag))

    def test_reject_certificate_change(self):
        self.meta["certificateSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "signer"):
            feed.publish(self.meta, self.empty, self.tag)

    def test_reject_modified_same_version(self):
        initial = feed.publish(self.meta, self.empty, self.tag)
        self.meta["sha256"] = "b" * 64
        with self.assertRaisesRegex(ValueError, "same versionCode"):
            feed.publish(self.meta, initial, self.tag)

    def test_reject_version_downgrade(self):
        initial = feed.publish(self.meta, self.empty, self.tag)
        with self.assertRaisesRegex(ValueError, "roll back"):
            feed.publish(manifest(30), initial, "atomic-v0.9.15-dev")

    def test_reject_wrong_release_size(self):
        candidate = release(self.meta, self.tag, True)
        candidate["assets"][1]["size"] += 1
        with self.assertRaisesRegex(ValueError, "size"):
            feed.publish(self.meta, self.empty, self.tag, candidate)

    def test_reject_wrong_release_digest(self):
        candidate = release(self.meta, self.tag, True)
        candidate["assets"][1]["digest"] = "sha256:" + "f" * 64
        with self.assertRaisesRegex(ValueError, "digest"):
            feed.publish(self.meta, self.empty, self.tag, candidate)

    def test_reject_missing_manifest(self):
        candidate = release(self.meta, self.tag, True)
        candidate["assets"].pop(0)
        with self.assertRaisesRegex(ValueError, "exactly one"):
            feed.publish(self.meta, self.empty, self.tag, candidate)

    def test_reject_nonmatching_channel(self):
        with self.assertRaisesRegex(ValueError, "prerelease"):
            feed.publish(self.meta, self.empty, self.tag, release(self.meta, self.tag, False))


if __name__ == "__main__":
    unittest.main(verbosity=2)

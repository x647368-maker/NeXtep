#!/usr/bin/env python3
"""Offline, signer-aware regression tests for the main-branch release publisher.

No network, GitHub secrets, real keystore, or public APK required.
"""
import hashlib
import importlib.util
import pathlib
import tempfile
import unittest

SOURCE = pathlib.Path(__file__).with_name("publish-update-feed.py")
spec = importlib.util.spec_from_file_location("atomic_release_feed", SOURCE)
feed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(feed)

def manifest(payload=b"test fixture", code=42, channel="dev"):
    return {
        "schemaVersion": 1,
        "applicationId": feed.PACKAGE,
        "versionName": "0.9.27-dev" if channel == "dev" else "0.9.27",
        "versionCode": code,
        "channel": channel,
        "apkAsset": "TaiQian-0.9.27.apk",
        "sha256": hashlib.sha256(payload).hexdigest(),
        "size": len(payload),
        "certificateSha256": feed.CERT,
        "releaseNotes": "验收后才正式发布",
    }

def release(m, tag, prerelease):
    return {
        "tag_name": tag, "draft": False, "prerelease": prerelease,
        "assets": [
            {"name": "atomic-update.json", "state": "uploaded"},
            {"name": m["apkAsset"], "state": "uploaded", "size": m["size"],
             "digest": "sha256:" + m["sha256"]},
        ]
    }

class PublisherSecurityTest(unittest.TestCase):
    def setUp(self):
        self.empty = {"schemaVersion": 1, "releases": []}
        self.payload = b"test fixture"
        self.meta = manifest(self.payload)
        self.tag = "atomic-v0.9.27-dev"

    def test_canonical_signer_matches_client_and_rejects_old_certificate(self):
        self.assertEqual("3703b59c48b1c30df872fbcd8fa01905b9a6c684f151ddbb0d05e5b5726468fe", feed.CERT)
        self.meta["certificateSha256"] = "b25ba1046d53510a1378b3404160d27a222a3ec9993b0a722b8f61304ac4abdd"
        with self.assertRaisesRegex(ValueError,"signer"):
            feed.publish(self.meta,self.empty,self.tag)

    def test_owner_published_apk_bytes_are_verified(self):
        with tempfile.TemporaryDirectory() as temp:
            path=pathlib.Path(temp)/self.meta["apkAsset"]
            path.write_bytes(self.payload)
            feed.verify_apk_file(dict(self.meta,releaseTag=self.tag),path)
            path.write_bytes(b"evil fixture")  # same length would still be rejected
            with self.assertRaisesRegex(ValueError,"digest"):
                feed.verify_apk_file(dict(self.meta,releaseTag=self.tag),path)

    def test_wrong_size_and_filename_are_blocked(self):
        with tempfile.TemporaryDirectory() as temp:
            path=pathlib.Path(temp)/self.meta["apkAsset"]
            path.write_bytes(self.payload + b"x")
            with self.assertRaisesRegex(ValueError,"size"):
                feed.verify_apk_file(self.meta,path)
            name=pathlib.Path(temp)/"different.apk"
            name.write_bytes(self.payload)
            with self.assertRaisesRegex(ValueError,"filename"):
                feed.verify_apk_file(self.meta,name)

    def test_real_apk_manifest_identity_must_match_metadata(self):
        feed.verify_apk_identity(self.meta, feed.PACKAGE,
                                 "0.9.27-dev", 42)
        for pkg, name, code in (
            ("io.github.other", "0.9.27-dev", 42),
            (feed.PACKAGE, "0.9.26-dev", 42),
            (feed.PACKAGE, "0.9.27-dev", 41),
        ):
            with self.assertRaisesRegex(ValueError, "identity"):
                feed.verify_apk_identity(self.meta, pkg, name, code)

    def test_signed_release_metadata_required(self):
        result=feed.publish(self.meta,self.empty,self.tag,release(self.meta,self.tag,True))
        self.assertEqual([42],[x["versionCode"] for x in result["releases"]])
        wrong=release(self.meta,self.tag,True)
        wrong["assets"][1]["size"]+=1
        with self.assertRaisesRegex(ValueError,"size"):
            feed.publish(self.meta,self.empty,self.tag,wrong)
        wrong=release(self.meta,self.tag,True)
        wrong["draft"]=True
        with self.assertRaisesRegex(ValueError,"publication"):
            feed.publish(self.meta,self.empty,self.tag,wrong)

    def test_idempotency_replay_and_channel_rollback_rejected(self):
        first=feed.publish(self.meta,self.empty,self.tag)
        self.assertEqual(first,feed.publish(self.meta,first,self.tag))
        modified=dict(self.meta,sha256="0"*64)
        with self.assertRaisesRegex(ValueError,"same versionCode"):
            feed.publish(modified,first,self.tag)
        lower=manifest(code=41)
        with self.assertRaisesRegex(ValueError,"roll back"):
            feed.publish(lower,first,"atomic-v0.9.26-dev")

    def test_dev_stable_channels_are_separate(self):
        first=feed.publish(self.meta,self.empty,self.tag)
        stable=manifest(code=43,channel="stable")
        updated=feed.publish(stable,first,"atomic-v0.9.27")
        self.assertEqual({"stable","dev"},{x["channel"] for x in updated["releases"]})
        with self.assertRaisesRegex(ValueError,"prerelease"):
            feed.publish(stable,first,"atomic-v0.9.27",release(stable,"atomic-v0.9.27",True))

if __name__=="__main__":
    unittest.main(verbosity=2)

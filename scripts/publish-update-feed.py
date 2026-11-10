#!/usr/bin/env python3
"""Update the public read-only version feed after an OWNER-approved GitHub Release.

Only metadata is written, never APKs, passwords or signing keys. The Android client
independently checks the installed signing certificate and downloaded APK digest.
"""
import argparse
import json
import pathlib
import re

PACKAGE = "io.github.x647368maker.atomicworkbench"
CERT = "b25ba1046d53510a1378b3404160d27a222a3ec9993b0a722b8f61304ac4abdd"
APK_LIMIT = 256 * 1024 * 1024
TAG = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
APK = re.compile(r"^[A-Za-z0-9._-]+\.apk$")
VERSION = re.compile(r"^\d+\.\d+\.\d+(-dev)?$")


def validate(item):
    required = {
        "schemaVersion", "applicationId", "versionName", "versionCode",
        "channel", "apkAsset", "sha256", "size", "certificateSha256",
        "releaseTag"
    }
    if not isinstance(item, dict) or not required.issubset(item):
        raise ValueError("update metadata has missing fields")
    if item["schemaVersion"] != 1 or item["applicationId"] != PACKAGE:
        raise ValueError("wrong metadata schema or package")
    if item["certificateSha256"] != CERT:
        raise ValueError("signer certificate mismatch")
    name = item["versionName"]
    if not isinstance(name, str) or not VERSION.fullmatch(name):
        raise ValueError("invalid versionName")
    if item["channel"] != ("dev" if name.endswith("-dev") else "stable"):
        raise ValueError("channel/versionName mismatch")
    if type(item["versionCode"]) is not int or item["versionCode"] <= 0:
        raise ValueError("invalid versionCode")
    if type(item["size"]) is not int or not 0 < item["size"] <= APK_LIMIT:
        raise ValueError("invalid APK size")
    if not isinstance(item["sha256"], str) or not re.fullmatch("[0-9a-f]{64}", item["sha256"]):
        raise ValueError("invalid SHA-256")
    if not isinstance(item["apkAsset"], str) or not APK.fullmatch(item["apkAsset"]):
        raise ValueError("invalid APK filename")
    if not isinstance(item["releaseTag"], str) or not TAG.fullmatch(item["releaseTag"]):
        raise ValueError("invalid release tag")
    if not isinstance(item.get("releaseNotes", ""), str):
        raise ValueError("releaseNotes must be a string")


def validate_release(item, release):
    if release.get("tag_name") != item["releaseTag"] or release.get("draft"):
        raise ValueError("release tag or publication status mismatch")
    if bool(release.get("prerelease")) != (item["channel"] == "dev"):
        raise ValueError("release prerelease setting mismatches channel")
    assets = release.get("assets", [])
    manifests = [a for a in assets if a.get("name") == "atomic-update.json" and a.get("state") == "uploaded"]
    apks = [a for a in assets if a.get("name") == item["apkAsset"] and a.get("state") == "uploaded"]
    if len(manifests) != 1 or len(apks) != 1:
        raise ValueError("release must have exactly one manifest and matching APK")
    asset = apks[0]
    if asset.get("size") != item["size"]:
        raise ValueError("release APK size does not match metadata")
    digest = asset.get("digest")
    if digest and digest != "sha256:" + item["sha256"]:
        raise ValueError("release APK digest does not match metadata")


def publish(manifest, current, tag, release=None):
    candidate = dict(manifest)
    candidate["releaseTag"] = tag
    validate(candidate)
    if release is not None:
        validate_release(candidate, release)
    if current.get("schemaVersion") != 1 or not isinstance(current.get("releases"), list):
        raise ValueError("existing feed is invalid")
    entries = current["releases"]
    for old in entries:
        validate(old)
    # Never replace an existing version with a different payload or move a channel backward.
    previous = [x for x in entries if x["channel"] == candidate["channel"]]
    for old in previous:
        if old["versionCode"] == candidate["versionCode"] and old["sha256"] != candidate["sha256"]:
            raise ValueError("same versionCode would change APK digest")
    if previous and candidate["versionCode"] < max(x["versionCode"] for x in previous):
        raise ValueError("release would roll back the channel")
    latest = [x for x in entries if x["channel"] != candidate["channel"]]
    latest.append(candidate)
    latest.sort(key=lambda x: x["versionCode"], reverse=True)
    return {"schemaVersion": 1, "releases": latest}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=pathlib.Path, required=True)
    parser.add_argument("--feed", type=pathlib.Path, required=True)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--release-json", type=pathlib.Path)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    feed = json.loads(args.feed.read_text(encoding="utf-8"))
    release = json.loads(args.release_json.read_text(encoding="utf-8")) if args.release_json else None
    updated = publish(manifest, feed, args.tag, release)
    args.out.write_text(json.dumps(updated, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Feed prepared for verified published", args.tag, "versionCode", manifest["versionCode"])


if __name__ == "__main__":
    main()

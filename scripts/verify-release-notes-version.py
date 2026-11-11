#!/usr/bin/env python3
"""Validate source version, versionCode, and the release-note heading before CI build.

This is the safety net for the v-prefixed 0.9.25/0.9.26 changelog regression.
No private signing material or network access is involved.
"""
import argparse
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
HEADING = re.compile(
    r"^台前调度\s+[vV]?(?P<version>\d+\.\d+\.\d+(?:-dev)?)"
    r"（(?:versionCode|版本号)\s*(?P<code>\d+)）"
)

def header_version(line: str):
    matched = HEADING.match(line.strip())
    return (matched.group("version"), int(matched.group("code"))) if matched else None

def source_version(source: str):
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"', source, re.M)
    codes = re.findall(r'^\s*versionCode\s*=\s*(\d+)', source, re.M)
    if len(names) != 1 or len(codes) != 1:
        raise ValueError("Gradle must define one unambiguous versionName/versionCode")
    return names[0], int(codes[0])

def main():
    cli = argparse.ArgumentParser()
    cli.add_argument("--self-test", action="store_true")
    args = cli.parse_args()
    if args.self_test:
        assert header_version("台前调度 v0.9.26-dev（versionCode 41）｜测试") == ("0.9.26-dev", 41)
        assert header_version("台前调度 0.9.24-dev（版本号 39）｜旧说明") == ("0.9.24-dev", 39)
        assert header_version("台前调度 v0.9.260-dev（versionCode 41）") != ("0.9.26-dev", 41)
        assert header_version("台前调度 v0.9.26-dev（versionCode 410）") != ("0.9.26-dev", 41)
        print("Release-note metadata check self-test passed")
        return
    notes = (ROOT / "RELEASE_NOTES.md").read_text(encoding="utf-8")
    build = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    actual = header_version(notes.splitlines()[0])
    expected = source_version(build)
    if actual != expected:
        raise SystemExit(f"BUILD BLOCKED: RELEASE_NOTES.md heading {actual!r} != Gradle {expected!r}")
    if len(notes.strip()) < 80:
        raise SystemExit("BUILD BLOCKED: release notes are missing meaningful content")
    print(f"Verified exact release-note identity {expected[0]} / versionCode {expected[1]}")

if __name__ == "__main__":
    main()

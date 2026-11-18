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

# Preserve the historical static check, while admitting only the exact
# revision-bearing contract introduced by the v89 compiled identity change.
DYNAMIC_BUILD = 'val BUILD = "${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}+${BuildConfig.ATOMIC_BUILD_REVISION}"'

def runtime_identity_contract(runtime: str, build: str, target_stamp: str):
    declarations = re.findall(r'^\s*(?:const\s+)?val\s+BUILD\s*=\s*[^\n]+', runtime, re.M)
    if len(declarations) != 1:
        return False
    declaration = declarations[0].strip()
    if declaration == f'const val BUILD = "{target_stamp}"':
        return True
    if declaration != DYNAMIC_BUILD:
        return False
    # The value must be the sanitized Gradle/CI revision, not a hardcoded
    # placeholder, APK lookup, or other unrelated source field.
    required = (
        'val atomicBuildRevision = providers.gradleProperty("atomicBuildRevision")',
        '.orElse(providers.environmentVariable("GITHUB_SHA"))',
        '.getOrElse("unknown")',
        '.take(12)',
        '.takeIf { it.matches(Regex("[a-fA-F0-9]{8,12}")) } ?: "unknown"',
        'buildConfigField("String", "ATOMIC_BUILD_REVISION", "\\"$atomicBuildRevision\\"")',
    )
    lines = {line.strip() for line in build.splitlines()}
    return all(line in lines for line in required)

def main():
    cli = argparse.ArgumentParser()
    cli.add_argument("--self-test", action="store_true")
    args = cli.parse_args()
    if args.self_test:
        assert header_version("台前调度 v0.9.26-dev（versionCode 41）｜测试") == ("0.9.26-dev", 41)
        assert header_version("台前调度 0.9.24-dev（版本号 39）｜旧说明") == ("0.9.24-dev", 39)
        assert header_version("台前调度 v0.9.260-dev（versionCode 41）") != ("0.9.26-dev", 41)
        assert header_version("台前调度 v0.9.26-dev（versionCode 410）") != ("0.9.26-dev", 41)
        stamp = "0.9.74-dev/89"
        build_fixture = r'''val atomicBuildRevision = providers.gradleProperty("atomicBuildRevision")
.orElse(providers.environmentVariable("GITHUB_SHA"))
.getOrElse("unknown")
.take(12)
.takeIf { it.matches(Regex("[a-fA-F0-9]{8,12}")) } ?: "unknown"
buildConfigField("String", "ATOMIC_BUILD_REVISION", "\\"$atomicBuildRevision\\"")'''
        assert runtime_identity_contract(f'const val BUILD = "{stamp}"', "", stamp)
        assert not runtime_identity_contract('const val BUILD = "0.9.73-dev/88"', "", stamp)
        assert runtime_identity_contract(DYNAMIC_BUILD, build_fixture, stamp)
        assert not runtime_identity_contract(DYNAMIC_BUILD, "", stamp)
        assert not runtime_identity_contract(DYNAMIC_BUILD.replace("BuildConfig.VERSION_CODE", "89"), build_fixture, stamp)
        assert not runtime_identity_contract(DYNAMIC_BUILD + "\n" + DYNAMIC_BUILD, build_fixture, stamp)
        assert not runtime_identity_contract(DYNAMIC_BUILD, build_fixture.replace("GITHUB_SHA", "WRONG_SHA"), stamp)
        print("Release-note and runtime identity metadata check self-test passed")
        return
    notes = (ROOT / "RELEASE_NOTES.md").read_text(encoding="utf-8")
    build = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    actual = header_version(notes.splitlines()[0])
    expected = source_version(build)
    if actual != expected:
        raise SystemExit(f"BUILD BLOCKED: RELEASE_NOTES.md heading {actual!r} != Gradle {expected!r}")
    if len(notes.strip()) < 80:
        raise SystemExit("BUILD BLOCKED: release notes are missing meaningful content")
    runtime_path = ROOT / "app/src/main/kotlin/io/github/lujinxin/nextep/systemui/StageRuntimeIdentity.kt"
    runtime = runtime_path.read_text(encoding="utf-8")
    target_stamp = f"{expected[0]}/{expected[1]}"
    if not runtime_identity_contract(runtime, build, target_stamp):
        raise SystemExit(f"BUILD BLOCKED: incompatible loaded SystemUI identity for {target_stamp!r}")
    print(f"Verified exact release notes and injected module identity {target_stamp}")

if __name__ == "__main__":
    main()

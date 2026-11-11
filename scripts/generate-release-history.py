#!/usr/bin/env python3
"""Build a bounded offline changelog from committed RELEASE_NOTES.md revisions.

Requires git history for the automatic archive; degrades to current notes in
source ZIP/shallow clones. The APK never runs this script or accesses the network.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
NOTES = ROOT / "RELEASE_NOTES.md"
VERSION = re.compile(r"(?<![0-9.])([0-9]+\.[0-9]+\.[0-9]+(?:-dev)?)(?![A-Za-z0-9.-])")
MAX_ENTRIES = 60
MAX_NOTE_CHARS = 32768

def identify(notes: str) -> str | None:
    # Version must occur near the heading, not merely in a reference to an old version.
    if "台前调度" not in notes[:500]:
        return None
    match = VERSION.search(notes[:500])
    return match.group(1) if match else None

def git(*args: str) -> str | None:
    try:
        process = subprocess.run(
            ["git", "-C", str(ROOT), *args], capture_output=True,
            text=True, encoding="utf-8", timeout=12, check=False
        )
        return process.stdout if process.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None

def collect() -> list[dict[str, str]]:
    seen: set[str] = set()
    result: list[dict[str, str]] = []
    def include(raw: str | None) -> None:
        if raw is None:
            return
        raw = raw.strip()
        version = identify(raw)
        if not version or version in seen or len(raw) > MAX_NOTE_CHARS:
            return
        seen.add(version)
        result.append({"version": version, "notes": raw})
    include(NOTES.read_text(encoding="utf-8"))
    history = git("log", "--format=%H", "--", "RELEASE_NOTES.md")
    if history:
        for sha in history.splitlines():
            if len(result) >= MAX_ENTRIES:
                break
            if not re.fullmatch("[0-9a-f]{40}", sha):
                continue
            include(git("show", f"{sha}:RELEASE_NOTES.md"))
    return result

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        assert identify("台前调度 0.9.17-dev（版本号 32）\n\n更新说明") == "0.9.17-dev"
        assert identify("台前调度 0.9.18 / 33 发布版") == "0.9.18"
        assert identify("这段内容不包含版本号") is None
        assert VERSION.search("0.9.1") is not None
        assert identify("0.9.170x") is None
        print("Offline changelog parser self-test passed")
        return
    if not args.output:
        parser.error("--output is required unless --self-test is set")
    rows = collect()
    if not rows:
        raise SystemExit("No recognizable release notes; cannot build offline changelog.")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(rows, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Embedded {len(rows)} version note(s), newest={rows[0]['version']}")

if __name__ == "__main__":
    main()

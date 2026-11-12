#!/usr/bin/env python3
"""Read-only APK ZIP size audit; no file mutation, signing or app runtime changes."""
from collections import defaultdict
from pathlib import Path
import sys
import zipfile

def category(name: str) -> str:
    if name.startswith("res/"):
        return "resources/images"
    if name.startswith("assets/"):
        return "assets"
    if name.startswith("lib/"):
        return "native libraries"
    if name.startswith("META-INF/"):
        return "signature and metadata"
    if name.endswith(".dex"):
        return "DEX/Kotlin/Java"
    if name == "resources.arsc":
        return "resource index"
    return "other"

def audit(path: Path) -> None:
    with zipfile.ZipFile(path) as apk:
        items = [entry for entry in apk.infolist() if not entry.is_dir()]
        sizes = defaultdict(lambda: [0, 0, 0])
        for item in items:
            row = sizes[category(item.filename)]
            row[0] += item.compress_size
            row[1] += item.file_size
            row[2] += 1
        print(f"APK SIZE AUDIT {path.name}: {path.stat().st_size:,} bytes, {len(items)} entries")
        print("GROUPS BY ON-DISK COMPRESSED BYTES")
        for name, (packed, raw, count) in sorted(sizes.items(), key=lambda kv: -kv[1][0]):
            print(f"{name:25s} {packed:>12,} packed / {raw:>12,} raw / {count:>4} files")
        print("TOP 25 APK ZIP ENTRIES (compressed bytes)")
        for item in sorted(items, key=lambda e: -e.compress_size)[:25]:
            print(f"  {item.compress_size:>11,} packed / {item.file_size:>11,} raw   {item.filename}")
        print("PNG/WEBP SUMMARY")
        images = [e for e in items if e.filename.lower().endswith((".png", ".webp"))]
        print(f"  count={len(images)}, packed={sum(e.compress_size for e in images):,}, raw={sum(e.file_size for e in images):,}")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: audit-apk-size.py <apk>")
    audit(Path(sys.argv[1]))

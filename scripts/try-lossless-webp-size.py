#!/usr/bin/env python3
"""Isolated CI size experiment: lossless icon conversion, NEVER a release build.

Converts only large launcher artwork, preserving original resource IDs and
pixels. Rejects any changed pixel or non-smaller result. Source files change
only inside this disposable CI checkout. No task engine, UI behavior, SDK,
signing, version, app setting, or image resolution is modified.
"""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
IMAGES = ROOT / "app/src/main/res/drawable-nodpi"
MIN_SAVING = 1024

def run(*args: str, timeout: int = 120) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=False)

def main() -> None:
    originals = sorted(IMAGES.glob("ic_launcher*_artwork.png"))
    if not originals:
        raise SystemExit("No launcher artwork; refuse to touch other image resources.")
    total_old, total_new = 0, 0
    accepted = 0
    for png in originals:
        old_bytes = png.stat().st_size
        total_old += old_bytes
        target = png.with_suffix(".webp")
        if target.exists():
            raise SystemExit(f"Refuse to overwrite existing WebP resource: {target}")
        encoded = run("cwebp", "-lossless", "-exact", "-metadata", "all", "-z", "6",
                      "-quiet", str(png), "-o", str(target))
        if encoded.returncode != 0 or not target.is_file():
            raise SystemExit(f"WebP conversion failed for {png.name}: {encoded.stderr[:400]}")
        optimized_bytes = target.stat().st_size
        # ImageMagick pixel-wise comparison; any changed pixel rejects conversion.
        identical = run("compare", "-metric", "AE", str(png), str(target), "null:", timeout=45)
        is_pixel_exact = identical.returncode == 0
        if not is_pixel_exact or optimized_bytes > old_bytes - MIN_SAVING:
            target.unlink()
            total_new += old_bytes
            print(f"SKIP {png.name}: original={old_bytes:,}; WebP={optimized_bytes:,}; pixel_exact={is_pixel_exact}")
            continue
        # Resource name remains unchanged (only suffix changes), so R.drawable IDs
        # and XML/Kotlin references will resolve exactly as before.
        png.unlink()
        accepted += 1
        total_new += optimized_bytes
        print(f"LOSSLESS {png.name} -> {target.name}: {old_bytes:,} -> {optimized_bytes:,} bytes; pixel AE=0")
    print(f"SUMMARY files={len(originals)} converted={accepted} raw_bytes_before={total_old} "
          f"raw_bytes_after={total_new} source_bytes_saved={total_old-total_new} "
          f"(actual APK ZIP savings require separate comparison)")
    if accepted == 0:
        print("No safe reduction selected. This is a valid no-op result.")

if __name__ == "__main__":
    main()

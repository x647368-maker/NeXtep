#!/usr/bin/env python3
"""SHA-bound P0-2a delta guard. NOT a replacement for the frozen v88 guard.

CI first runs the original verify-state-continuity.py at the exact P0-1
base SHA in a detached worktree, then runs this guard on the P0-2a HEAD.
The old guard intentionally rejects changes to the v88-reviewed production
bytes; these changes must be reviewed explicitly as the new P0-2a delta.
"""
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]
BASE="6ddc3d4333d0aecc7f5a8218aab8e788906156ce"
P="app/src/main/kotlin/io/github/lujinxin/nextep/systemui/"
T="app/src/androidTest/kotlin/io/github/x647368maker/atomicworkbench/visual/"
ALLOWED={
    P+"ExchangeMotionPlan.kt",
    P+"StageTransientTapSwap.kt",
    P+"WorkspaceSwapAnimator.kt",
    T+"PairedMotionClockRegression.kt",
    T+"PhasedPreviewRegression.kt",
    ".github/workflows/v89-p0-2a-unit-gate.yml",
    ".github/workflows/v89-p0-2a-graphics-gate.yml",
    "scripts/verify-v89-p02a-boundary.py",
}
PINNED_PRODUCTION_BLOBS={
    P+"ExchangeMotionPlan.kt": "44eeedb9e6d7871a84eb85221941c5a2bcfe1f35",
    P+"StageTransientTapSwap.kt": "b6ad60d2062b9314e0aa72b4e1d534fb76b20803",
    P+"WorkspaceSwapAnimator.kt": "7ca9a966f18e3c0a62bcb11a8ad0bb0c0d3af6e6",
}

def git(*args):
    return subprocess.check_output(["git",*args],cwd=ROOT,text=True).strip()

def assert_contains(path,*needles):
    data=(ROOT/path).read_text()
    for needle in needles:
        assert needle in data, f"{path}: missing required production hook {needle!r}"

def main():
    subprocess.run(["git","merge-base","--is-ancestor",BASE,"HEAD"],
        cwd=ROOT,check=True)
    changed=set(filter(None,git("diff","--name-only",BASE,"HEAD").splitlines()))
    assert changed==ALLOWED, (
        f"P0-2a delta mismatch: unexpected={sorted(changed-ALLOWED)} "
        f"missing={sorted(ALLOWED-changed)}"
    )
    # Verify exact reviewed candidate blobs, not merely permitted filenames.
    for path,sha in PINNED_PRODUCTION_BLOBS.items():
        actual=git("hash-object",str(ROOT/path))
        assert actual==sha,f"{path}: candidate source bytes changed ({actual})"
    assert_contains(P+"WorkspaceSwapAnimator.kt",
        "preview.usesMotionPlan -> android.animation.TimeInterpolator { it }",
        "val frameProgress=sampleGeometryProgress(progress)",
        "StagePreviewFillPolicy.frame")
    assert_contains(P+"StageTransientTapSwap.kt",
        "val frameProgress=sampleGeometryProgress(field)",
        "TimeInterpolator{ motionPlan.geometryProgress(it) }",
        "interpolator=if(motionPlan!=null) TimeInterpolator{it} else easing")
    assert_contains(T+"PhasedPreviewRegression.kt",
        "PairedMotionClockRegression.run(activity,instrumentation)")
    assert_contains(T+"PairedMotionClockRegression.kt",
        "incomingView.translationX",
        "raster.getPixel(px,py)==Color.GREEN")
    print("PASS v89/P0-2a strict SHA-pinned production and test delta; "
          "unchanged frozen base audited separately")

if __name__=="__main__":
    main()

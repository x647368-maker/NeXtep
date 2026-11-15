#!/usr/bin/env python3
"""Verify exact scope of the isolated native-animation candidate.

This is an additional source-integrity preflight, NOT a substitute for
verify-state-continuity.py, Android builds, signing, or device validation.
It compares committed Git objects, not uncommitted worktree files.
"""
import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

BASE = "913f338eff98a5957403c8a7fdfca53d35a0af30"
REVIEW_TARGET = "b4ef45a111702da7bee85029c981ccc31a9411e5"
SELF = "scripts/verify-v87-native-candidate.py"
MAIN = "app/src/main/kotlin/io/github/lujinxin/nextep/systemui/"
TEST = "app/src/test/kotlin/io/github/lujinxin/nextep/systemui/"
NEW = {"app/src/androidTest/kotlin/io/github/x647368maker/atomicworkbench/visual/StageNativeFlightRuntimeRegression.kt": "2630f2ce782dd47947ea9717ae040ec37a7b57ee"}
PINS = {
    "app/src/androidTest/kotlin/io/github/x647368maker/atomicworkbench/visual/LivePreviewInstrumentation.kt": ("f332c2708447ccbd11a4aff1eb434274b5a28980", "2ce8336fd6dbd34e2c9e352bf2871506c786e803"),
    "app/src/androidTest/kotlin/io/github/x647368maker/atomicworkbench/visual/StageLiveMirrorPrimitiveRegression.kt": ("e0761b9400cd9528bdab6780b7c968f213ba0f60", "df5c0c4d2551274245db55b7e89acb690c2786cb"),
    ".github/workflows/atomic-android.yml": ("4c6ed172ccdfd165f9639d2d0b79b2eaaabd4dcf", "956bac76978ea131af63b98def1af5d9ef4991d8"),
    "app/build.gradle.kts": ("a58e0476a3b47dd479427d53e7437961ba6a85c5", "3f7663838fab637cd45e6ad6dc3f12634e93bef8"),
    MAIN+"StageConcurrentNativeTrialPolicy.kt": ("42343f821180cf93733a9aff80023ee723fabb80", "84f90a1c0d1730ac49ad54e79c28782f75594ddf"),
    MAIN+"StageGpuMirrorOcclusion.kt": ("f07f177d1987b19257a1490d72e188ad50008afc", "0a32a2c28c80547770512a51842dc21d4a7db144"),
    MAIN+"StageLiveSurfaceExchange.kt": ("f3681dbfde208d4c976af7938601d8657165f181", "e9a34583106aeebe3930b4b1d6b20096c7adbe0a"),
    MAIN+"StageLiveSurfaceFlightGeometry.kt": ("8849f80b22e3ce76c8ce78db6294c421023f954c", "18d3356bac56d6ce1d1c5d58cf0f369a7db06aac"),
    MAIN+"StagePrivateDualTaskFlightGate.kt": ("de055e62992e593235914bf339fb3127762449ae", "65d5ad2064c711ae3fba16e8762dce3c97fa94d8"),
    MAIN+"StageTransientTapPolicy.kt": ("a5312b523a810cfdb8e82e77618005335d9b9c17", "5041ec241992b3bb3a88f1661855d05704e3add1"),
    MAIN+"StageTransientTapSwap.kt": ("9fa8cdbb60fb2c2b6ec2740b595c39c537127a12", "7e9489d8aedeedea5beea0f653ce5a68f5006908"),
    TEST+"StageConcurrentNativeTrialPolicyTest.kt": ("0343597c1e9f0ed2a88caa4d8cef1eaa07193e6e", "266d315240fc1423f61b43af5d625348113c4baa"),
    TEST+"StageLiveSurfaceFlightGeometryTest.kt": ("b1d1fbf4032c2df0cae3886c761bb3bf0e675544", "e1795f15dd46ee5d9097a6efd0aaa520a5f812ef"),
    TEST+"StageTransientTapPolicyTest.kt": ("42b89180ab4c4407ff68c9d97ec4c3d260618b3a", "df51ba28dd1d32b13066eaf05af523df643d20b9"),
}

def check_scope(changed, observed):
    expected = set(PINS) | set(NEW)
    missing = expected - changed
    unexpected = changed - expected - {SELF}
    errors = []
    if missing:
        errors.append("missing expected changes: " + ", ".join(sorted(missing)))
    if unexpected:
        errors.append("unexpected changes: " + ", ".join(sorted(unexpected)))
    for file, pins in PINS.items():
        if observed.get(file) != pins:
            errors.append("blob mismatch: " + file)
    return errors

def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])

def check_candidate_contracts(root, ref):
    def read(path):
        return git(root, "show", ref + ":" + path).decode()
    def require(path, *markers):
        content = read(path)
        for marker in markers:
            if marker not in content:
                raise SystemExit("candidate contract missing: " + path + ": " + marker)
        return content
    require("app/build.gradle.kts",
            'buildConfigField("boolean", "ATOMIC_NATIVE_SURFACE_TRIAL", "false")',
            'versionCode = 86', 'versionName = "0.9.71-dev"',
            'applicationId = "io.github.x647368maker.atomicworkbench"')
    require(MAIN+"StagePrivateDualTaskFlightGate.kt", "BuildConfig.ATOMIC_NATIVE_SURFACE_TRIAL")
    require(MAIN+"StageTransientTapPolicy.kt", "!StagePrivateDualTaskFlightGate.enabled()")
    swap = require(MAIN+"StageTransientTapSwap.kt",
                   "decideMoveRequest(current(),moveStarted,::stillValid)",
                   "StageTransientTapPolicy.canHandoff(flightDone,moveAck)")
    if swap.count("TaskSurfaceCompat.captureObservedSwapPreview(") != 2:
        raise SystemExit("snapshot count changed")
    if any(s in swap for s in ("captureDisplayPreview(", "setCaptureSecureLayers", "setAllowProtected")):
        raise SystemExit("unapproved capture path")
    require(MAIN+"StageLiveSurfaceExchange.kt",
            "StageConcurrentNativeTrialPolicy.sourceIdentityReady(",
            "StageConcurrentNativeTrialPolicy.movePresentation(",
            "if (concurrentHandoff && frameComplete && !unmaskedMove) armHeldEndpointWriter()",
            "handoffReady=finalAlphaSubmitted &&")
    require(MAIN+"StageLiveSurfaceFlightGeometry.kt",
            "peakSweptOverlap(start,middle,obstacle)<=limit",
            "peakSweptOverlap(middle,end,obstacle)<=limit")
    print("PASS: pinned candidate route, source, privacy and handoff markers.", flush=True)

def historical_on_normalized_copy(root, ref):
    # The old script and ALL old manifests stay byte-for-byte unchanged.
    # Run them only on a disposable view: exact pinned changes are reversed,
    # and the resulting Git tree must equal the complete reviewed base.
    if git(root, "rev-parse", "HEAD").decode().strip() != ref:
        raise SystemExit("audit must target the actual build checkout")
    if git(root, "status", "--porcelain", "--untracked-files=no").strip():
        raise SystemExit("tracked worktree changes are not permitted")
    base_tree = git(root, "rev-parse", BASE+"^{tree}").strip()
    with tempfile.TemporaryDirectory(prefix="atomic-history-") as tmp:
        view = Path(tmp) / "normalized"
        git(root, "worktree", "add", "--detach", "--quiet", str(view), ref)
        try:
            git(view, "restore", "--source", BASE, "--staged", "--worktree", "--", *PINS)
            git(view, "rm", "--force", "--", SELF, *NEW)
            if git(view, "write-tree").strip() != base_tree:
                raise SystemExit("normalized tree is not identical to historical base")
            if git(view, "diff", "--name-only").strip():
                raise SystemExit("normalized tracked worktree differs from index")
            print("Running unchanged full historical audit on exact normalized base tree.", flush=True)
            subprocess.run([sys.executable, str(view/"scripts/verify-state-continuity.py")],
                           cwd=view, check=True)
        finally:
            git(root, "worktree", "remove", "--force", str(view))
    if git(root, "status", "--porcelain", "--untracked-files=no").strip():
        raise SystemExit("build checkout changed during audit")
    print("PASS: historical baseline audit plus separately pinned candidate delta; actual build checkout untouched.", flush=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default="HEAD")
    parser.add_argument("--historical", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    ref = git(root, "rev-parse", "--verify", args.ref+"^{commit}").decode().strip()
    git(root, "merge-base", "--is-ancestor", BASE, ref)
    changed = set(git(root, "diff", "--name-only", "-z", "--no-renames", BASE, ref)
                  .decode().rstrip("\0").split("\0")) - {""}
    observed = {}
    for file in PINS:
        observed[file] = tuple(git(root, "rev-parse", commit+":"+file).decode().strip()
                               for commit in (BASE, ref))
    errors = check_scope(changed, observed)
    if errors:
        raise SystemExit("\n".join(errors))
    # Also reject changes in file modes and type, not only content blobs.
    for file in PINS:
        modes = [git(root, "ls-tree", commit, "--", file).split(b" ", 1)[0]
                 for commit in (BASE, ref)]
        if modes != [b"100644", b"100644"]:
            raise SystemExit("unexpected file mode: "+file)
    for file, pin in NEW.items():
        actual = git(root, "rev-parse", ref+":"+file).decode().strip()
        mode = git(root, "ls-tree", ref, "--", file).split(b" ", 1)[0]
        old = git(root, "ls-tree", BASE, "--", file)
        if actual != pin or mode != b"100644" or old:
            raise SystemExit("unexpected new test object: "+file)
    print("PASS: committed candidate matches 14 exact file pairs and 1 new pinned test; no extra paths except this auditor.")
    check_candidate_contracts(root, ref)
    if args.historical:
        historical_on_normalized_copy(root, ref)
    else:
        print("NOT RUN: historical freeze audit.")
    print("NOT RUN: Gradle, emulator, signing, OEM tests.")

if __name__ == "__main__":
    main()

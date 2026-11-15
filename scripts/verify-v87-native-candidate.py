#!/usr/bin/env python3
"""Verify exact scope of the isolated native-animation candidate.

This is an additional source-integrity preflight, NOT a substitute for
verify-state-continuity.py, Android builds, signing, or device validation.
It compares committed Git objects, not uncommitted worktree files.
"""
import argparse
import subprocess
from pathlib import Path

BASE = "913f338eff98a5957403c8a7fdfca53d35a0af30"
REVIEW_TARGET = "b4ef45a111702da7bee85029c981ccc31a9411e5"
SELF = "scripts/verify-v87-native-candidate.py"
MAIN = "app/src/main/kotlin/io/github/lujinxin/nextep/systemui/"
TEST = "app/src/test/kotlin/io/github/lujinxin/nextep/systemui/"
PINS = {
    "app/build.gradle.kts": ("a58e0476a3b47dd479427d53e7437961ba6a85c5", "3f7663838fab637cd45e6ad6dc3f12634e93bef8"),
    MAIN+"StageConcurrentNativeTrialPolicy.kt": ("42343f821180cf93733a9aff80023ee723fabb80", "84f90a1c0d1730ac49ad54e79c28782f75594ddf"),
    MAIN+"StageGpuMirrorOcclusion.kt": ("f07f177d1987b19257a1490d72e188ad50008afc", "0a32a2c28c80547770512a51842dc21d4a7db144"),
    MAIN+"StageLiveSurfaceExchange.kt": ("f3681dbfde208d4c976af7938601d8657165f181", "a451f740cb6e19b097a226566e00db168ee50d48"),
    MAIN+"StageLiveSurfaceFlightGeometry.kt": ("8849f80b22e3ce76c8ce78db6294c421023f954c", "18d3356bac56d6ce1d1c5d58cf0f369a7db06aac"),
    MAIN+"StagePrivateDualTaskFlightGate.kt": ("de055e62992e593235914bf339fb3127762449ae", "65d5ad2064c711ae3fba16e8762dce3c97fa94d8"),
    MAIN+"StageTransientTapPolicy.kt": ("a5312b523a810cfdb8e82e77618005335d9b9c17", "5041ec241992b3bb3a88f1661855d05704e3add1"),
    MAIN+"StageTransientTapSwap.kt": ("9fa8cdbb60fb2c2b6ec2740b595c39c537127a12", "41fa9d2284de77bf6130d012ba6aeced31b28234"),
    TEST+"StageConcurrentNativeTrialPolicyTest.kt": ("0343597c1e9f0ed2a88caa4d8cef1eaa07193e6e", "266d315240fc1423f61b43af5d625348113c4baa"),
    TEST+"StageLiveSurfaceFlightGeometryTest.kt": ("b1d1fbf4032c2df0cae3886c761bb3bf0e675544", "e1795f15dd46ee5d9097a6efd0aaa520a5f812ef"),
    TEST+"StageTransientTapPolicyTest.kt": ("42b89180ab4c4407ff68c9d97ec4c3d260618b3a", "df51ba28dd1d32b13066eaf05af523df643d20b9"),
}

def check_scope(changed, observed):
    expected = set(PINS)
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

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default="HEAD")
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
    print("PASS: committed candidate matches 11 exact file pairs; no extra paths except this auditor.")
    print("NOT RUN: historical freeze audit, Gradle, emulator, signing, OEM tests.")

if __name__ == "__main__":
    main()

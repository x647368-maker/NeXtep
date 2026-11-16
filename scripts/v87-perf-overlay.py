#!/usr/bin/env python3
"""Copy only the isolated instrumentation files to exact R9/R10 checkouts.
The protected original runner and migration fixture remain byte-identical.
"""
import hashlib
from pathlib import Path
import shutil
import sys
src, dest = map(Path, sys.argv[1:3])
rel = Path("app/src/androidTest/kotlin/io/github/x647368maker/atomicworkbench/visual")
def blob(p):
    b = p.read_bytes()
    return hashlib.sha1(("blob %d\0" % len(b)).encode()+b).hexdigest()
assert blob(dest / rel / "LivePreviewInstrumentation.kt") == "4011b6327ccfc1af0c57646a265a75c535ab7e8b", "unexpected historical runner"
assert blob(dest / rel / "TaskMigrationRegression.kt") == "03e99137c29e96d45601cfc8d81bf21a4e2aaa3d", "unexpected historical migration fixture"
for path in [Path("app/src/androidTest/AndroidManifest.xml"),*(rel / f for f in ("StagePerformanceEvidence.kt","PerformanceTaskMigrationProbe.kt","PerformanceEvidenceInstrumentation.kt"))]:
    assert not (dest / path).exists(), "test path already exists"
    shutil.copyfile(src / path, dest / path)

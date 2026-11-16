#!/usr/bin/env python3
"""Apply identical test-only measurement changes to exact R9/R10 checkouts.
Never replace another branch's animation/UI runner wholesale.
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

runner = dest / rel / "LivePreviewInstrumentation.kt"
migration = dest / rel / "TaskMigrationRegression.kt"
assert blob(runner) == "4011b6327ccfc1af0c57646a265a75c535ab7e8b", "unexpected historical runner"
assert blob(migration) == "03e99137c29e96d45601cfc8d81bf21a4e2aaa3d", "unexpected historical migration fixture"
s = runner.read_text()
def once(old, new):
    global s
    assert s.count(old) == 1, old
    s = s.replace(old, new)
once("    private var nativeMirrorOnly = false",
     "    private var performanceOnly = false\n    private var nativeMirrorOnly = false")
once('        nativeMirrorOnly = arguments?.getString("nativeMirrorOnly") == "true"',
     '        performanceOnly = arguments?.getString("performanceOnly") == "true"\n        nativeMirrorOnly = arguments?.getString("nativeMirrorOnly") == "true"')
# Reuse the exact R11 performance-only branch body; keep original modes intact.
r11 = (src / rel / "LivePreviewInstrumentation.kt").read_text()
a = r11.index("            if (performanceOnly) {")
b = r11.index("            if (diagnosticOnly) {", a)
once("            if (diagnosticOnly) {",
     '            check(listOf(performanceOnly, nativeMirrorOnly, diagnosticOnly).count { it } <= 1)\n'+r11[a:b]+"            if (diagnosticOnly) {")
runner.write_text(s)
for f in ("StagePerformanceEvidence.kt", "TaskMigrationRegression.kt"):
    shutil.copyfile(src / rel / f, dest / rel / f)

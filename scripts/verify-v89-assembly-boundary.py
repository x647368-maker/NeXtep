#!/usr/bin/env python3
"""Audit the original continuity baseline AND the exact staged v89 delta.

The historical checker is intentionally unmodified. Re-run it in a detached
worktree of the last fully reviewed P0-1 tree, as the existing P0-2a gate does.
Each later checkpoint is immutable and its independently scoped path set is
enumerated here; a new candidate/file change requires a new explicit review.
This is not proof of OEM pixel presentation or acceptance.
"""
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
P = "app/src/main/kotlin/io/github/lujinxin/nextep/"
T = "app/src/test/kotlin/io/github/lujinxin/nextep/"
A = "app/src/androidTest/kotlin/io/github/x647368maker/atomicworkbench/visual/"
W = ".github/workflows/"
BASE = "6ddc3d4333d0aecc7f5a8218aab8e788906156ce"
CANDIDATE = "5d8648a76406ae1ed7990a63dfeb76697a08674d"
GATE = "scripts/verify-v89-assembly-boundary.py"
WORKFLOW = W+"atomic-android.yml"

# Exact incremental file sets, cross-checked against the staged PRs and
# immutable compare results. Do not derive these from current HEAD at runtime.
STEPS = (
    ("b3313c558c02ae1bbc6379875c5eaa3c010660ef", {
        W+"v89-p0-2a-graphics-gate.yml", W+"v89-p0-2a-unit-gate.yml",
        A+"PairedMotionClockRegression.kt", A+"PhasedPreviewRegression.kt",
        P+"systemui/ExchangeMotionPlan.kt", P+"systemui/StageTransientTapSwap.kt",
        P+"systemui/WorkspaceSwapAnimator.kt", "scripts/verify-v89-p02a-boundary.py",
    }),
    ("2d486febdfc9848ea2a4189e29498e2f56ba6b33", {
        W+"v89-handoff-graphics.yml", W+"v89-handoff-unit.yml",
        W+"v89-retention-unit-gate.yml", P+"logging/StageSwapTraceBuffer.kt",
        P+"systemui/NeXtepWindowController.kt", P+"systemui/StageAckDispatch.kt",
        P+"systemui/StageLiveSurfaceExchange.kt", T+"logging/StageSwapTraceBufferTest.kt",
        T+"systemui/StageAckDispatchTest.kt", "scripts/test-v89-handoff-wiring.py",
    }),
    ("e69bf898d28f0ca3fb528e691c5052fd672c967d", {
        W+"v89-build-revision-gate.yml", "app/build.gradle.kts",
        P+"systemui/StageRuntimeIdentity.kt", T+"systemui/StageRuntimeIdentityTest.kt",
    }),
    ("11a56ef5286974cc5013cf8b4cdfb87d30226701", {
        W+"v89-main-reveal-blend-gate.yml", W+"v89-p0-zoom-integrated-hwui-ci.yml",
        A+"StageTransientDualFlightRegression.kt", P+"framework/StagePhasedPromotionEntryPolicy.kt",
        P+"framework/TaskSurfaceCompat.kt", P+"systemui/StageMainEndpointRevealPolicy.kt",
        P+"systemui/StageTransientTapSwap.kt", T+"framework/StagePhasedPromotionEntryPolicyTest.kt",
        T+"systemui/StageMainEndpointRevealPolicyTest.kt",
    }),
    ("1d6468ce4c198bf9b557532eb5fa417fc4f301d9", {
        W+"v89-main-surface-commit-proof-ci.yml", P+"framework/TaskPresentationCommitProof.kt",
        P+"framework/TaskSurfaceCompat.kt", T+"framework/TaskPresentationCommitProofTest.kt",
    }),
    ("551215922630f170bb6fd0f333542787035b627a", {
        W+"v89-one-main-animator-ci.yml", P+"framework/StagePhasedPromotionEntryPolicy.kt",
        P+"framework/TaskSurfaceCompat.kt", T+"framework/StagePhasedPromotionEntryPolicyTest.kt",
    }),
    ("de505e0e1f172897e89e87c0669dec80f4c2cf50", {
        W+"v89-main-receipt-generation-ci.yml", P+"framework/TaskPresentationCommitProof.kt",
        P+"framework/TaskSurfaceCompat.kt", T+"framework/TaskPresentationCommitProofTest.kt",
    }),
    ("308a29b067159d75af0ef91978461808fe253d74", {
        W+"v89-visual-handoff-owner-ci.yml", P+"display/SlotTaskCoordinator.kt",
        P+"framework/StageMainVisualHandoffHold.kt", P+"framework/StagePhasedPromotionEntryPolicy.kt",
        P+"framework/TaskSurfaceCompat.kt", T+"framework/StagePhasedPromotionEntryPolicyTest.kt",
    }),
    ("c02258c4e799db5349b7d5a949b1bb92d1d4f53f", {
        W+"v89-handoff-writer-evidence-ci.yml", P+"framework/TaskSurfaceCompat.kt",
        P+"logging/StageSurfaceHandoffTrace.kt", P+"logging/StageSwapTraceBuffer.kt",
        P+"systemserver/WorkspaceTaskSurfaceHook.kt", P+"systemui/StageLiveSurfaceExchange.kt",
        T+"logging/StageSwapTraceBufferTest.kt",
    }),
    ("235c0cea0a3a50ae3bfeec9b1b1aad56b319499f", {
        WORKFLOW, "scripts/verify-release-notes-version.py",
    }),
    ("f7838be00091487e0dcb85c3492271c67a55df5d", {
        W+"v89-handoff-txt-evidence-ci.yml", P+"config/StageIssueDiagnosticActivity.kt",
        P+"config/StageIssueFinishCoordinator.kt", P+"config/StageIssueSession.kt",
        P+"logging/StageHandoffEvidenceBuffer.kt", P+"logging/StageSurfaceHandoffTrace.kt",
        P+"monitoring/StageHandoffEvidenceContract.kt", P+"monitoring/WorkspaceEnergyContract.kt",
        P+"workspace/SystemServerWorkspaceBridge.kt", T+"logging/StageHandoffEvidenceBufferTest.kt",
    }),
    (CANDIDATE, {
        W+"v89-handoff-evidence-reachable-ci.yml",
        P+"systemserver/NativeFloatingWindowLayerHooker.kt", P+"workspace/SystemServerWorkspaceBridge.kt",
    }),
)

def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)

def paths(before, after):
    return set(git("diff", "--no-renames", "--name-only", "-z", before, after)
               .decode("utf-8").rstrip("\0").split("\0")) - {""}

def equal(actual, expected, label):
    if actual != expected:
        raise SystemExit(f"BLOCKED {label}: extra={sorted(actual-expected)}, missing={sorted(expected-actual)}")

def require(condition, message):
    if not condition:
        raise SystemExit("BLOCKED "+message)

def source(rel, *markers):
    s = (ROOT/(P+rel)).read_text()
    for marker in markers:
        require(marker in s, f"{rel} missing {marker!r}")
    return s

def historical_gate():
    with tempfile.TemporaryDirectory(prefix="atomic-v89-baseline-") as temp:
        tree = Path(temp)/"tree"
        subprocess.run(["git", "worktree", "add", "--detach", str(tree), BASE],
                       cwd=ROOT, check=True)
        try:
            subprocess.run([sys.executable, "scripts/verify-state-continuity.py"],
                           cwd=tree, check=True)
        finally:
            subprocess.run(["git", "worktree", "remove", "--force", str(tree)],
                           cwd=ROOT, check=True)

def main():
    require(not git("status", "--porcelain", "--untracked-files=no"),
            "tracked worktree differs from compiled checkout")
    # Entire pinned candidate tree (including all unlisted files) must survive
    # untouched except for exactly this new verification script and one CI line.
    subprocess.run(["git", "merge-base", "--is-ancestor", CANDIDATE, "HEAD"], cwd=ROOT, check=True)
    equal(paths(CANDIDATE, "HEAD"), {GATE, WORKFLOW}, "post-candidate delta")
    old = git("show", CANDIDATE+":"+WORKFLOW).decode()
    marker = "        run: python3 scripts/verify-state-continuity.py\n"
    replacement = "        run: python3 "+GATE+"\n"
    require(old.count(marker)==1 and (ROOT/WORKFLOW).read_text()==old.replace(marker,replacement,1),
            "CI changed beyond the exact gate invocation")
    parent = BASE
    for checkpoint, allowed in STEPS:
        subprocess.run(["git","merge-base","--is-ancestor",parent,checkpoint],cwd=ROOT,check=True)
        equal(paths(parent, checkpoint), allowed, checkpoint[:12])
        print("PASS exact staged delta", checkpoint[:12], len(allowed), flush=True)
        parent = checkpoint

    # Preserve the whole older source guard, including all original manifests.
    historical_gate()
    # Direct checks on the current source, not just an audit of old source.
    c=source("display/SlotTaskCoordinator.kt",
             "StagePhasedFinishGate.canFinish(token==transitionGeneration,",
             "transitionInProgress,exchangeSession?.settled!=false,animationFinished)",
             "TaskSurfaceCompat.beginMainVisualHandoff(",
             "queryTasksNonBlocking(::forwardCurrent)")
    require(c.count("stillCurrent=::forwardCurrent")==4, "forward task ownership guards")
    require(c.count("releasePhasedMainVisualHold()")==3, "exact visual hold lifecycle")
    for file in ("systemui/StageMediaSignals.kt", "systemui/StagePlaybackObservation.kt",
                 "systemui/NeXtepWindowController.kt", "display/SlotTaskCoordinator.kt",
                 "display/VirtualDisplaySlot.kt"):
        require(not re.search(r'transportControls|dispatchMediaButtonEvent|requestAudioFocus|abandonAudioFocus|KEYCODE_MEDIA|\.reload\s*\(',
                              source(file)), file+" passive media boundary")
    source("framework/TaskSurfaceCompat.kt", "TaskPresentationCommitProof.needsReceipt(",
           "presentation.commitProof.claimListener(ticket)", "TaskPresentationCommitProof.matchesCommittedReceipt(",
           "mainVisualHandoffs.holds(taskId,observedStates[taskId]?.token)",
           "commitMoveRepositionWithoutExtraAnimator(")
    source("systemui/StageTransientTapSwap.kt", "val frameProgress=sampleGeometryProgress(field)",
           "TimeInterpolator{ motionPlan.geometryProgress(it) }")
    source("systemui/WorkspaceSwapAnimator.kt", "preview.usesMotionPlan -> android.animation.TimeInterpolator { it }",
           "val frameProgress=sampleGeometryProgress(progress)", "StagePreviewFillPolicy.frame")
    source("monitoring/StageHandoffEvidenceContract.kt", "TriggerBroadcastContract.CONTROL_PERMISSION",
           "isOrderedBroadcast", "setPackage(target)")
    s=source("systemserver/NativeFloatingWindowLayerHooker.kt", "startWorkspaceObservation(display)",
             "StageHandoffEvidenceContract.register(context, observationHandler)")
    require(s.index("observationHandler.post {") < s.index("StageHandoffEvidenceContract.register(context, observationHandler)"),
            "server diagnostics startup lane")
    for helper in ("scripts/test-v89-handoff-wiring.py", "scripts/verify-issue-auto-stop.py",
                   "scripts/verify-release-notes-version.py"):
        subprocess.run([sys.executable,helper],cwd=ROOT,check=True)
    print("PASS historical baseline, exact staged source continuity and current safety markers; OEM device not accepted")

if __name__=="__main__":
    main()

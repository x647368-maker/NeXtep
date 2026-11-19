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
    # Keep the previously reviewed assembly and the exact cover-only layer.
    assembly = "026f07b95b214e227267a174ccb674c44afd2e45"
    cover = "a45b4537741bbd1d0e7ef1ae962950a3ec0c3160"
    cover_paths = {P+"systemui/StageLiveSurfaceExchange.kt",
                   P+"systemui/StageHeldEndpointPolicy.kt",
                   T+"systemui/StageHeldEndpointPolicyTest.kt"}
    for parent, child in ((CANDIDATE, assembly), (assembly, cover), (cover, "HEAD")):
        subprocess.run(["git", "merge-base", "--is-ancestor", parent, child], cwd=ROOT, check=True)
    equal(paths(CANDIDATE, assembly), {GATE, WORKFLOW}, "original assembly delta")
    equal(paths(assembly, cover), cover_paths, "covered mirror continuity delta")
    rejected = "0e7bf3b9c0baab23d2ba0e74cf70dd1995f45830"
    evidence = "5bc5159ea541de1a690cda4f3fe1d7ba878739ef"
    for parent, child in ((cover, rejected), (rejected, evidence), (evidence, "HEAD")):
        subprocess.run(["git", "merge-base", "--is-ancestor", parent, child], cwd=ROOT, check=True)
    equal(paths(cover, rejected), {GATE}, "previous candidate gate delta")
    equal(paths(rejected, evidence), {
        P+"logging/StageAttemptEvidence.kt", P+"logging/StageHandoffEvidenceBuffer.kt",
        P+"logging/StageSurfaceHandoffTrace.kt", P+"logging/StageExchangeTelemetry.kt",
        P+"framework/TaskSurfaceCompat.kt", P+"systemui/StageLiveSurfaceExchange.kt",
        P+"monitoring/StageHandoffEvidenceContract.kt", P+"config/StageIssueSession.kt",
        P+"config/StageIssueReportPolicy.kt", P+"systemui/NeXtepWindowController.kt",
        T+"logging/StageAttemptEvidenceTest.kt",
    }, "explicit failed-device evidence repair")
    diagnostic = "5f70f77a81faffb368c98e3659ba701ae2e747fb"
    subprocess.run(["git", "merge-base", "--is-ancestor", diagnostic, "HEAD"], cwd=ROOT, check=True)
    equal(paths(evidence, diagnostic), {GATE}, "post-evidence gate-only delta")
    contract_fix = "2f4a6d3cefd0c86cd5339f562643dcb6c1028dad"
    subprocess.run(["git", "merge-base", "--is-ancestor", contract_fix, "HEAD"], cwd=ROOT, check=True)
    equal(paths(diagnostic, contract_fix), {
        GATE, P+"systemui/StageLiveSurfaceExchange.kt",
        T+"systemui/StageMirrorRootContractTest.kt",
    }, "explicit P0 mirror contract admission delta")
    first_frame_fix = "6c770090b8ec05906ad4b0cd712a4ab60a8a9858"
    subprocess.run(["git", "merge-base", "--is-ancestor", first_frame_fix, "HEAD"], cwd=ROOT, check=True)
    equal(paths(contract_fix, first_frame_fix), {
        GATE, P+"systemui/StageLiveSurfaceExchange.kt",
        P+"systemui/StageLiveSurfaceFlightGeometry.kt",
        P+"framework/TaskSurfaceCompat.kt",
        P+"systemui/StageConcurrentNativeTrialPolicy.kt",
        T+"systemui/StageMainMirrorStartTest.kt",
    }, "explicit owned source first-frame mapping delta")
    coordinate_fix = "81fcf72f7380d5fa79ff8f034bce93aeac58e27b"
    subprocess.run(["git", "merge-base", "--is-ancestor", coordinate_fix, "HEAD"], cwd=ROOT, check=True)
    equal(paths(first_frame_fix, coordinate_fix), {
        GATE, P+"systemui/StageLiveSurfaceExchange.kt",
        P+"systemui/StageHeldEndpointPolicy.kt",
        T+"systemui/StageMirrorRootContractTest.kt",
        T+"systemui/StageMainDestinationCoordinateTest.kt",
    }, "isolated mirror coordinate observation and destination affine delta")
    evidence_trial = "c9dd04350bfc4986b4e197f040852e204abc6c23"
    subprocess.run(["git", "merge-base", "--is-ancestor", evidence_trial, "HEAD"], cwd=ROOT, check=True)
    equal(paths(coordinate_fix, evidence_trial), {
        GATE, P+"systemui/StageLiveSurfaceExchange.kt",
        T+"systemui/StageMirrorRootContractTest.kt",
    }, "explicit private firmware-scoped evidence trial only")
    diag_retry = "30498e1a5c5e5a0390e320e8fc6f629d560ff2a3"
    subprocess.run(["git", "merge-base", "--is-ancestor", diag_retry, "HEAD"], cwd=ROOT, check=True)
    equal(paths(evidence_trial, diag_retry), {
        GATE, P+"systemui/StageLiveSurfaceExchange.kt",
        P+"systemui/StageConcurrentNativeTrialPolicy.kt",
        T+"systemui/StageMirrorRootContractTest.kt",
    }, "isolated opt-in diagnostic retry delta")
    content_candidate = "72bd16eeeba65ae31c151abeb59f7b8c1c061604"
    subprocess.run(["git", "merge-base", "--is-ancestor", content_candidate, "HEAD"], cwd=ROOT, check=True)
    equal(paths(diag_retry, content_candidate), {
        GATE, P+"systemui/StageLiveSurfaceExchange.kt",
        P+"systemui/StageConcurrentNativeTrialPolicy.kt",
        P+"framework/TaskSurfaceCompat.kt", P+"display/TaskSwitcherView.kt",
        P+"systemui/StageEndpointContentMapping.kt",
        T+"systemui/StageEndpointContentMappingTest.kt",
    }, "isolated content endpoint trial delta")
    dest_read = "bd6a1d3957b75605e2c64682e780386092281d72"
    subprocess.run(["git", "merge-base", "--is-ancestor", dest_read, "HEAD"], cwd=ROOT, check=True)
    equal(paths(content_candidate, dest_read), {
        GATE, P+"framework/TaskSurfaceCompat.kt", P+"systemui/StageLiveSurfaceExchange.kt",
        T+"systemui/StageEndpointContentMappingTest.kt",
    }, "verified demotion read only at final content check")
    common_domain = "41adf07aa0d7a7e1b8de054c4feb0122fb6ed4bd"
    subprocess.run(["git", "merge-base", "--is-ancestor", common_domain, "HEAD"], cwd=ROOT, check=True)
    equal(paths(dest_read, common_domain), {
        GATE, P+"systemui/StageEndpointContentMapping.kt", P+"systemui/StageLiveSurfaceExchange.kt",
        T+"systemui/StageEndpointContentMappingTest.kt",
    }, "conservative old/new common domain; no metadata-as-buffer expansion")
    read_retry = "ce4bd290a98402041e565266ef41e87b238c2551"
    subprocess.run(["git", "merge-base", "--is-ancestor", read_retry, "HEAD"], cwd=ROOT, check=True)
    equal(paths(common_domain, read_retry), {
        GATE, P+"systemui/StageEndpointContentMapping.kt", P+"systemui/StageLiveSurfaceExchange.kt",
        T+"systemui/StageEndpointContentMappingTest.kt",
    }, "bounded destination evidence retry only")
    completed_demotion = "62ffa0db7e1633939c4b8a0e8a9c16ab5d707bbc"
    subprocess.run(["git", "merge-base", "--is-ancestor", completed_demotion, "HEAD"], cwd=ROOT, check=True)
    equal(paths(read_retry, completed_demotion), {
        GATE, P+"framework/TaskSurfaceCompat.kt", P+"framework/ObservedDemotionFitPolicy.kt",
        P+"systemui/StageLiveSurfaceExchange.kt", T+"framework/ObservedDemotionFitPolicyTest.kt",
    }, "read-only completed demotion mirror admission")
    equal(paths(completed_demotion, "HEAD"), {
        GATE, P+"framework/TaskSurfaceCompat.kt",
    }, "revoke completion marker on failed final recheck")
    source("framework/TaskSurfaceCompat.kt", "if(!retained)slot.completedMirrorFit=null")
    source("framework/ObservedDemotionFitPolicy.kt", "completedFit===currentFit",
           "noConflictingPresentation && noHeldWriter")
    source("framework/TaskSurfaceCompat.kt", "slot.completedMirrorFit=slot.confirmedDemotion",
           "slot!==ticket || heldMirrorWriter.get()!=null", "completedSlotMirrorSource(stamp.taskId,slot,state)",
           "fun recordSlotMirrorCompletion(", "fun captureSlotMirrorCompletion(")
    source("systemui/StageLiveSurfaceExchange.kt",
           "allowCompletedDemotion=inEvidenceScope && diagnosticOptIn",
           "TaskSurfaceCompat.captureSlotMirrorCompletion(it)",
           "TaskSurfaceCompat.recordSlotMirrorCompletion(completedSlotStamp,completedSlotTicket)",
           "cleaned && completedSlotStamp!=null && completedSlotTicket!=null",
           "firstTransactionCommitted && terminalTransactionCommitted && nativeFrameCount>1")
    source("systemui/StageEndpointContentMapping.kt", "liveReady && read==DestinationRead.READY",
           "StageConcurrentNativeTrialPolicy.handoffAction(elapsedMs,timeoutMs,")
    source("systemui/StageLiveSurfaceExchange.kt", 'missing("main_identity")',
           'missing("slot_texture_identity")', 'missing("main_geometry",pending=true)',
           "content_geometry_pending", "content_target_geometry_rejected")
    for rel in ("framework/TaskSurfaceCompat.kt","systemui/StageConcurrentNativeTrialPolicy.kt"):
        path=P+rel
        require(git("show",read_retry+":"+path)==git("show",common_domain+":"+path),
                "destination read retry changed task ownership or timing policy "+path)
    source("systemui/StageEndpointContentMapping.kt", "projected(common,sourceRoot)",
           "projected(overlap(local,a.commonLocal)", "val end=a.terminalAvailable")
    source("systemui/StageLiveSurfaceExchange.kt", "content_domain_gap", "buffer_latch_unobserved")
    source("framework/TaskSurfaceCompat.kt", "readSlotMirrorFrame(stamp,allowVerifiedDestination=false)",
           "readSlotMirrorFrame(stamp,allowVerifiedDestination=true)",
           "(allowVerifiedDestination && verifiedDemotedSlot(stamp.taskId,slot,state))")
    source("systemui/StageLiveSurfaceExchange.kt",
           "TaskSurfaceCompat.heldDestinationSlotMirrorFrame(slotStamp)",
           "TaskSurfaceCompat.ownedSlotMirrorFrame(incomingStamp,allowCompletedDemotion=true)")
    source("systemui/StageEndpointContentMapping.kt", "fun compose(", "fun mapError(", "fun held(", "fun flight(")
    source("systemui/StageLiveSurfaceExchange.kt", "if(inEvidenceScope && diagnosticOptIn)",
           "endpoint_content_anchor_trial=both", "StageEndpointContentMapping.flight(",
           "StageEndpointContentMapping.held(", 'destinationContentRead("ready")',
           'destinationContentMatches("reveal_end")', "content_terminal")
    source("display/TaskSwitcherView.kt", "internal fun mirrorLiveContentBounds()")
    source("framework/TaskSurfaceCompat.kt", "internal fun ownedSlotMirrorFrame(")
    for path in (P+"systemui/StageLiveSurfaceFlightGeometry.kt", P+"systemui/StageHeldEndpointPolicy.kt"):
        require((ROOT/path).read_bytes()==git("show", diag_retry+":"+path),
                "old coordinate/route policies changed "+path)
    source("systemui/StageLiveSurfaceExchange.kt",
           "StageMirrorEvidenceTrial.awaitingDiagnostic(observation,inEvidenceScope,diagnosticOptIn)",
           "return markUnavailable(StageMirrorEvidenceTrial.DIAGNOSTIC_REQUIRED)")
    current_retry_source = source("systemui/StageConcurrentNativeTrialPolicy.kt",
                          '"fallback:"+StageMirrorEvidenceTrial.DIAGNOSTIC_REQUIRED')
    retry_source = git("show", diag_retry+":"+P+"systemui/StageConcurrentNativeTrialPolicy.kt").decode()
    require(current_retry_source == retry_source.replace(
        '        "fallback:"+StageMirrorEvidenceTrial.DIAGNOSTIC_REQUIRED,',
        '        "fallback:"+StageMirrorEvidenceTrial.DIAGNOSTIC_REQUIRED,\n'
        '        "fallback:slot_content_mapping_unavailable",\n'
        '        "fallback:content_anchor_unrepresentable",', 1),
        "only content geometry readiness may be newly transient")
    require(retry_source == git("show", evidence_trial+":"+P+"systemui/StageConcurrentNativeTrialPolicy.kt").decode().replace(
            '        "fallback:main_source_mapping_unrepresentable",',
            '        "fallback:main_source_mapping_unrepresentable",\n'
            '        // Only the precisely scoped evidence candidate can emit this reason.\n'
            '        // Re-evaluate on the next user tap; never clear a prior OEM denial.\n'
            '        "fallback:"+StageMirrorEvidenceTrial.DIAGNOSTIC_REQUIRED,', 1),
            "session policy changed beyond the single transient reason")
    source("systemui/NeXtepWindowController.kt",
           "if (StageConcurrentNativeTrialPolicy.transientMirrorOutcome(reason))",
           'StageConcurrentNativeTrialPolicy.disableForSession("OEM mirror unavailable: "+reason)')
    mirror_source = source("systemui/StageLiveSurfaceExchange.kt",
                           "DETACHED, RETAINED, UNKNOWN", "return markUnavailable(\"mirror_root_unknown\")")
    require("unknown_legacy" not in mirror_source, "no unknown-as-retained route")
    require(mirror_source.index("val detached=contract.detachedOrNull()") <
            mirror_source.index("val firstMain=StageLiveSurfaceFlightGeometry.mainMirrorStart(") <
            mirror_source.index("TaskSurfaceCompat.createOwnedMainMirror(mainTaskId)"),
            "unknown rejected before geometry and mirror acquisition")
    source("framework/TaskSurfaceCompat.kt", "fun ownedMainMirrorFrame(", "p.frame !== frame")
    source("systemui/StageLiveSurfaceExchange.kt", "sameMirrorContent(firstMain,plannedFirst)", "mainFirstFrame ?: plannedOutgoing", "mainSourceStillMatches()")
    source("logging/StageAttemptEvidence.kt", "max_scale_step=", "task_pair up=", "stage_overflow=")
    source("logging/StageSurfaceHandoffTrace.kt", "task_write_temporal", "fun bindPair(", "if(!evidence.hasOpenCapture())return")
    source("systemui/StageLiveSurfaceExchange.kt", "traceMirrorAdmission(detached)", '"held_mirror"')
    source("framework/TaskSurfaceCompat.kt", "StageSurfaceHandoffTrace.taskWriter(")
    source("systemui/StageLiveSurfaceExchange.kt",
           "StageHeldEndpointPolicy.coveredFit(", "covered ?: StageHeldEndpointPolicy.fit(",
           "frame.token!=stamp.token || frame.leash!==stamp.leash")
    source("systemui/StageHeldEndpointPolicy.kt", "fun coveredFit(", "fun mainDestinationFit(")
    source("systemui/StageLiveSurfaceExchange.kt", "mirror_root_observation=",
           "StageHeldEndpointPolicy.mainDestinationFit(", "if(incoming && mainFit==null)")
    require("incomingRebaseReceiptArmed" in mirror_source, "receipt-generation work deliberately deferred")
    source("systemui/StageLiveSurfaceExchange.kt",
           "BuildConfig.DEBUG && BuildConfig.ATOMIC_NATIVE_SURFACE_TRIAL",
           "Build.FINGERPRINT.toByteArray(Charsets.UTF_8)",
           "hex==EVIDENCE_TRIAL_FIRMWARE_SHA256",
           ".ticket(SystemClock.elapsedRealtime())!=null",
           "StageMirrorEvidenceTrial.resolve(observation,inEvidenceScope,diagnosticOptIn)",
           "detached_evidence_trial")
    require("Build.MODEL" not in mirror_source and "Build.MANUFACTURER" not in mirror_source,
            "no brand/model root contract inference")
    # The previous coordinate-only delta remains frozen at diag_retry.
    for unchanged in ("framework/TaskSurfaceCompat.kt", "systemui/StageLiveSurfaceFlightGeometry.kt"):
        path = P+unchanged
        require(git("show", diag_retry+":"+path)==git("show", first_frame_fix+":"+path),
                "coordinate-only stage unexpectedly changed "+unchanged)
    require("randomizedRebaseRespectsSourceBoundsAndUniformCenteredCover" in
            (ROOT/(T+"systemui/StageHeldEndpointPolicyTest.kt")).read_text(),
            "randomized cover continuity regression")
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

package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageConcurrentNativeTrialPolicyTest {
    @Test fun firstReceiptUsesOriginalDeadlineNotDelayedMainDispatch() {
        val p=StageConcurrentNativeTrialPolicy
        assertFalse(p.firstReceiptWithinBudget(100,-1,48))
        assertFalse(p.firstReceiptWithinBudget(-1,100,48))
        assertFalse(p.firstReceiptWithinBudget(100,99,48))
        assertTrue(p.firstReceiptWithinBudget(100,100,48))
        assertTrue(p.firstReceiptWithinBudget(100,148,48))
        assertFalse(p.firstReceiptWithinBudget(100,149,48))
        assertFalse(p.firstReceiptWithinBudget(100,100,-1))
    }
    @Test fun firstGpuCommitFailureCanFallbackOnlyBeforeAnyFrameOrTaskMove() {
        val p=StageConcurrentNativeTrialPolicy
        for(current in listOf(false,true))
            for(attached in listOf(false,true))
                for(source in listOf(false,true))
                    for(moved in listOf(false,true))
                        for(frames in listOf(-1,0,1)) {
                            val expected=current && attached && source && !moved && frames==0
                            assertEquals(expected,p.canFallbackBeforeFirstGpuFrame(
                                current,attached,source,moved,frames))
                        }
    }
    @Test fun maskedHandoffRequiresCompositorAcknowledgement() {
        val p=StageConcurrentNativeTrialPolicy
        for(first in listOf(false,true)) for(required in listOf(false,true))
            for(terminal in listOf(false,true)) {
                val expected=if(first && (!required || terminal))
                    StageConcurrentNativeTrialPolicy.MovePresentation.ANIMATED
                else StageConcurrentNativeTrialPolicy.MovePresentation.UNMASKED
                assertEquals(expected,p.movePresentation(first,required,terminal))
            }
        assertEquals(64L,p.TERMINAL_COMMIT_BUDGET_MS)
        // Submission alone or a lost first-commit callback is not evidence
        // of a fully acknowledged experimental endpoint.
        assertEquals(StageConcurrentNativeTrialPolicy.MovePresentation.UNMASKED,
            p.movePresentation(true,true,false))
        assertEquals(StageConcurrentNativeTrialPolicy.MovePresentation.UNMASKED,
            p.movePresentation(false,true,true))
    }
    @Test fun nativeTeardownRunsAllStepsDespiteVendorAndLoggerFailures() {
        val policy=StageConcurrentNativeTrialPolicy
        for(mask in 0 until 128) for(loggerFails in listOf(false,true)) {
            val visited=mutableListOf<Int>()
            var errors=0
            val steps=Array<()->Unit>(7){index->
                {
                    visited.add(index)
                    if(mask and (1 shl index)!=0) error("release failed")
                }
            }
            policy.cleanupSteps(*steps,onFailure={
                errors++
                if(loggerFails) error("logger failed")
            })
            assertEquals((0..6).toList(),visited)
            assertEquals(Integer.bitCount(mask),errors)
        }
    }
    @Test fun nativeSourcePreflightRejectsDetachedOrReplacedLeashes() {
        val p=StageConcurrentNativeTrialPolicy
        for(host in listOf(false,true)) for(handles in listOf(false,true))
            for(incoming in listOf(false,true)) for(outgoing in listOf(false,true)) {
                var first=0
                var second=0
                assertEquals(host && handles && incoming && outgoing,
                    p.sourceIdentityReady(host,handles,{first++;incoming},{second++;outgoing}))
                assertEquals(if(host && handles) 1 else 0,first)
                assertEquals(if(host && handles && incoming) 1 else 0,second)
            }
        assertFalse(p.sourceIdentityReady(true,true,{error("stale source")},{true}))
        assertFalse(p.sourceIdentityReady(true,true,{true},{error("stale target")}))
        assertTrue(p.transientMirrorOutcome("fallback:mirror_source_changed"))
    }
    @Test fun existingPhysicalWindowAllowsNoNewViewRoot() {
        StageConcurrentNativeTrialPolicy.resetForTests()
        for((w,h) in listOf(1140 to 2616, 2248 to 2480, 2560 to 1600))
            assertTrue(StageConcurrentNativeTrialPolicy.fullScreenHost(0,0,w,h,w,h))
        assertFalse(StageConcurrentNativeTrialPolicy.fullScreenHost(0,85,1080,2300,1080,2400))
        assertFalse(StageConcurrentNativeTrialPolicy.fullScreenHost(0,0,900,2400,1080,2400))
    }
    @Test fun wallpaperCoverRequiresExactPhysicalHostButNativeGateIsUnchanged() {
        val p=StageConcurrentNativeTrialPolicy
        for((w,h) in listOf(1272 to 2772,2772 to 1272,2248 to 2480,
                2480 to 2248,2560 to 1600,1600 to 2560)) {
            assertTrue(p.wallpaperHostMatches(w,h,w,h))
            assertFalse(p.wallpaperHostMatches(w+1,h,w,h))
            assertFalse(p.wallpaperHostMatches(w,h+1,w,h))
            assertFalse(p.wallpaperHostMatches(w-1,h,w,h))
            assertFalse(p.wallpaperHostMatches(w,h-1,w,h))
            assertFalse(p.wallpaperHostMatches(h,w,w,h))
            assertTrue(p.fullScreenHost(0,0,w+1,h+1,w,h))
        }
        assertFalse(p.wallpaperHostMatches(0,0,0,0))
        assertFalse(p.wallpaperHostMatches(1,1,1,1))
    }

    @Test fun optionalWallpaperPublicationRejectsAllStaleOrIneligibleCombinations() {
        val p=StageConcurrentNativeTrialPolicy
        for(mask in 0 until 64) {
            fun yes(bit:Int)=mask and (1 shl bit)!=0
            assertEquals(mask==63,p.wallpaperTexturePublishable(
                yes(0),yes(1),yes(2),yes(3),yes(4),yes(5)))
        }
    }

    @Test fun wallpaperRecoveryIsBoundedAndOnlyForCallbackTimeout() {
        val p=StageConcurrentNativeTrialPolicy
        for(attempt in -1..3)for(reason in listOf("callback_wait:TimeoutException elapsed_ms=172",
            "fence_timeout elapsed_ms=120","unusable_buffer elapsed_ms=1","ready elapsed_ms=30",
            "ui_lane_denied elapsed_ms=0","callback_wait:InterruptedException elapsed_ms=10",
            "renderer_create:IllegalArgumentException elapsed_ms=2","not_reported")) {
            assertEquals(attempt==0 && reason.startsWith("callback_wait:TimeoutException "),
                p.retryWallpaperPreparation(attempt,reason))
        }
    }

    @Test fun trialAlwaysGatedByVisibilityAndSaver() {
        StageConcurrentNativeTrialPolicy.resetForTests()
        assertEquals(360L, StageConcurrentNativeTrialPolicy.DURATION_MS)
        assertTrue(StageConcurrentNativeTrialPolicy.allowed(true,true,false))
        assertFalse(StageConcurrentNativeTrialPolicy.allowed(false,true,false))
        assertFalse(StageConcurrentNativeTrialPolicy.allowed(true,false,false))
        assertFalse(StageConcurrentNativeTrialPolicy.allowed(true,true,true))
    }
    @Test fun nativeFlightGetsVisibleLeadInBeforeTaskHandoff() {
        StageConcurrentNativeTrialPolicy.resetForTests()
        assertFalse(StageConcurrentNativeTrialPolicy.shouldStartMove(0L,360L,false,0))
        assertFalse(StageConcurrentNativeTrialPolicy.shouldStartMove(70L,360L,false,0))
        assertFalse(StageConcurrentNativeTrialPolicy.shouldStartMove(360L,360L,false,0))
        // Real task reparenting must not alter live mirrors before final travel.
        assertFalse(StageConcurrentNativeTrialPolicy.shouldStartMove(280L,360L,true,8))
        assertFalse(StageConcurrentNativeTrialPolicy.shouldStartMove(353L,360L,true,8))
        assertFalse(StageConcurrentNativeTrialPolicy.shouldStartMove(354L,360L,true,2))
        assertTrue(StageConcurrentNativeTrialPolicy.shouldStartMove(354L,360L,true,3))
        // AnimationEnd itself starts the move when the last frame was dropped.
        assertFalse(StageConcurrentNativeTrialPolicy.shouldStartMove(360L,360L,true,0))
        assertFalse(StageConcurrentNativeTrialPolicy.shouldStartMove(-1L,205L,true,3))
        assertFalse(StageConcurrentNativeTrialPolicy.shouldStartMove(100L,0L,true,3))
    }
    @Test fun coldMirrorPreparationGetsOneBoundedRetry() {
        StageConcurrentNativeTrialPolicy.resetForTests()
        assertTrue(StageConcurrentNativeTrialPolicy.acceptPrepareTime(28))
        assertFalse(StageConcurrentNativeTrialPolicy.acceptPrepareTime(29))
        assertTrue(StageConcurrentNativeTrialPolicy.allowed(true,true,false))
        assertFalse(StageConcurrentNativeTrialPolicy.acceptPrepareTime(30))
        assertFalse(StageConcurrentNativeTrialPolicy.allowed(true,true,false))
        StageConcurrentNativeTrialPolicy.resetForTests()
        assertFalse(StageConcurrentNativeTrialPolicy.acceptPrepareTime(29))
        assertTrue(StageConcurrentNativeTrialPolicy.acceptPrepareTime(20))
        assertFalse(StageConcurrentNativeTrialPolicy.acceptPrepareTime(29))
        assertTrue(StageConcurrentNativeTrialPolicy.allowed(true,true,false))
        StageConcurrentNativeTrialPolicy.resetForTests()
        assertFalse(StageConcurrentNativeTrialPolicy.acceptPrepareTime(80))
        assertFalse(StageConcurrentNativeTrialPolicy.allowed(true,true,false))
        StageConcurrentNativeTrialPolicy.resetForTests()
        assertFalse(StageConcurrentNativeTrialPolicy.acceptPrepareTime(-1))
        assertTrue(StageConcurrentNativeTrialPolicy.allowed(true,true,false))
        StageConcurrentNativeTrialPolicy.disableForSession("OEM")
        assertFalse(StageConcurrentNativeTrialPolicy.allowed(true,true,false))
        StageConcurrentNativeTrialPolicy.resetForTests()
    }

    @Test fun diagnoseDifferentGateReasonsWithoutChangingExistingSafetyPolicy() {
        StageConcurrentNativeTrialPolicy.resetForTests()
        assertEquals("allowed",StageConcurrentNativeTrialPolicy.gateReason(true,true,false))
        assertEquals("workspace_or_sidebar_not_ready",
            StageConcurrentNativeTrialPolicy.gateReason(false,true,false))
        assertEquals("system_animators_disabled",
            StageConcurrentNativeTrialPolicy.gateReason(true,false,false))
        assertEquals("power_saver",StageConcurrentNativeTrialPolicy.gateReason(true,true,true))
        assertFalse(StageConcurrentNativeTrialPolicy.acceptPrepareTime(81))
        val reason=StageConcurrentNativeTrialPolicy.gateReason(true,true,false)
        assertTrue(reason.contains("prepare_timeout_81ms"))
        assertTrue(reason.contains("cooldown:"))
        assertFalse(StageConcurrentNativeTrialPolicy.allowed(true,true,false))
        StageConcurrentNativeTrialPolicy.resetForTests()
    }

    @Test fun slowPreparationsRecoverOnAnExplicitLaterTapButOemDenialDoesNot() {
        val policy=StageConcurrentNativeTrialPolicy
        policy.resetForTests()
        assertFalse(policy.acceptPrepareTime(29,100L))
        assertFalse(policy.acceptPrepareTime(30,200L))
        assertFalse(policy.allowed(true,true,false,1699L))
        assertTrue(policy.allowed(true,true,false,1700L))
        assertTrue(policy.acceptPrepareTime(18,1700L))
        assertFalse(policy.acceptPrepareTime(80,1800L))
        assertFalse(policy.allowed(true,true,false,5799L))
        assertTrue(policy.allowed(true,true,false,5800L))
        assertTrue(policy.acceptPrepareTime(20,5800L))
        policy.disableForSession("OEM explicitly denied")
        assertFalse(policy.allowed(true,true,false,100000L))
        policy.resetForTests()
    }

    @Test fun slotReadyRequiresPostMoveBuffer() {
        assertFalse(StageConcurrentNativeTrialPolicy.freshFrameAfterTaskAck(null,1L))
        assertFalse(StageConcurrentNativeTrialPolicy.freshFrameAfterTaskAck(42L,42L))
        assertFalse(StageConcurrentNativeTrialPolicy.freshFrameAfterTaskAck(42L,41L))
        assertTrue(StageConcurrentNativeTrialPolicy.freshFrameAfterTaskAck(42L,43L))
    }

    @Test fun onlyCommittedIdenticalNonterminalProgressIsRedundant() {
        val p=StageConcurrentNativeTrialPolicy
        for(concurrent in listOf(false,true))
            for(first in listOf(false,true))
                for(terminal in listOf(false,true)) {
                    val expected=concurrent && first && !terminal
                    assertEquals(expected,p.unchangedFlightFrame(concurrent,first,terminal,0f,0f))
                    assertEquals(expected,p.unchangedFlightFrame(concurrent,first,terminal,.25f,.25f))
                    assertFalse(p.unchangedFlightFrame(concurrent,first,terminal,.25f,.250001f))
                    assertFalse(p.unchangedFlightFrame(concurrent,first,terminal,0f,.000001f))
                    assertFalse(p.unchangedFlightFrame(concurrent,first,terminal,Float.NaN,Float.NaN))
                    assertFalse(p.unchangedFlightFrame(concurrent,first,terminal,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY))
                }
    }

    @Test fun terminalFrameDoesNotNeedAnimatorEndAndCannotBePremature() {
        val p=StageConcurrentNativeTrialPolicy
        assertFalse(p.terminalFrameDue(300,360,.98f,true,45))
        assertFalse(p.terminalFrameDue(353,360,.999f,true,45))
        assertFalse(p.terminalFrameDue(354,360,.999f,true,2))
        assertTrue(p.terminalFrameDue(354,360,.999f,true,45))
        assertTrue(p.terminalFrameDue(360,360,.9f,false,0))
        assertTrue(p.terminalFrameDue(359,360,1f,false,0))
        assertFalse(p.terminalFrameDue(0,0,1f,true,45))
    }

    @Test fun visibleConcurrentMoveRequiresTerminalReceipt() {
        val p=StageConcurrentNativeTrialPolicy
        for(concurrent in listOf(false,true))for(first in listOf(false,true))for(terminal in listOf(false,true)) {
            assertEquals(concurrent && first && !terminal,
                p.rejectUnconfirmedVisibleMove(concurrent,first,terminal))
        }
    }
    @Test fun terminalReceiptUsesActualArrivalNotUiDispatch() {
        val p=StageConcurrentNativeTrialPolicy
        assertFalse(p.terminalReceiptWithinBudget(-1,100))
        assertFalse(p.terminalReceiptWithinBudget(100,-1))
        assertFalse(p.terminalReceiptWithinBudget(100,99))
        assertTrue(p.terminalReceiptWithinBudget(100,100))
        assertTrue(p.terminalReceiptWithinBudget(100,164))
        assertFalse(p.terminalReceiptWithinBudget(100,165))
        assertFalse(p.terminalReceiptWithinBudget(100,250))
    }
    @Test fun terminalDeadlineIsOneShotAndBoundedAcrossAnimatorScales() {
        val p=StageConcurrentNativeTrialPolicy
        assertEquals(424L,p.terminalDeadlineDelay(360,1f))
        assertEquals(244L,p.terminalDeadlineDelay(360,.5f))
        assertEquals(784L,p.terminalDeadlineDelay(360,2f))
        assertEquals(64L,p.terminalDeadlineDelay(360,0f))
        assertEquals(1500L,p.terminalDeadlineDelay(380,10f))
        assertEquals(424L,p.terminalDeadlineDelay(360,Float.NaN))
    }

    @Test fun acknowledgedMoveNeverDependsOnAViewOrMissingAnimatorEndForever() {
        val p=StageConcurrentNativeTrialPolicy
        assertEquals(StageConcurrentNativeTrialPolicy.HandoffAction.WAIT,
            p.handoffAction(0,480,false,true,true))
        assertEquals(StageConcurrentNativeTrialPolicy.HandoffAction.EXPIRED,
            p.handoffAction(480,480,false,true,true))
        assertEquals(StageConcurrentNativeTrialPolicy.HandoffAction.EXPIRED,
            p.handoffAction(480,480,true,true,false))
        assertEquals(StageConcurrentNativeTrialPolicy.HandoffAction.EXPIRED,
            p.handoffAction(480,480,true,false,true))
        assertEquals(StageConcurrentNativeTrialPolicy.HandoffAction.READY,
            p.handoffAction(80,480,true,true,true))
        assertEquals(116L,p.nextHandoffCheck(100,580))
        assertEquals(580L,p.nextHandoffCheck(578,580))
    }

    @Test fun adaptiveZeroGapBlendRequiresBothTargetTransactionReceiptsAndBudget() {
        val p=StageConcurrentNativeTrialPolicy
        for(mask in 0..31) {
            val adaptive=mask and 1!=0
            val first=mask and 2!=0
            val incoming=mask and 4!=0
            val outgoing=mask and 8!=0
            val failed=mask and 16!=0
            assertEquals(mask==15,p.adaptiveHandoffBlend(adaptive,first,incoming,outgoing,
                failed,200L,1f))
        }
        val budget=p.ADAPTIVE_HANDOFF_REVEAL_MS+p.TERMINAL_COMMIT_BUDGET_MS
        assertTrue(p.adaptiveHandoffBlend(true,true,true,true,false,budget,1f))
        assertFalse(p.adaptiveHandoffBlend(true,true,true,true,false,budget-1L,1f))
        assertFalse(p.adaptiveHandoffBlend(true,true,true,true,false,budget,2f))
        assertTrue(p.adaptiveHandoffBlend(true,true,true,true,false,budget,.5f))
        for(scale in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY))
            assertFalse(p.adaptiveHandoffBlend(true,true,true,true,false,1000L,scale))
    }

    @Test fun transientMirrorReadinessDoesNotDisableTheSession() {
        StageConcurrentNativeTrialPolicy.resetForTests()
        for (reason in listOf("fallback:detached_or_software",
            "fallback:host_not_full_display", "fallback:invalid_bounds",
            "fallback:card_outside_source_root", "fallback:no_owned_main_task_mirror",
            "fallback:invalid_native_handles", "fallback:rail_corridor_blocked",
            "fallback:slot_task_leash_not_ready")) {
            assertTrue(StageConcurrentNativeTrialPolicy.transientMirrorOutcome(reason))
        }
        assertFalse(StageConcurrentNativeTrialPolicy.transientMirrorOutcome("fallback:no_sidebar_viewroot_mirror"))
        assertFalse(StageConcurrentNativeTrialPolicy.transientMirrorOutcome("fallback:sidebar_reflection_exception"))
        assertFalse(StageConcurrentNativeTrialPolicy.transientMirrorOutcome("fallback:single_window_root"))
        assertTrue(StageConcurrentNativeTrialPolicy.allowed(true,true,false))
    }
}

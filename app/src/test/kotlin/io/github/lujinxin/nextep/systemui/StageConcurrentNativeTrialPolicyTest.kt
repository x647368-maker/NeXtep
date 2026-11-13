package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageConcurrentNativeTrialPolicyTest {
    @Test fun existingPhysicalWindowAllowsNoNewViewRoot() {
        StageConcurrentNativeTrialPolicy.resetForTests()
        for((w,h) in listOf(1140 to 2616, 2248 to 2480, 2560 to 1600))
            assertTrue(StageConcurrentNativeTrialPolicy.fullScreenHost(0,0,w,h,w,h))
        assertFalse(StageConcurrentNativeTrialPolicy.fullScreenHost(0,85,1080,2300,1080,2400))
        assertFalse(StageConcurrentNativeTrialPolicy.fullScreenHost(0,0,900,2400,1080,2400))
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

    @Test fun slotReadyRequiresPostMoveBuffer() {
        assertFalse(StageConcurrentNativeTrialPolicy.freshFrameAfterTaskAck(null,1L))
        assertFalse(StageConcurrentNativeTrialPolicy.freshFrameAfterTaskAck(42L,42L))
        assertFalse(StageConcurrentNativeTrialPolicy.freshFrameAfterTaskAck(42L,41L))
        assertTrue(StageConcurrentNativeTrialPolicy.freshFrameAfterTaskAck(42L,43L))
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

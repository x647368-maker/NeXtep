package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageTransientTapPolicyTest {
    @Test fun liveGpuPrimaryDoesNotDisableRenderThreadSnapshotFallback() {
        val requested=io.github.x647368maker.atomicworkbench.BuildConfig.ATOMIC_NATIVE_SURFACE_TRIAL
        assertTrue("v88 debug APK must exercise the live GPU path by default",requested)
        assertEquals(requested,StagePrivateDualTaskFlightGate.enabled())
        assertTrue("A failed or unsupported live mirror must retain the bounded RenderThread fallback",
            StageTransientTapPolicy.snapshotFallbackEnabled())
    }
    @Test fun onlyCommittedValidFirstFrameMayStartRealMove() {
        val p=StageTransientTapPolicy
        assertTrue(p.canStartMove(true,true,false))
        assertFalse(p.canStartMove(true,false,false))
        assertFalse(p.canStartMove(false,true,false))
        assertFalse(p.canStartMove(true,true,true))
    }
    @Test fun delayedMoveChecksCurrentStateAndNeverResubmits() {
        val p=StageTransientTapPolicy
        for(current in listOf(false,true)) for(started in listOf(false,true))
            for(valid in listOf(false,true)) {
                var probes=0
                val decision=p.decideMoveRequest(current,started){probes++;valid}
                val expected=when {
                    !current || started -> StageTransientTapPolicy.MoveRequest.IGNORE
                    !valid -> StageTransientTapPolicy.MoveRequest.STALE
                    else -> StageTransientTapPolicy.MoveRequest.START
                }
                assertEquals(expected,decision)
                assertEquals(if(current && !started) 1 else 0,probes)
            }
        // First committed frame was valid, but an intervening fold or task
        // replacement invalidates the later motion callback.
        assertTrue(p.canStartMove(true,true,false))
        assertEquals(StageTransientTapPolicy.MoveRequest.STALE,
            p.decideMoveRequest(true,false){false})
        // Deadline and frame callback may both arrive; only the first may move.
        assertEquals(StageTransientTapPolicy.MoveRequest.START,
            p.decideMoveRequest(true,false){true})
        assertEquals(StageTransientTapPolicy.MoveRequest.IGNORE,
            p.decideMoveRequest(true,true){error("duplicate validity probe")})
    }
    @Test fun workspacePreviewFallbackIsAllowedOnlyBeforeMoveWithCurrentIdentity() {
        val p=StageTransientTapPolicy
        for(current in listOf(false,true)) for(started in listOf(false,true))
            for(valid in listOf(false,true)) {
                assertEquals(current && !started && valid,
                    p.canFallbackToWorkspacePreview(current,started,valid))
            }
    }
    @Test fun flightAndActualOwnershipAckMustBothComplete() {
        val p=StageTransientTapPolicy
        assertFalse(p.canHandoff(false,false))
        assertFalse(p.canHandoff(true,false))
        assertFalse(p.canHandoff(false,true))
        assertTrue(p.canHandoff(true,true))
    }
    @Test fun missingTerminalReceiptMayOnlyRecoverAfterRealMoveAck() {
        val p=StageTransientTapPolicy
        for(started in listOf(false,true)) for(ack in listOf(false,true)) for(missing in listOf(false,true)){
            assertEquals(started && ack && missing,p.canRecoverUnconfirmedTerminal(started,ack,missing))
            // A recovery permission never changes the real terminal gate.
            assertFalse(p.canHandoff(false,ack))
        }
    }
    @Test fun slowSystemAnimationRetainsHardLifetimeAndHandoffReserve() {
        val p=StageTransientTapPolicy
        val remaining=p.MAX_ATTEMPT_MS-p.CAPTURE_BUDGET_MS-p.FIRST_COMMIT_BUDGET_MS
        assertNull(p.nominalFlightMs(0f,remaining))
        assertNull(p.nominalFlightMs(Float.NaN,remaining))
        assertNull(p.nominalFlightMs(Float.POSITIVE_INFINITY,remaining))
        assertNull(p.nominalFlightMs(1f,654))
        for(scale in listOf(.5f,1f,2f,3f,5f,10f)){
            val nominal=checkNotNull(p.nominalFlightMs(scale,remaining))
            assertTrue(nominal>0L && nominal<=p.FLIGHT_MS)
            assertTrue(nominal*scale<=900f)
            assertTrue(nominal*scale+p.FIRST_COMMIT_BUDGET_MS+
                p.HANDOFF_BUDGET_MS+64<=remaining)
            if(scale<=2f)assertEquals(p.FLIGHT_MS,nominal)
        }
        assertEquals(90L,p.nominalFlightMs(10f,p.MAX_ATTEMPT_MS))
        assertEquals(46L,p.nominalFlightMs(10f,1120))
    }
    @Test fun doNotStartSecondCaptureAfterOneShotBudget() {
        assertTrue(StageTransientTapPolicy.canCaptureSecond(159,160))
        assertFalse(StageTransientTapPolicy.canCaptureSecond(160,160))
        assertFalse(StageTransientTapPolicy.canCaptureSecond(161,160))
        assertEquals(300L,StageTransientTapPolicy.FLIGHT_MS)
        assertTrue(StageTransientTapPolicy.CAPTURE_BUDGET_MS+
            StageTransientTapPolicy.FIRST_COMMIT_BUDGET_MS+
            StageTransientTapPolicy.FLIGHT_MS+
            StageTransientTapPolicy.HANDOFF_BUDGET_MS<
            StageTransientTapPolicy.MAX_ATTEMPT_MS)
    }
}

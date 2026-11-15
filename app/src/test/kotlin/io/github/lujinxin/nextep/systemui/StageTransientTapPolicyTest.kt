package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageTransientTapPolicyTest {
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
    @Test fun flightAndActualOwnershipAckMustBothComplete() {
        val p=StageTransientTapPolicy
        assertFalse(p.canHandoff(false,false))
        assertFalse(p.canHandoff(true,false))
        assertFalse(p.canHandoff(false,true))
        assertTrue(p.canHandoff(true,true))
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

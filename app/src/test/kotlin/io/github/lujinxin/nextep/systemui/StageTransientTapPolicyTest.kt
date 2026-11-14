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

package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageTabletSwapLiveGateTest {
    @Test fun twoReadyChecksOfOneBufferDoNotImmediatelyRelease() {
        val gate = StageTabletSwapLiveGate()
        assertFalse(gate.ready(100L, true))
        assertFalse(gate.ready(116L, true))
        assertFalse(gate.ready(132L, true))
        assertTrue(gate.ready(148L, true))
    }

    @Test fun realFrameDisruptionResetsStableDwell() {
        val gate = StageTabletSwapLiveGate()
        assertFalse(gate.ready(100L, true))
        assertFalse(gate.ready(125L, false))
        assertFalse(gate.ready(145L, true))
        assertFalse(gate.ready(180L, true))
        assertTrue(gate.ready(193L, true))
    }

    @Test fun noFrameNeverPassesAndClockRollbackDoesNotFalsePass() {
        val gate = StageTabletSwapLiveGate()
        for (ms in 0L..900L step 30L) assertFalse(gate.ready(ms, false))
        assertFalse(gate.ready(600L, true))
        assertFalse(gate.ready(500L, true))
        assertFalse(gate.ready(547L, true))
        assertTrue(gate.ready(548L, true))
    }
}

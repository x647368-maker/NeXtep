package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Does not invent energy savings; verifies task-preserving lock/output policy only. */
class StageLockPowerPolicyTest {
    @Test fun lockedOrPhysicallyOffNeverWarmsVirtualDisplayOutputs() {
        assertFalse(StageLockPowerPolicy.allowSuspendedOutput(false, false))
        assertFalse(StageLockPowerPolicy.allowSuspendedOutput(false, true))
        assertFalse(StageLockPowerPolicy.allowSuspendedOutput(true, true))
        assertTrue(StageLockPowerPolicy.allowSuspendedOutput(true, false))
    }

    @Test fun screenOffAndLockedStatesThrottleFallback() {
        assertEquals(15_000L, StageLockPowerPolicy.observationIntervalMs(false, false))
        assertEquals(15_000L, StageLockPowerPolicy.observationIntervalMs(false, true))
        assertEquals(4_000L, StageLockPowerPolicy.observationIntervalMs(true, true))
        assertEquals(2_000L, StageLockPowerPolicy.observationIntervalMs(true, false))
        assertEquals(4_000L, StageLockPowerPolicy.resumeRetryMs(true, true))
        assertEquals(1_000L, StageLockPowerPolicy.resumeRetryMs(false, false))
        assertEquals(100L, StageLockPowerPolicy.resumeRetryMs(true, false))
    }
}

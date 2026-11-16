package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test

class StageSlotLifecycleGateTest {
    @Test fun oneOwnerExcludesEveryOtherMutation() {
        val gate = StageSlotLifecycleGate()
        val owner = checkNotNull(gate.acquire())
        assertTrue(gate.allowsMutation(owner))
        assertFalse(gate.allowsMutation())
        assertNull(gate.acquire())
        assertFalse(gate.release(owner))
        assertTrue(gate.allowsMutation())
        val next = checkNotNull(gate.acquire())
        assertFalse(gate.release(next))
    }

    @Test fun exitRequestBlocksNewWorkButAllowsCurrentOwnerToRollback() {
        val gate = StageSlotLifecycleGate()
        val owner = checkNotNull(gate.acquire())
        assertTrue(gate.requestExitIfOwned())
        assertTrue(gate.allowsMutation(owner))
        assertFalse(gate.allowsMutation())
        assertNull(gate.acquire())
        assertTrue(gate.release(owner))
        assertTrue(gate.isExitPending)
        assertFalse(gate.allowsMutation())
        assertNull(gate.acquire())
        gate.beginExit()
        gate.finishExit()
        assertFalse(gate.isExitPending)
        assertTrue(gate.allowsMutation())
    }

    @Test fun ordinaryCloseReleasesWithoutSchedulingExit() {
        val gate = StageSlotLifecycleGate()
        val owner = checkNotNull(gate.acquire())
        assertFalse(gate.release(owner))
        assertFalse(gate.isExitPending)
        assertTrue(gate.allowsMutation())
        assertNotNull(gate.acquire())
    }

    @Test fun staleAndDuplicateReleaseCannotStealOwnership() {
        val gate = StageSlotLifecycleGate()
        val owner = checkNotNull(gate.acquire())
        val other = StageSlotLifecycleGate.Lease(999L)
        assertTrue(runCatching { gate.release(other) }.isFailure)
        assertTrue(gate.allowsMutation(owner))
        assertTrue(runCatching { gate.release(owner); gate.release(owner) }.isFailure)
    }
}

package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test

class StageSlotLifecycleGateTest {
    @Test fun reentrantExitWaitsForOutermostReplaceLease() {
        val gate=StageSlotLifecycleGate()
        assertTrue(gate.beginMutation()) // replace
        assertTrue(gate.beginMutation()) // nested close
        assertTrue(gate.deferExitIfMutating())
        assertTrue(gate.allowsMutation()) // outer replace must finish or rollback
        assertFalse(gate.endMutation())
        assertTrue(gate.endMutation()) // exactly one exit callback is scheduled
        assertFalse(gate.allowsMutation())
        assertFalse(gate.beginMutation())
        gate.beginExit()
        gate.finishExit()
        assertTrue(gate.allowsMutation())
    }

    @Test fun failedCloseStillReleasesLeaseAndSchedulesExitOnce() {
        val gate=StageSlotLifecycleGate()
        assertTrue(gate.beginMutation())
        assertTrue(gate.deferExitIfMutating())
        assertTrue(gate.endMutation())
        assertFalse(gate.endMutationIfUnbalancedForTest())
        assertFalse(gate.allowsMutation())
    }

    @Test fun ordinaryCloseDoesNotCreateExitOrBlockNextMutation() {
        val gate=StageSlotLifecycleGate()
        assertTrue(gate.beginMutation())
        assertFalse(gate.endMutation())
        assertTrue(gate.allowsMutation())
        assertTrue(gate.beginMutation())
        assertFalse(gate.endMutation())
    }

    private fun StageSlotLifecycleGate.endMutationIfUnbalancedForTest(): Boolean =
        runCatching { endMutation() }.isSuccess
}

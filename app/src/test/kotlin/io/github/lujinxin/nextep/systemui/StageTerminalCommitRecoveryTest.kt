package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageTerminalCommitRecoveryTest {
    @Test fun missingCommitMayRearmExactlyOnceWithoutInventingProof(){
        val gate=StageTerminalCommitRecovery()
        assertFalse(gate.accept(false))
        assertTrue(gate.retry())
        assertFalse(gate.retry())
        assertFalse(gate.accept(false))
        assertTrue(gate.accept(true))
        assertFalse(gate.accept(true))
        assertFalse(gate.retry())
    }
    @Test fun normalCommitCancelsRetry(){
        val gate=StageTerminalCommitRecovery()
        assertTrue(gate.accept(true))
        assertFalse(gate.retry())
        assertFalse(gate.accept(true))
    }
    @Test fun staleCallbackAfterCancelCannotReveal(){
        val gate=StageTerminalCommitRecovery()
        gate.close()
        assertFalse(gate.accept(true))
        assertFalse(gate.retry())
    }
    @Test fun retryFitsExistingTerminalBudget(){
        assertTrue(StageTerminalCommitRecovery.RETRY_MS>0)
        assertTrue(StageTerminalCommitRecovery.RETRY_MS<StageTransientTapPolicy.FIRST_COMMIT_BUDGET_MS)
    }
}

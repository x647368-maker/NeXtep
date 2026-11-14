package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageFirstCommitRecoveryTest {
    @Test fun firstAndTerminalCommitHaveIndependentOneShotGates(){
        val first=StageTerminalCommitRecovery()
        val terminal=StageTerminalCommitRecovery()
        assertFalse(first.accept(false))
        assertTrue(first.retry())
        assertFalse(first.retry())
        assertTrue(first.accept(true))
        first.close()
        assertFalse(first.accept(true))
        assertTrue(terminal.retry())
        assertFalse(terminal.accept(false))
        assertTrue(terminal.accept(true))
    }
    @Test fun cancellationOrFallbackCannotAcceptLateFirstCommit(){
        val first=StageTerminalCommitRecovery()
        first.close()
        assertFalse(first.retry())
        assertFalse(first.accept(true))
    }
    @Test fun retryIsNotPermissionToMove(){
        val first=StageTerminalCommitRecovery()
        assertTrue(first.retry())
        assertFalse(first.accept(false))
        assertFalse(StageTransientTapPolicy.canStartMove(true,false,false))
        assertTrue(first.accept(true))
        assertTrue(StageTransientTapPolicy.canStartMove(true,true,false))
        assertFalse(StageTransientTapPolicy.canStartMove(true,true,true))
        assertFalse(StageTransientTapPolicy.canStartMove(false,true,false))
    }
}

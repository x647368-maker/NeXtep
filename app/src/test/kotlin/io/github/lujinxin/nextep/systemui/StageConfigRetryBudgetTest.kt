package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageConfigRetryBudgetTest {
    @Test fun providerReadFailureIsBounded() {
        assertTrue(StageConfigRetryBudget.shouldRetry(1))
        assertTrue(StageConfigRetryBudget.shouldRetry(2))
        assertTrue(StageConfigRetryBudget.shouldRetry(3))
        assertFalse(StageConfigRetryBudget.shouldRetry(4))
        assertFalse(StageConfigRetryBudget.shouldRetry(100_000))
        assertFalse(StageConfigRetryBudget.shouldRetry(0))
    }
    @Test fun aFreshExplicitAttemptMayRetryAgain() {
        // UI/broadcast resets the counter before each new explicit attempt.
        assertFalse(StageConfigRetryBudget.shouldRetry(10))
        assertTrue(StageConfigRetryBudget.shouldRetry(1))
    }
}

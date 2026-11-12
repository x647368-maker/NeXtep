package io.github.x647368maker.atomicworkbench.systemui

/** Persistent provider failures must not keep waking SystemUI on a timer.
 * Unlock/config-change triggers reset the budget and are never suppressed. */
internal object StageConfigRetryBudget {
    private const val MAX_SCHEDULED_RETRIES = 3
    fun shouldRetry(failedReadCount: Int): Boolean =
        failedReadCount in 1..MAX_SCHEDULED_RETRIES
}

package io.github.x647368maker.atomicworkbench.systemui

/** Per-tap only. No background capture, refresh or repeating idle timer. */
internal object StageTransientTapPolicy {
    fun privateTrialEnabled():Boolean=true
    const val CAPTURE_BUDGET_MS=160L
    const val FIRST_COMMIT_BUDGET_MS=110L
    const val FLIGHT_MS=300L
    const val HANDOFF_BUDGET_MS=480L
    const val MAX_ATTEMPT_MS=1800L
    fun canCaptureSecond(now:Long,deadline:Long):Boolean=now<deadline
    fun canStartMove(valid:Boolean,committed:Boolean,started:Boolean):Boolean=
        valid && committed && !started
    fun canHandoff(flightDone:Boolean,moveAcknowledged:Boolean):Boolean=flightDone && moveAcknowledged
}

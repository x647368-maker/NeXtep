package io.github.x647368maker.atomicworkbench.systemui

/**
 * Strictly temporary PR62 branch opt-in for REAL on-device validation.
 * PR60's public safety gate stays OFF and main/Release are untouched.
 * Do not cherry-pick this file or turn on ordinary user builds until
 * ColorOS/Find N5/Y700 complete visual, power and touch regression checks.
 */
internal object StagePrivateDualTaskFlightGate {
    private const val PRIVATE_TRIAL_ONLY = true
    fun enabled(): Boolean = PRIVATE_TRIAL_ONLY
}

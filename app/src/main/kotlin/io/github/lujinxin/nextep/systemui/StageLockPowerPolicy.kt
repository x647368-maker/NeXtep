package io.github.x647368maker.atomicworkbench.systemui

/** Only the physical default display + keyguard may authorize preview output.
 * A virtual display can keep PowerManager.isInteractive() true after screen-off,
 * so the caller must provide its physical-display-aware readiness result.
 * Pure policy: changes neither tasks nor virtual-display ownership.
 */
internal object StageLockPowerPolicy {
    private const val SCREEN_OFF_FALLBACK_MS = 15_000L
    private const val LOCKED_FALLBACK_MS = 4_000L
    private const val ACTIVE_FALLBACK_MS = 2_000L
    private const val SCREEN_WAKING_RETRY_MS = 1_000L

    fun allowSuspendedOutput(physicalScreenReady: Boolean, keyguardLocked: Boolean): Boolean =
        physicalScreenReady && !keyguardLocked

    fun observationIntervalMs(physicalScreenReady: Boolean, keyguardLocked: Boolean): Long = when {
        !physicalScreenReady -> SCREEN_OFF_FALLBACK_MS
        keyguardLocked -> LOCKED_FALLBACK_MS
        else -> ACTIVE_FALLBACK_MS
    }

    fun resumeRetryMs(physicalScreenReady: Boolean, keyguardLocked: Boolean): Long = when {
        keyguardLocked -> LOCKED_FALLBACK_MS
        !physicalScreenReady -> SCREEN_WAKING_RETRY_MS
        else -> 100L
    }
}

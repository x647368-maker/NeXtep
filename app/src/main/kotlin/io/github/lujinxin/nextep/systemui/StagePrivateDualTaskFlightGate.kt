package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.BuildConfig

/**
 * v88 development builds enable the live dual-task Surface flight as the
 * primary tap animation. Gradle keeps the release BuildConfig flag false.
 * Runtime visibility, animator, power, mirror, first-commit and preparation
 * budgets remain authoritative; snapshot RenderThread motion is still fallback.
 * The legacy full-screen native gate remains independently disabled.
 */
internal object StagePrivateDualTaskFlightGate {
    fun enabled(): Boolean = BuildConfig.ATOMIC_NATIVE_SURFACE_TRIAL
}

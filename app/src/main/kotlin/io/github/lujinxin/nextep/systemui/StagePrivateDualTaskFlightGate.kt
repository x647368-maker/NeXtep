package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.BuildConfig

/**
 * Private, explicit debug build A/B. Ordinary builds keep the existing
 * transient preview path; release variants cannot enable this experiment.
 * The legacy full-screen native gate remains independently disabled.
 */
internal object StagePrivateDualTaskFlightGate {
    fun enabled(): Boolean = BuildConfig.ATOMIC_NATIVE_SURFACE_TRIAL
}

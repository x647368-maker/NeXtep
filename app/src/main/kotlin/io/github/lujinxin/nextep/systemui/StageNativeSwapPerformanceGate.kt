package io.github.x647368maker.atomicworkbench.systemui

/**
 * Safety gate introduced after 3731.mp4 real-device P0 stutter.
 *
 * PR50's two SurfaceFlinger mirrors are experimental and intentionally OFF
 * in user builds. Even when its GPU transactions succeed, that path starts
 * the REAL task move at 94% of a 280-360ms visual flight, then waits for live
 * buffers. It therefore cannot meet the existing no-slowdown requirement.
 *
 * This gate does not alter the existing real task, input, VirtualDisplay or
 * regular preview exchange path. A future, separately reviewed experiment
 * must explicitly change this constant after measured ColorOS validation.
 */
internal object StageNativeSwapPerformanceGate {
    private const val EXPERIMENTAL_NATIVE_SWAP_OPT_IN = false
    fun enabledByDefault(): Boolean = EXPERIMENTAL_NATIVE_SWAP_OPT_IN
}

package io.github.x647368maker.atomicworkbench.systemui

/**
 * Tap-to-exchange motion is a workbench feature on *every* supported display
 * shape (phone portrait/landscape, fold closed/open, tablet portrait/landscape).
 * Use actual current window bounds rather than Build.MODEL / ROM identity.
 *
 * A tap only falls back to the proven stationary exchange if its real source
 * and destination geometry is unavailable or degenerate; no device blacklist.
 */
internal object StageCrossDeviceSwapMotionPolicy {
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        val valid get() = left.isFinite() && top.isFinite() &&
            right.isFinite() && bottom.isFinite() &&
            width >= 8f && height >= 8f
    }

    fun canAnimateTap(source: Bounds, destination: Bounds, hasSourceFrame: Boolean): Boolean =
        hasSourceFrame && source.valid && destination.valid
}

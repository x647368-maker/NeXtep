package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.config.WorkspaceConfigContract

/** Presentation gate only: the real move starts after the first committed frame. */
internal object StageParallelTapMotionPolicy {
    // The former 180ms cap hid most of the visual scale-up on real 60Hz
    // phones. Use a deliberate, bounded ~300ms card morph like the provided
    // stage-workbench reference. Only animated taps take this path.
    private const val MIN_VISIBLE_MS = 280L
    private const val MAX_VISUAL_MS = 360L
    fun concurrent(uniformTapMorph: Boolean, returningHome: Boolean, enabled: Boolean,
                   asynchronousTaskMove: Boolean): Boolean =
        uniformTapMorph && !returningHome && enabled && asynchronousTaskMove
    fun durationMs(configuredMs: Int): Long =
        WorkspaceConfigContract.normalizeSwapAnimationDurationMs(configuredMs).toLong()
            .coerceIn(MIN_VISIBLE_MS, MAX_VISUAL_MS)
    fun canHandoff(animationComplete: Boolean, realMoveComplete: Boolean): Boolean =
        animationComplete && realMoveComplete
}

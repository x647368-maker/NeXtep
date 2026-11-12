package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.config.WorkspaceConfigContract

/** Presentation gate only: the real move starts after the first committed frame. */
internal object StageParallelTapMotionPolicy {
    private const val MAX_VISUAL_MS = 180L
    fun concurrent(uniformTapMorph: Boolean, returningHome: Boolean, enabled: Boolean,
                   asynchronousTaskMove: Boolean): Boolean =
        uniformTapMorph && !returningHome && enabled && asynchronousTaskMove
    fun durationMs(configuredMs: Int): Long =
        minOf(MAX_VISUAL_MS, WorkspaceConfigContract.normalizeSwapAnimationDurationMs(configuredMs).toLong())
    fun canHandoff(animationComplete: Boolean, realMoveComplete: Boolean): Boolean =
        animationComplete && realMoveComplete
}

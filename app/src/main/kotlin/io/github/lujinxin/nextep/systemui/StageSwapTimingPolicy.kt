package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.config.WorkspaceConfigContract

/**
 * Presentation timing only. Every actual task exchange remains protected by the
 * existing frame-commit callback, transaction guard, cancellation and handoff.
 */
internal object StageSwapTimingPolicy {
    private const val STATIC_TAP_PREVIEW_MS = 1L

    fun previewDurationMs(configuredMs: Int, freezeGeometry: Boolean, returningHome: Boolean): Long {
        // No motion exists for frozen tap previews; use the next UI frame.
        // Do not change HOME wallpaper transitions or drag-and-drop motion.
        if (freezeGeometry && !returningHome) return STATIC_TAP_PREVIEW_MS
        return WorkspaceConfigContract.normalizeSwapAnimationDurationMs(configuredMs).toLong()
    }
}

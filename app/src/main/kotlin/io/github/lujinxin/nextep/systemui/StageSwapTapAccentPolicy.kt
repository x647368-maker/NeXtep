package io.github.x647368maker.atomicworkbench.systemui

/** One-shot optical hint during an already-existing preview; task timing stays at 1ms. */
internal object StageSwapTapAccentPolicy {
    const val DURATION_MS = 140L
    fun shouldAnimate(frozen: Boolean, returningHome: Boolean, enabled: Boolean): Boolean =
        frozen && !returningHome && enabled
    fun opacity(progress: Float): Int =
        ((1f - kotlin.math.abs(2f * progress.coerceIn(0f, 1f) - 1f)) * 96f)
            .toInt().coerceIn(0, 96)
}

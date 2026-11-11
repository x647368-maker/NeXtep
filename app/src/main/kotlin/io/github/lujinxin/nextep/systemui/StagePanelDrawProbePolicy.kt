package io.github.x647368maker.atomicworkbench.systemui

/**
 * Limit only fallback PRE-DRAW shade probing. Layout, focus and attach
 * callbacks always trigger immediate checks; this never throttles input,
 * real cards, overlays or system panel rendering.
 */
internal object StagePanelDrawProbePolicy {
    const val MIN_INTERVAL_MS = 100L

    fun shouldProbe(nowMs: Long, previousMs: Long): Boolean =
        previousMs == Long.MIN_VALUE ||
            nowMs < previousMs ||
            nowMs - previousMs >= MIN_INTERVAL_MS
}

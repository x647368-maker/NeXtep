package io.github.x647368maker.atomicworkbench.systemui

/**
 * 2026-11-13 real-device P0: eliminate serial bitmap-capture/animation delay
 * BEFORE the phased coordinator receives its move callback. Do not touch
 * drag, HOME, slot-to-slot or legacy synchronous paths.
 */
internal object StageSwapTapLatencyPolicy {
    fun useImmediatePhasedPath(
        isTap: Boolean,
        asynchronousTaskMove: Boolean,
        occupiedSlot: Boolean,
    ): Boolean = isTap && asynchronousTaskMove && occupiedSlot
}

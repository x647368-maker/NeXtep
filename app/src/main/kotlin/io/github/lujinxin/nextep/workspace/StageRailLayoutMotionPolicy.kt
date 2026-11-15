package io.github.x647368maker.atomicworkbench.workspace

/** One short motion of an already attached rail, using physical screen axes.
 * Geometry, ownership and task buffers are never changed by this policy. */
internal object StageRailLayoutMotionPolicy {
    data class Entry(val dx: Float, val dy: Float, val durationMs: Long)

    fun entry(sameCanvas: Boolean, changedLayout: Boolean, horizontal: Boolean,
              positiveOutward: Boolean, distance: Float, durationMs: Long): Entry? {
        if (!sameCanvas || !changedLayout || !distance.isFinite() || distance <= 0f || durationMs <= 0L) return null
        val offset = if (positiveOutward) distance else -distance
        return if (horizontal) Entry(0f, offset, durationMs) else Entry(offset, 0f, durationMs)
    }
}

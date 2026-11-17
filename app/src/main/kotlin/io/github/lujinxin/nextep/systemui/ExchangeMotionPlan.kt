package io.github.x647368maker.atomicworkbench.systemui

import android.animation.TimeInterpolator
import android.view.animation.PathInterpolator

/**
 * v89 P0-2a: one bounded, per-exchange geometry clock for both moving previews.
 *
 * This wraps the route already admitted by StageSnapshotMotion. It MUST NOT
 * select a new route, move a real task, acquire a Surface or retain any bitmap.
 * Actual move/ready/terminal ownership stays with the existing coordinator.
 *
 * Certified shared routes embed easing inside plan.at(): their EXTERNAL clock
 * is therefore linear. Ordinary paired routes preserve their existing cubic
 * timing exactly. Construct once per preview, never inside a frame callback.
 */
internal class ExchangeMotionPlan private constructor(
    val pair: StageSnapshotMotion.PairFlight,
    val clockMode: ClockMode,
    private val clock: TimeInterpolator,
) {
    enum class ClockMode { CERTIFIED_INTERNAL, PAIRED_CUBIC }

    /** Both incoming and outgoing MUST sample the same progress value. */
    fun geometryProgress(raw: Float): Float {
        if (!raw.isFinite()) return 0f
        val clamped = raw.coerceIn(0f, 1f)
        if (clamped == 0f || clamped == 1f) return clamped
        val eased = clock.getInterpolation(clamped)
        return if (eased.isFinite()) eased.coerceIn(0f, 1f) else clamped
    }

    fun incomingAt(raw: Float): StageSnapshotMotion.Pose =
        pair.up.pose(geometryProgress(raw))

    fun outgoingAt(raw: Float): StageSnapshotMotion.Pose =
        pair.down.pose(geometryProgress(raw))

    /** Pure geometry sampling only, not evidence that live task pixels presented. */
    fun atGeometry(progress: Float): Pair<StageSnapshotMotion.Pose, StageSnapshotMotion.Pose> =
        pair.up.pose(progress) to pair.down.pose(progress)

    companion object {
        fun fromExistingPair(pair: StageSnapshotMotion.PairFlight): ExchangeMotionPlan? {
            val up = pair.up
            val down = pair.down
            if (up.width < 2 || up.height < 2 || down.width < 2 || down.height < 2 ||
                !up.from.valid() || !up.to.valid() || !down.from.valid() || !down.to.valid()
            ) return null
            val certified = pair.usesSharedClock
            val timing = if (certified) TimeInterpolator { it }
                else PathInterpolator(.32f, .08f, .36f, 1f)
            return ExchangeMotionPlan(pair,
                if (certified) ClockMode.CERTIFIED_INTERNAL else ClockMode.PAIRED_CUBIC,
                timing)
        }
    }
}

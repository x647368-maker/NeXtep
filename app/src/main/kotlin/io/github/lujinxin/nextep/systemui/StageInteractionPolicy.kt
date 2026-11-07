package io.github.x647368maker.atomicworkbench.systemui

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.roundToInt

/** Task identities and slot indexes remain unchanged by presentation or recent-use depth. */
internal object StageStackPolicy {
    data class Pose(val scale: Float, val x: Float, val y: Float, val tilt: Float, val z: Float, val alpha: Float)

    fun touch(order: List<Int>, slot: Int): List<Int> {
        require(order.distinct().size == order.size && slot in order)
        return listOf(slot) + order.filter { it != slot }
    }

    fun poses(heights: List<Int>, tops: List<Int>, density: Float, right: Boolean, order: List<Int>): List<Pose> {
        require(heights.isNotEmpty() && heights.size == tops.size && heights.all { it > 0 })
        require(density.isFinite() && density > 0 && order.sorted() == heights.indices.toList())
        val pitch = heights.min() * 0.86f
        val totalHeight = tops.last() + heights.last()
        val stackHeight = pitch * (heights.size - 1) + heights.last()
        val padding = (totalHeight - stackHeight) / 2f
        val direction = if (right) 1f else -1f
        return heights.indices.map { index ->
            val depth = order.indexOf(index)
            Pose(0.98f - depth * 0.025f, direction * depth * 2f * density,
                padding + index * pitch - tops[index], -direction * (12f + depth * 3f),
                (4 + (heights.size - depth) * 4) * density, 1f - depth * 0.045f)
        }
    }
}

/** Pure transition geometry. Real task movement is still owned by SlotTaskCoordinator. */
internal object StageExchangeMotion {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }
    data class Frame(val box: Box)

    /** TextureView previews are already rotated by the rail. Reorder the rendered
     * corners into that bitmap's orientation, avoiding a second landscape turn. */
    fun orientQuad(points: FloatArray, rotation: Float): FloatArray {
        require(points.size == 8 && points.all { it.isFinite() } && rotation.isFinite())
        val turns = ((rotation / 90f).roundToInt() % 4 + 4) % 4
        return FloatArray(8) { i -> points[((i / 2 - turns + 4) % 4) * 2 + i % 2] }
    }

    fun frame(from: Box, to: Box, progress: Float): Frame {
        require(progress.isFinite() && from.width > 0 && from.height > 0 && to.width > 0 && to.height > 0)
        val p = progress.coerceIn(0f, 1f)
        fun lerp(a: Float, b: Float) = a + (b - a) * p
        val lift = if (p == 0f || p == 1f) 0f else
            sin(Math.PI * p).toFloat() * minOf(minOf(from.height, to.height) * 0.12f,
                abs((from.left + from.right - to.left - to.right) / 2f) * 0.08f)
        return Frame(Box(lerp(from.left, to.left), lerp(from.top, to.top) - lift,
            lerp(from.right, to.right), lerp(from.bottom, to.bottom) - lift))
    }
}

internal data class StageTaskIdentity(val taskId: Int, val packageName: String, val userId: Int)

internal object StageGroupVerification {
    /** An ID is preferred only while that same task/user still exists; fallback launches
     * may have a new ID, but another profile's identical package never satisfies restore. */
    fun matches(expected: StageTaskIdentity, actual: StageTaskIdentity?, originalStillExists: Boolean): Boolean =
        actual != null && expected.packageName == actual.packageName && expected.userId == actual.userId &&
            (!originalStillExists || expected.taskId == actual.taskId)
}

internal object StageGroupSwitchPolicy {
    fun shouldSaveCurrent(activeId: String?, targetId: String): Boolean = activeId != null && activeId != targetId
    fun isComplete(slots: List<Boolean>, main: Boolean): Boolean = slots.size == 4 && slots.all { it } && main
}

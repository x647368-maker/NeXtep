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
        val totalHeight = tops.last() + heights.last() + tops.first()
        val stackHeight = pitch * (heights.size - 1) + heights.last()
        val padding = (totalHeight - stackHeight) / 2f
        val direction = if (right) 1f else -1f
        return heights.indices.map { index ->
            val depth = order.indexOf(index)
            Pose(0.94f - depth * 0.018f, direction * depth * 2f * density,
                padding + index * pitch - tops[index], -direction * (8f + depth * 2f),
                (4 + (heights.size - depth) * 4) * density, 1f - depth * 0.02f)
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
    fun canReorder(current: List<StageTaskIdentity?>, target: List<StageTaskIdentity?>): Boolean {
        if (current.size != 5 || target.size != 5 || current[0] == null || target[0] == null) return false
        val existing = current.filterNotNull()
        val requested = target.filterNotNull()
        return existing.distinct().size == existing.size && requested.distinct().size == requested.size &&
            existing.toSet() == requested.toSet()
    }
    data class GroupSwap(val from: Int, val to: Int)

    fun planReorder(current: List<StageTaskIdentity?>, target: List<StageTaskIdentity?>): List<GroupSwap>? {
        if (!canReorder(current, target)) return null
        val state = current.toMutableList()
        val moves = mutableListOf<GroupSwap>()
        fun swap(from: Int, to: Int) {
            check(state[from] != null)
            val value = state[from]; state[from] = state[to]; state[to] = value
            moves.add(GroupSwap(from, to))
        }
        if (state[0] != target[0]) swap(state.indexOf(target[0]), 0)
        for (i in 1 until state.size) {
            if (state[i] == target[i]) continue
            if (target[i] != null) {
                val source = state.indexOf(target[i])
                check(source > i)
                swap(source, i)
            } else {
                val empty = ((i + 1) until state.size).first { state[it] == null }
                swap(i, empty)
            }
        }
        check(state == target)
        return moves
    }

    fun shouldSaveCurrent(activeId: String?, targetId: String): Boolean = activeId != null && activeId != targetId
    fun isComplete(slots: List<Boolean>, main: Boolean): Boolean = slots.size == 4 && slots.all { it } && main
}

/** Visibility never changes task/display ownership. Empty capacity is not a window. */
internal object StageActiveWindowPolicy {
    fun visible(occupied: List<Boolean>): List<Int> {
        require(occupied.size == 4)
        return occupied.indices.filter { occupied[it] }
    }
    fun positions(occupied: List<Boolean>, heights: List<Int>, tops: List<Int>, panelHeight: Int,
        order: List<Int>, density: Float, right: Boolean): Map<Int, StageStackPolicy.Pose> {
        val shown = order.filter { occupied[it] }
        require(heights.size == 4 && tops.size == 4 && heights.all { it > 0 } && panelHeight > 0)
        require(order.sorted() == (0..3).toList() && density > 0)
        if (shown.isEmpty()) return emptyMap()
        val gap = 8f * density
        val reserve = 56f * density
        val scale = minOf(.96f, (panelHeight - reserve - gap * (shown.size + 1)) /
            shown.sumOf { heights[it] }.toFloat()).coerceAtLeast(.1f)
        val stackHeight = shown.sumOf { heights[it] }.toFloat() * scale + gap * (shown.size - 1)
        var y = ((panelHeight - reserve - stackHeight) / 2f).coerceAtLeast(gap)
        return shown.associateWith { slot ->
            val pose = StageStackPolicy.Pose(scale, 0f,
                y - tops[slot] - heights[slot] * (1f - scale) / 2f,
                0f, 2f * density, 1f)
            y += heights[slot] * scale + gap
            pose
        }

    }
}

internal object StageTopRowPolicy {
    fun positions(occupied: List<Boolean>, widths: List<Int>, heights: List<Int>, lefts: List<Int>, tops: List<Int>,
        panelWidth: Int, panelHeight: Int, density: Float, order: List<Int> = listOf(0, 1, 2, 3)): Map<Int, StageStackPolicy.Pose> {
        require(order.sorted() == (0..3).toList())
        val shown = order.filter { occupied[it] }
        require(widths.size == 4 && heights.size == 4 && lefts.size == 4 && tops.size == 4)
        if (shown.isEmpty()) return emptyMap()
        val gap = 8f * density
        val total = shown.sumOf { widths[it] } + gap * (shown.size - 1)
        var x = ((panelWidth - total) / 2f).coerceAtLeast(0f)
        return shown.associateWith { index ->
            val pose = StageStackPolicy.Pose(1f, x - lefts[index], 8f * density - tops[index], 0f, 4f * density, 1f)
            x += widths[index] + gap
            pose
        }
    }
}

internal class StageDragHoverPolicy(private val dwellMillis: Long = 550) {
    private var slot: Int? = null
    private var task: Int? = null
    private var since = 0L
    private var consumed = false
    fun reset() { slot = null; task = null; consumed = false; since = 0L }
    fun update(nextSlot: Int?, nextTask: Int?, time: Long): Int? {
        if (nextSlot == null || nextTask == null) { reset(); return null }
        if (nextSlot != slot || nextTask != task) { slot = nextSlot; task = nextTask; since = time; consumed = false }
        if (consumed || time - since < dwellMillis) return null
        consumed = true
        return nextSlot
    }
}

internal object StageVisualOrder {
    fun swap(order: List<Int>, from: Int, to: Int): List<Int> {
        require(order.sorted() == (0..3).toList() && from in 0..3 && to in 0..3)
        val result = order.toMutableList(); val a = order.indexOf(from); val b = order.indexOf(to)
        result[a] = to; result[b] = from; return result
    }
}

package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.display.SlotGeometry
import kotlin.math.roundToInt

/** Presentation only: match the existing producer, never resize its display. */
internal object StageLiveCardLayout {
    fun bounds(profile: WorkspaceLayoutProfile, canvases: List<SlotGeometry?>,
        occupied: List<Boolean>, order: List<Int>): List<WorkspaceLayoutProfile.Slot> {
        require(canvases.size == 4 && occupied.size == 4 && order.sorted() == (0..3).toList())
        val g = profile.geometry
        val density = profile.densityDpi / 160f
        val gap = (8 * density).roundToInt().coerceAtLeast(1)
        val shown = order.filter { occupied[it] }
        val base = profile.slots
        if (shown.isEmpty()) return base
        StageTabletRailVisualPolicy.compactCards(profile, canvases, occupied, order)?.let { return it }
        if (g.isNativeLandscapeRail) {
            // Vertical stack of wide *live* cards on the physical left/right
            // Y700 rail. Use each producer's actual retained canvas, not a
            // forced rotated screenshot or the old portrait-only base slot.
            // Portrait-locked apps remain portrait and are centered without
            // stretching or incorrect touch projection.
            val reserve = ((if (shown.size < 4) 56 else 16) * density).roundToInt()
            val availableHeight = (g.sidebarLogicalHeight - reserve - (shown.size + 1) * gap)
                .coerceAtLeast(shown.size)
            val maxCardHeight = (availableHeight / shown.size).coerceAtLeast(1)
            val maxCardWidth = (g.sidebarPhysicalWidth - 2 * gap).coerceAtLeast(1)
            val fitted = shown.associateWith { i ->
                val canvas = canvases[i] ?: profile.canvas
                val scale = minOf(maxCardWidth.toFloat() / canvas.width,
                    maxCardHeight.toFloat() / canvas.height)
                WorkspaceLayoutProfile.Slot(
                    (canvas.width * scale).roundToInt().coerceIn(1, maxCardWidth),
                    (canvas.height * scale).roundToInt().coerceIn(1, maxCardHeight),
                    0,
                )
            }
            val stackHeight = shown.sumOf { fitted.getValue(it).height } +
                (shown.size - 1) * gap
            var y = ((g.sidebarLogicalHeight - reserve - stackHeight) / 2).coerceAtLeast(gap)
            return base.toMutableList().also { cards ->
                shown.forEach { index ->
                    cards[index] = fitted.getValue(index).copy(top = y)
                    y += cards[index].height + gap
                }
            }
        }
        val fitted = base.mapIndexed { i, box ->
            val canvas = canvases[i] ?: profile.canvas
            val maxWidth = if (g.railAtTop) box.width else (g.sidebarWidth - 2 * gap).coerceAtLeast(1)
            val maxHeight = if (g.railAtTop) box.height else g.sidebarLogicalHeight
            val scale = minOf(maxWidth.toFloat() / canvas.width, maxHeight.toFloat() / canvas.height)
            WorkspaceLayoutProfile.Slot((canvas.width * scale).roundToInt().coerceAtLeast(1),
                (canvas.height * scale).roundToInt().coerceAtLeast(1), 0)
        }
        if (g.railAtTop) {
            // Top sizing remains width-led; frozen portrait producers keep their full aspect ratio.
            var x = ((g.sidebarPhysicalWidth - shown.sumOf { fitted[it].width } - gap * (shown.size - 1)) / 2).coerceAtLeast(0)
            return fitted.mapIndexed { i, box -> box.copy(left = 0, top = gap) }.toMutableList().also { result ->
                shown.forEach { i -> result[i] = fitted[i].copy(left = x, top = gap); x += fitted[i].width + gap }
            }
        }
        val reserve = (56 * density).roundToInt()
        val available = (g.sidebarLogicalHeight - reserve - 2 * gap).coerceAtLeast(1)
        val scale = minOf(1f, (available - gap * (shown.size - 1)).coerceAtLeast(1).toFloat() /
            shown.sumOf { fitted[it].height })
        val result = fitted.map { box -> box.copy(width = (box.width * scale).toInt().coerceAtLeast(1),
            height = (box.height * scale).toInt().coerceAtLeast(1)) }.toMutableList()
        val stack = shown.sumOf { result[it].height } + gap * (shown.size - 1)
        var y = ((g.sidebarLogicalHeight - reserve - stack) / 2).coerceAtLeast(gap)
        shown.forEach { i -> result[i] = result[i].copy(top = y); y += result[i].height + gap }
        return result
    }
}

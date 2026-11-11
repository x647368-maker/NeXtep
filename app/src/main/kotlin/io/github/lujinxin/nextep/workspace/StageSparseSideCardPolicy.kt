package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.display.SlotGeometry
import kotlin.math.roundToInt

/**
 * Display-only sparse-rail fitting on tall compact phones.
 * Main task geometry, VirtualDisplay producer size and button anchors stay
 * unchanged; expanded near-square foldables retain their frozen layout.
 */
internal object StageSparseSideCardPolicy {
    fun enabled(g: WorkspaceGeometry, visibleCount: Int): Boolean =
        visibleCount in 1..3 &&
        !g.railAtTop && !g.isLandscape &&
        !StageDisplayPolicy.wideTablet(g.screenWidth, g.screenHeight) &&
        !StageDisplayPolicy.unfoldedNearSquare(g.screenWidth, g.screenHeight)

    fun fit(
        profile: WorkspaceLayoutProfile,
        canvases: List<SlotGeometry?>,
        occupied: List<Boolean>,
        order: List<Int>,
    ): List<WorkspaceLayoutProfile.Slot>? {
        val g = profile.geometry
        val shown = order.filter { occupied[it] }
        if (!enabled(g, shown.size)) return null
        val density = profile.densityDpi / 160f
        // Four-card mode retains its original 8dp gutters. Sparse portrait
        // mode can use 4dp/5dp gutters without changing the rail dimensions.
        val inset = ((if (shown.size <= 2) 4f else 5f) * density)
            .roundToInt().coerceAtLeast(1)
        val gap = (8f * density).roundToInt().coerceAtLeast(1)
        // The existing bottom add button is 44dp tall with a 12dp margin.
        // Never allow a live card to invade this stable control area.
        val footer = (56f * density).roundToInt().coerceAtLeast(1)
        val available = g.sidebarLogicalHeight - footer - (shown.size + 1) * gap
        val maxWidth = g.sidebarPhysicalWidth - 2 * inset
        if (available < shown.size || maxWidth <= 0) return null
        val maxHeight = (available / shown.size).coerceAtLeast(1)
        val fitted = shown.associateWith { i ->
            val canvas = canvases[i] ?: profile.canvas
            val scale = minOf(
                maxWidth.toFloat() / canvas.width,
                maxHeight.toFloat() / canvas.height,
            )
            WorkspaceLayoutProfile.Slot(
                (canvas.width * scale).roundToInt().coerceIn(1, maxWidth),
                (canvas.height * scale).roundToInt().coerceIn(1, maxHeight),
                0,
            )
        }
        val stack = shown.sumOf { fitted.getValue(it).height } +
            (shown.size - 1) * gap
        var y = ((g.sidebarLogicalHeight - footer - stack) / 2).coerceAtLeast(gap)
        return profile.slots.toMutableList().also { result ->
            shown.forEach { i ->
                result[i] = fitted.getValue(i).copy(top = y)
                y += result[i].height + gap
            }
        }
    }
}

package io.github.x647368maker.atomicworkbench.workspace

import kotlin.math.roundToInt

/** Presentation-only idle rail. Its original live slot hosts must remain attached
 * at their original size; the returned geometry is for the main task and handle. */
internal object StageSmartRailGeometry {
    fun horizontal(g: WorkspaceGeometry): Boolean =
        g.railAtTop || (g.isLandscape && !g.isNativeLandscapeRail)

    private fun thickness(density: Float) = (44 * density).roundToInt().coerceAtLeast(1)

    fun reclaim(base: WorkspaceGeometry, density: Float): WorkspaceGeometry {
        // An accepted top-row layout may already let the main task occupy part
        // of the rail. Never shrink the task merely to reserve a hidden handle.
        val reserved = if (base.railAtTop)
            base.sidebarWidth - minOf(base.mainTopOverlap, base.sidebarWidth - 1)
        else base.sidebarWidth
        val candidate = base.copy(mainTopOverlap = 0,
            sidebarWidth = minOf(reserved, thickness(density)).coerceAtLeast(1))
        return if (candidate.contentScale >= base.contentScale) candidate else base
    }

    fun handle(g: WorkspaceGeometry, density: Float): StageTopLayoutMetrics.Box {
        val isHorizontal = horizontal(g)
        val length = (72 * density).roundToInt().coerceAtLeast(1)
        val reserve = if (g.railAtTop)
            g.sidebarWidth - minOf(g.mainTopOverlap, g.sidebarWidth - 1)
        else g.sidebarWidth
        val short = minOf(reserve, thickness(density)).coerceAtLeast(1)
        val w = minOf(g.sidebarPhysicalWidth, if (isHorizontal) length else short, g.availableWidth)
        val h = minOf(g.sidebarPhysicalHeight, if (isHorizontal) short else length, g.availableHeight)
        // Top and legacy landscape top/bottom rails use their real horizontal
        // bounds. Native landscape tablet and portrait side rails stay vertical.
        val x = (g.sidebarLeft + (g.sidebarPhysicalWidth - w) / 2)
            .coerceIn(g.availableLeft, g.availableRight - w)
        val y = (g.sidebarTop + (if (g.railAtTop) reserve - h else g.sidebarPhysicalHeight - h) / 2)
            .coerceIn(g.availableTop, g.availableBottom - h)
        return StageTopLayoutMetrics.Box(x, y, w, h)
    }

    fun towardsContent(g: WorkspaceGeometry, motion: Float): Boolean =
        if (horizontal(g)) {
            if (g.railAtTop || g.isSidebarAtTop) motion > 0 else motion < 0
        } else {
            if (g.sidebarSide == SidebarSide.LEFT) motion > 0 else motion < 0
        }
}

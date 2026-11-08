package io.github.x647368maker.atomicworkbench.workspace

/** Collapsed controls never use the 1-pixel hidden rail as a button's bounding box. */
internal object StageCollapsedRailPolicy {
    fun geometry(base: WorkspaceGeometry, density: Float): WorkspaceGeometry {
        fun dp(value: Int) = (value * density).toInt()
        return if (base.railAtTop) base.copy(sidebarWidth = 1, topHeight = base.topHeight + dp(48))
        else base.copy(sidebarWidth = minOf(base.sidebarWidth, dp(44).coerceAtLeast(1)))
    }
    fun handle(base: WorkspaceGeometry, current: WorkspaceGeometry, density: Float): StageTopLayoutMetrics.Box {
        fun dp(value: Int) = (value * density).toInt()
        val width: Int; val height: Int; val x: Int; val y: Int
        if (base.railAtTop) {
            width = dp(112); height = dp(44)
            x = base.availableLeft + dp(12); y = base.availableTop + base.topHeight + dp(2)
        } else if (base.isLandscape) {
            width = dp(112); height = dp(44)
            x = current.sidebarLeft + dp(12); y = current.sidebarTop
        } else {
            width = dp(44); height = dp(72)
            x = if (base.sidebarSide == SidebarSide.LEFT) current.availableLeft else current.availableRight - width
            y = current.availableTop + current.topHeight + (current.sidebarPhysicalHeight - height) / 2
        }
        return StageTopLayoutMetrics.Box(x.coerceIn(current.availableLeft, current.availableRight - width),
            y.coerceIn(current.availableTop, current.availableBottom - height), width, height)
    }
}

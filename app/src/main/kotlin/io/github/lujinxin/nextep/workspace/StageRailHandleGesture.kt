package io.github.x647368maker.atomicworkbench.workspace

import kotlin.math.abs

/** Direction follows physical display axes, not a portrait-side assumption. */
internal object StageRailHandleGesture {
    enum class Decision { TAP, INWARD, OUTWARD, IGNORE }
    fun horizontalRail(g: WorkspaceGeometry): Boolean =
        g.railAtTop || (g.isLandscape && !g.isNativeLandscapeRail)

    fun positiveOutward(g: WorkspaceGeometry): Boolean =
        if (horizontalRail(g)) !g.railAtTop && !g.isSidebarAtTop
        else g.sidebarSide == SidebarSide.RIGHT

    fun decision(g: WorkspaceGeometry, dx: Float, dy: Float, tapSlop: Float, swipeDistance: Float): Decision {
        if (!dx.isFinite() || !dy.isFinite()) return Decision.IGNORE
        if (maxOf(abs(dx),abs(dy)) <= tapSlop) return Decision.TAP
        val along = if (horizontalRail(g)) dy else dx
        val across = if (horizontalRail(g)) dx else dy
        if (abs(along) < swipeDistance || abs(along) <= abs(across)*1.25f) return Decision.IGNORE
        return if ((along > 0) == positiveOutward(g)) Decision.OUTWARD else Decision.INWARD
    }

    fun collapseOffset(g: WorkspaceGeometry): Pair<Float,Float> {
        val horizontal = horizontalRail(g)
        val distance = if (horizontal) g.sidebarPhysicalHeight.toFloat() else g.sidebarPhysicalWidth.toFloat()
        val signed = if (positiveOutward(g)) distance else -distance
        return if (horizontal) 0f to signed else signed to 0f
    }
}

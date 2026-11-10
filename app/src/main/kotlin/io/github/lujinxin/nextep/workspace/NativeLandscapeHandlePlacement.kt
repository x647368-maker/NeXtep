package io.github.x647368maker.atomicworkbench.workspace

import kotlin.math.roundToInt

/** The optional auto-hidden rail reopens from the same physical top/bottom band.
 * The pre-existing phone/fold handle path is intentionally not modified.
 */
internal object NativeLandscapeHandlePlacement {
    fun hidden(g: WorkspaceGeometry, density: Float): StageTopLayoutMetrics.Box {
        require(g.isNativeLandscapeRail && density > 0f)
        fun dp(value: Int) = (value * density).roundToInt()
        val width = dp(48)
        val height = dp(32)
        val x = (g.availableRight - dp(56)).coerceAtLeast(g.availableLeft)
        val y = g.sidebarTop + dp(12)
        return StageTopLayoutMetrics.Box(x, y, width, height)
    }
}

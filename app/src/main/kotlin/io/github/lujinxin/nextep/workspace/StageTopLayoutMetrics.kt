package io.github.x647368maker.atomicworkbench.workspace

import kotlin.math.roundToInt

/** One source for preview depth and the three controls' disjoint hit rectangles. */
internal object StageTopLayoutMetrics {
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right get() = left + width
        val bottom get() = top + height
        fun overlaps(other: Box) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }
    data class Actions(val collapse: Box, val main: Box, val add: Box)
    fun depth(g: WorkspaceGeometry, density: Float): Int {
        val width = (g.availableWidth - 5 * 8 * density) / 4f
        val desired = (width * 1120f / 720f + (48 + 16) * density).roundToInt()
        val minimumMain = (if (g.isLandscape) 160 else 260) * density
        return minOf(desired, g.availableHeight - g.topHeight - minimumMain.roundToInt())
            .coerceIn(2, minOf(g.availableWidth, g.availableHeight) - 1)
    }
    fun actions(width: Int, height: Int, density: Float): Actions {
        fun dp(value: Int) = (value * density).toInt()
        return Actions(Box(dp(12), height - dp(44), dp(44), dp(32)),
            Box((width - dp(64)) / 2, height - dp(40), dp(64), dp(24)),
            Box(width - dp(54), height - dp(48), dp(44), dp(44)))
    }
}

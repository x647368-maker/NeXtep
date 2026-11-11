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
    /** Width first: four previews occupy ~96% of a 395dp safe viewport.
     * Fewer previews grow moderately; absent slots never contribute to the row. */
    fun cardWidth(g: WorkspaceGeometry, count: Int, density: Float): Int {
        require(count in 1..4 && density > 0)
        val columns = (g.availableWidth - (count + 1) * 8 * density) / count
        val cap = when (count) { 1 -> .38f; 2 -> .34f; 3 -> .30f; else -> 1f }
        return minOf(columns, g.availableWidth * cap).toInt().coerceAtLeast(1)
    }
    fun depth(g: WorkspaceGeometry, density: Float, count: Int = 4, mainPriority: Boolean = false): Int {
        require(count in 0..4 && density > 0)
        if (count == 0) return (56 * density).roundToInt().coerceAtLeast(2)
        val width = cardWidth(g, count, density)
        // Only optional padding changes between policies, never the readable card size.
        val desired = (width * 1120f / 720f + (if (mainPriority) 56 else 64) * density).roundToInt()
        val minimumMain = (if (g.isLandscape) 160 else 220) * density
        val limit = minOf(g.availableWidth - 1, g.availableHeight - g.topHeight - minimumMain.roundToInt())
            .coerceAtLeast(2)
        // Only an explicitly selected TOP rail on a wide tablet gets this
        // height limit. Otherwise portrait-phone preview proportions can
        // consume most of the landscape canvas and make the top choice look
        // unusable. Leave phone and near-square foldable results unchanged.
        val tabletCap = when {
            StageDisplayPolicy.unfoldedNearSquare(g.screenWidth, g.screenHeight) ->
                (g.availableHeight * .24f).roundToInt().coerceAtLeast(2)
            StageDisplayPolicy.wideLandscapeTablet(g.screenWidth, g.screenHeight) ->
                (g.availableHeight * .32f).roundToInt().coerceAtLeast(2)
            else -> limit
        }
        return desired.coerceIn(2, minOf(limit, tabletCap))
    }
    fun actions(width: Int, height: Int, density: Float): Actions {
        fun dp(value: Int) = (value * density).toInt()
        return Actions(Box(dp(12), height - dp(44), dp(44), dp(32)),
            Box((width - dp(64)) / 2, height - dp(40), dp(64), dp(24)),
            Box(width - dp(54), height - dp(48), dp(44), dp(44)))
    }
}

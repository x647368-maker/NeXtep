package io.github.x647368maker.atomicworkbench.workspace

import kotlin.math.roundToInt

internal object StageAdaptiveLayout {
    fun rail(g: WorkspaceGeometry, count: Int, density: Float): WorkspaceGeometry {
        require(count in 0..4 && density > 0)
        if (count == 0 || g.isLandscape) return g
        if (!g.railAtTop) {
            val fraction = when (count) { 1 -> .215f; 2 -> .235f; else -> .25f }
            return g.copy(sidebarWidth = (g.availableWidth * fraction).roundToInt().coerceAtLeast(2))
        }
        val width = minOf((g.availableWidth - (count + 1) * 8 * density) / count,
            g.availableWidth * (if (count == 1) .38f else .30f))
        val depth = (width * 1120f / 720f + 64 * density).roundToInt()
        val max = minOf(g.availableWidth - 1, (g.availableHeight * .34f).toInt(),
            g.availableHeight - g.topHeight - (220 * density).toInt()).coerceAtLeast(2)
        return g.copy(sidebarWidth = depth.coerceIn(2, max))
    }
    fun portraitCinema(g: WorkspaceGeometry): WorkspaceGeometry = g.copy(railAtTop = false,
        sidebarWidth = (g.availableWidth * .34f).roundToInt().coerceIn(2, g.availableWidth - 1))
    fun cinemaDepth(g: WorkspaceGeometry, density: Float): Int =
        ((g.availableWidth - 16 * density) * 9f / 16f + 64 * density).roundToInt()
            .coerceIn(2, minOf(g.availableWidth - 1, g.availableHeight - g.topHeight - 2).coerceAtLeast(2))
}

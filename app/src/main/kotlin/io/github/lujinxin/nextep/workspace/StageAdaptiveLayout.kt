package io.github.x647368maker.atomicworkbench.workspace

import kotlin.math.roundToInt

internal object StageAdaptiveLayout {
    fun rail(g: WorkspaceGeometry, count: Int, density: Float): WorkspaceGeometry {
        require(count in 0..4 && density > 0)
        if (count == 0) return if (g.railAtTop) g.copy(sidebarWidth = StageTopLayoutMetrics.depth(g, density, 0)) else g
        if (g.isLandscape) return g
        if (!g.railAtTop) {
            val fraction = when (count) { 1 -> .215f; 2 -> .235f; else -> .25f }
            return g.copy(sidebarWidth = (g.availableWidth * fraction).roundToInt().coerceAtLeast(2))
        }
        return g.copy(sidebarWidth = StageTopLayoutMetrics.depth(g, density, count))
    }
    fun mainPriority(profile: WorkspaceLayoutProfile, count: Int): WorkspaceLayoutProfile {
        require(count in 0..4)
        val g = profile.geometry
        val band = (56 * profile.densityDpi / 160f).roundToInt().coerceAtLeast(1)
        return profile.copy(geometry=g.copy(topHeight=minOf(g.topHeight,band),mainInset=0))
    }
    fun portraitCinema(g: WorkspaceGeometry): WorkspaceGeometry = g.copy(railAtTop = false,
        sidebarWidth = (g.availableWidth * .34f).roundToInt().coerceIn(2, g.availableWidth - 1))
    fun cinemaDepth(g: WorkspaceGeometry, density: Float): Int =
        ((g.availableWidth - 16 * density) * 9f / 16f + 64 * density).roundToInt()
            .coerceIn(2, minOf(g.availableWidth - 1, g.availableHeight - g.topHeight - 2).coerceAtLeast(2))
}

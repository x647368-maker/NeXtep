package io.github.x647368maker.atomicworkbench.workspace

import kotlin.math.roundToInt

/** Reuse only the empty footer between real cards and main. Never resize/crop the task.
 * The sidebar's narrow input region must be confirmed before sharing that footer. */
internal object StageMainWindowMetrics {
    fun enlarge(profile: WorkspaceLayoutProfile, inputRegionReady: Boolean): WorkspaceLayoutProfile {
        val g = profile.geometry
        if (!inputRegionReady || !g.railAtTop || g.isLandscape || profile.activeCount == 0) return profile
        val d = profile.densityDpi / 160f
        val bodyBottom = profile.slots.first().let { it.top + it.height }
        val overlap = (g.sidebarPhysicalHeight - bodyBottom - (8 * d).roundToInt()).coerceAtLeast(0)
        if (overlap == 0) return profile
        val larger = g.copy(mainTopOverlap = overlap)
        // Both existing edge buttons and the relocated 48dp main handle must stay
        // entirely in wallpaper, including their touch targets and the reserved edge margin.
        val margin = (56 * d).roundToInt()
        if (larger.contentLeft - larger.availableLeft < margin ||
            larger.availableRight - larger.contentRight < margin) return profile
        return profile.copy(geometry = larger)
    }
    fun mainHandle(g: WorkspaceGeometry, density: Float): StageTopLayoutMetrics.Box {
        fun dp(n: Int) = (n * density).roundToInt()
        require(g.mainTopOverlap > 0 && g.railAtTop && !g.isLandscape)
        return StageTopLayoutMetrics.Box(g.availableLeft + dp(8),
            (g.contentTop + dp(48)).coerceAtMost(g.availableBottom - dp(48)), dp(48), dp(48))
    }
}

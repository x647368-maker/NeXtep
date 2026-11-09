package io.github.x647368maker.atomicworkbench.workspace

import kotlin.math.roundToInt

/** Presentation-only fit. Keep the recorded canvas, previews and task identities unchanged. */
internal object StageMainWindowMetrics {
    fun enlarge(profile: WorkspaceLayoutProfile, inputRegionReady: Boolean): WorkspaceLayoutProfile {
        val g = profile.geometry
        if (!inputRegionReady || !g.railAtTop || profile.activeCount == 0) return profile
        val d = profile.densityDpi / 160f
        val bodyBottom = profile.slots.first().let { it.top + it.height }
        val wanted = (g.sidebarPhysicalHeight - bodyBottom - (4*d).roundToInt()).coerceAtLeast(0)
        if (wanted == 0) return profile
        // The persistent 48dp menu/collapse controls now live in the toolbar.
        // Only a four-card row has no add button in its footer to preserve.
        if (profile.activeCount < 4) return profile
        return profile.copy(geometry=g.copy(mainTopOverlap=wanted,mainInset=0))
    }
    fun mainHandle(g: WorkspaceGeometry, density: Float): StageTopLayoutMetrics.Box {
        fun dp(n: Int)=(n*density).roundToInt()
        return StageTopLayoutMetrics.Box(g.availableLeft+dp(8),g.availableTop+dp(4),dp(48),dp(48))
    }
}

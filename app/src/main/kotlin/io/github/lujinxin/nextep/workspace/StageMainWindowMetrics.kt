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
        // Sum separately rounded hit-target edges; rounding 56dp once could miss a pixel.
        val margin = maxOf((8*d).roundToInt()+(48*d).roundToInt(), (12*d).toInt()+(44*d).toInt())
        fun fits(overlap: Int): Boolean {
            val candidate = g.copy(mainTopOverlap=overlap)
            return candidate.contentLeft-candidate.availableLeft >= margin &&
                candidate.availableRight-candidate.contentRight >= margin
        }
        // Keep the largest legal partial improvement, rather than dropping the entire
        // enlargement when a fold state/density leaves insufficient room for an edge button.
        var low=0; var high=wanted
        while(low<high) {
            val mid=(low+high+1)/2
            if(fits(mid)) low=mid else high=mid-1
        }
        return if(low>0 && fits(low)) profile.copy(geometry=g.copy(mainTopOverlap=low)) else profile
    }
    fun mainHandle(g: WorkspaceGeometry, density: Float): StageTopLayoutMetrics.Box {
        fun dp(n: Int)=(n*density).roundToInt()
        require(g.mainTopOverlap>0 && g.railAtTop)
        return StageTopLayoutMetrics.Box(g.availableLeft+dp(8),
            (g.contentTop+dp(48)).coerceAtMost(g.availableBottom-dp(48)),dp(48),dp(48))
    }
}

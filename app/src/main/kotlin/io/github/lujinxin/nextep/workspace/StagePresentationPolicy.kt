package io.github.x647368maker.atomicworkbench.workspace

import kotlin.math.roundToInt

internal object StagePresentationPolicy {
    fun toolbarDp(priority: Boolean, expanded: Boolean): Int = if(priority)56 else if(expanded)112 else 96
    fun apply(profile: WorkspaceLayoutProfile, priority: Boolean, inputReady: Boolean): WorkspaceLayoutProfile {
        val staged = if (priority)
            StageMainWindowMetrics.enlarge(
                StageAdaptiveLayout.mainPriority(profile, profile.activeCount), inputReady)
        else profile.copy(geometry = profile.geometry.copy(mainTopOverlap = 0,
            mainInset = (12 * profile.densityDpi / 160f).roundToInt()))
        val g = staged.geometry
        // Keep a matching 12dp breathing margin *inside* the usable task
        // region, on both edges of a wide Y700 side layout. Previously main
        // priority set mainInset=0, pressing the task against the right bezel.
        // Neither top-row mode nor any phone / near-square foldable is changed.
        if (!g.isNativeLandscapeRail) return staged
        val desiredInset = (12 * profile.densityDpi / 160f).roundToInt().coerceAtLeast(1)
        val safeInset = desiredInset.coerceAtMost(
            (minOf(g.availableWidth - g.sidebarWidth, g.availableHeight - g.topHeight) / 4)
                .coerceAtLeast(1))
        return staged.copy(geometry = g.copy(mainInset = safeInset))
    }
}

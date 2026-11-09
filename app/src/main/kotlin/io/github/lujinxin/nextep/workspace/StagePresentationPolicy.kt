package io.github.x647368maker.atomicworkbench.workspace

import kotlin.math.roundToInt

internal object StagePresentationPolicy {
    fun toolbarDp(priority: Boolean, expanded: Boolean): Int = if(priority)56 else if(expanded)112 else 96
    fun apply(profile: WorkspaceLayoutProfile, priority: Boolean, inputReady: Boolean): WorkspaceLayoutProfile =
        if(priority) StageMainWindowMetrics.enlarge(StageAdaptiveLayout.mainPriority(profile,profile.activeCount),inputReady)
        else profile.copy(geometry=profile.geometry.copy(mainTopOverlap=0,mainInset=(12*profile.densityDpi/160f).roundToInt()))
}

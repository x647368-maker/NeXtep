package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.display.SlotGeometry

/**
 * Lenovo Y700-style wide physical display running a ported phone ROM.
 *
 * Only an uncreated side-rail producer on the physical landscape screen may
 * start with a landscape canvas. An already task-owning virtual display keeps
 * its exact width/height/density across rotation, live swap, and layout changes.
 * This policy never calls VirtualDisplay.resize(), releases output or relaunches
 * tasks. Portrait-only apps may still letterbox normally on Android.
 */
internal object StageTabletCanvasPolicy {
    fun proposed(profile: WorkspaceLayoutProfile, active: SlotGeometry?): SlotGeometry {
        active?.let { return it }
        val g = profile.geometry
        if (!g.isNativeLandscapeRail) return profile.canvas
        return SlotGeometry(g.availableWidth, g.availableHeight, profile.densityDpi)
    }
}

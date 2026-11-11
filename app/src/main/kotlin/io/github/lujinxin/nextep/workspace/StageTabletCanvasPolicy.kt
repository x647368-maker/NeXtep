package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.display.SlotGeometry

/**
 * Lenovo Y700-style wide physical display running a ported phone ROM.
 *
 * Only a new virtual display on the physical landscape screen may
 * start with a landscape canvas, independent of whether TOP, LEFT, or RIGHT
 * was selected when the workbench first opened. Otherwise launching in TOP
 * creates portrait producers that remain portrait forever after selecting
 * LEFT/RIGHT because continuity correctly retains task-owning displays.
 * An already task-owning virtual display keeps
 * its exact width/height/density across rotation, live swap, and layout changes.
 * This policy never calls VirtualDisplay.resize(), releases output or relaunches
 * tasks. Portrait-only apps may still letterbox normally on Android.
 */
internal object StageTabletCanvasPolicy {
    fun proposed(profile: WorkspaceLayoutProfile, active: SlotGeometry?): SlotGeometry {
        active?.let { return it }
        val g = profile.geometry
        // A user can enter via the separate top row before choosing either
        // side rail. The producer direction is a physical display choice, not
        // the selected PREVIEW arrangement. Do not resize an active display.
        if (!StageDisplayPolicy.wideLandscapeTablet(g.screenWidth, g.screenHeight))
            return profile.canvas
        return SlotGeometry(g.availableWidth, g.availableHeight, profile.densityDpi)
    }
}

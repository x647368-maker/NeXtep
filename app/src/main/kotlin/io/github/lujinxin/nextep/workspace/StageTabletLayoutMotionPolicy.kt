package io.github.x647368maker.atomicworkbench.workspace

/** A short live View animation on layout selection only. No screenshots,
 * extra rendering loop, task move or virtual-display recreation.
 * Task surfaces/input transforms remain owned by the existing geometry path.
 */
internal object StageTabletLayoutMotionPolicy {
    data class Entry(val dx: Float, val dy: Float, val durationMs: Long = 200L)

    fun entry(g: WorkspaceGeometry, density: Float): Entry? {
        if (!StageDisplayPolicy.wideLandscapeTablet(g.screenWidth, g.screenHeight))
            return null
        val distance = 10f * density
        return if (g.railAtTop) Entry(0f, -distance)
        else Entry(if (g.sidebarSide == SidebarSide.LEFT) -distance else distance, 0f)
    }
}

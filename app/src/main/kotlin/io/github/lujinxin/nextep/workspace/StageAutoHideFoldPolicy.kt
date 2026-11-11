package io.github.x647368maker.atomicworkbench.workspace

import kotlin.math.roundToInt

/** Presentation geometry only: no task moves, virtual-display recreation or
 * recorded canvas changes. OnePlus compact phones and wide Y700 are untouched.
 * The owner can always reopen the side rail through an independently touchable
 * handle on the physical screen edge.
 */
internal object StageAutoHideFoldPolicy {
    fun reclaim(base: WorkspaceGeometry, density: Float): WorkspaceGeometry {
        if (!StageDisplayPolicy.unfoldedNearSquare(base.screenWidth, base.screenHeight) ||
            base.railAtTop) return base
        val minimum = (44 * density).roundToInt().coerceAtLeast(2)
        return base.copy(mainTopOverlap = 0, sidebarWidth = minOf(base.sidebarWidth, minimum))
    }

    fun edgeHandleX(base: WorkspaceGeometry, density: Float): Int {
        val pad = (8 * density).roundToInt()
        val width = (24 * density).roundToInt()
        return if (base.sidebarSide == SidebarSide.LEFT) base.availableLeft + pad
            else base.availableRight - pad - width
    }
}

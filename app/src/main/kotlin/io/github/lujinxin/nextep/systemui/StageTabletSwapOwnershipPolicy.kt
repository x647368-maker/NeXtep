package io.github.x647368maker.atomicworkbench.systemui

/**
 * A task move is not complete merely because the *target* task appears on
 * its display. If a slot is declared Ready, its virtual display must actually
 * be vacant before its preview is exposed; otherwise the outgoing task can
 * flash once during asynchronous ColorOS reparenting.
 *
 * Only called by the Y700-specific gate once an actual post-move texture frame
 * has arrived. The owner list is obtained through a single runningTasks query.
 */
internal object StageTabletSwapOwnershipPolicy {
    data class Owner(val taskId: Int, val displayId: Int)

    /** expectedTaskId = null represents an empty / Ready virtual display. */
    fun settled(expectedTaskId: Int?, displayId: Int, owners: List<Owner>): Boolean {
        if (displayId < 0) return false
        return if (expectedTaskId == null) owners.none { it.displayId == displayId }
        else owners.any { it.taskId == expectedTaskId && it.displayId == displayId }
        // RunningTasks also reports background/paused tasks on the same
        // virtual display. They must not stall an otherwise valid handoff.
        // A Ready display, unlike an Occupied one, really must be vacant.
    }
}

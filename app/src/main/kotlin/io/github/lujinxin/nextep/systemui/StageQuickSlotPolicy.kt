package io.github.x647368maker.atomicworkbench.systemui

/** A quick-bar command never displaces a second task to "add" one already
 * owned by the workspace. Revalidate the target before any explicit replace. */
internal object StageQuickSlotPolicy {
    data class Owner(val taskId: Int, val displayId: Int)
    enum class Action { ADD, REPLACE, ALREADY_PRESENT, FULL, STALE }

    fun ownedSlot(taskId: Int, displayId: Int, slots: List<Owner?>): Int? =
        slots.indexOfFirst { it?.taskId == taskId && it.displayId == displayId }
            .takeIf { it >= 0 }

    fun action(ids: List<Int?>, source: Int?, target: Int?, expected: Int?): Action {
        if (target != null && (target !in ids.indices || expected == null || ids[target] != expected))
            return Action.STALE
        if (source != null && source in ids.indices && ids[source] != null)
            return Action.ALREADY_PRESENT
        if (target != null) return Action.REPLACE
        return if (ids.isNotEmpty() && ids.any { it == null }) Action.ADD
        else Action.FULL
    }
}

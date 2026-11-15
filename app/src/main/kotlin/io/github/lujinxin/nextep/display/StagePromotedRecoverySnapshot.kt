package io.github.x647368maker.atomicworkbench.display

/** A single fresh recovery observation; never retained across owner turns. */
internal object StagePromotedRecoverySnapshot {
    data class Selection<T>(val task: T?, val isForeground: Boolean)

    fun <T : Any> select(
        query: () -> List<T>,
        expectedId: Int?,
        taskId: (T) -> Int,
        isExpected: (T) -> Boolean,
        isMainDisplay: (T) -> Boolean,
    ): Selection<T> {
        if (expectedId == null) return Selection(null, false)
        val tasks = query()
        val expected = tasks.firstOrNull { taskId(it) == expectedId }
            ?.takeIf { isExpected(it) && isMainDisplay(it) }
        val foreground = tasks.firstOrNull(isMainDisplay)
        return Selection(expected, expected != null && foreground?.let(taskId) == expectedId)
    }
}

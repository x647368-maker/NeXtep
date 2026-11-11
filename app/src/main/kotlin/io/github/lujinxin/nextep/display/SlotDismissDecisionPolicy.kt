package io.github.x647368maker.atomicworkbench.display

/** Pure guard for dismissing a real task without destroying it.
 * Missing observations are never treated as a successful close.
 */
internal object SlotDismissDecisionPolicy {
    const val DEFAULT_DISPLAY_ID = 0

    fun nativeFallbackRequired(wctSettled: Boolean, observedDisplayId: Int?): Boolean =
        !wctSettled && observedDisplayId != DEFAULT_DISPLAY_ID

    fun mayPublishVacantSlot(observedDisplayId: Int?): Boolean =
        observedDisplayId == DEFAULT_DISPLAY_ID

    fun shouldRestoreMain(
        fallbackUsed: Boolean,
        previousMainTaskId: Int?,
        closedTaskId: Int,
        previousMainDisplayId: Int?,
    ): Boolean = fallbackUsed &&
        previousMainTaskId != null && previousMainTaskId != closedTaskId &&
        previousMainDisplayId == DEFAULT_DISPLAY_ID
}

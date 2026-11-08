package io.github.x647368maker.atomicworkbench.display

/** Only direct user launch may create a task. Missing tasks during swaps stay missing. */
internal object TaskContinuityPolicy {
    enum class Origin { WINDOW_SWAP, LAYOUT, LOCK_RESTORE, TASK_OBSERVATION, USER_LAUNCH, USER_MEDIA_BUTTON, SYSTEM_OR_APP }
    fun canLaunch(origin: Origin, existingTask: Boolean) = origin == Origin.USER_LAUNCH && !existingTask
    fun canTransport(origin: Origin, expectedSession: Any?, currentSession: Any?) =
        origin == Origin.USER_MEDIA_BUTTON && expectedSession != null && expectedSession == currentSession
    data class Canvas(val width: Int, val height: Int, val density: Int)
    fun canvas(active: Canvas?, requested: Canvas): Canvas = active ?: requested
    fun shouldMove(currentDisplay: Int?, destination: Int) = currentDisplay != destination
}

package io.github.x647368maker.atomicworkbench.systemui

internal enum class StagePlayback { PLAYING, PAUSED, BUFFERING, STOPPED, UNKNOWN }
internal data class StageMediaKey(val packageName: String, val userId: Int, val token: Any)
internal data class StagePlaybackObservation(val key: StageMediaKey, val state: StagePlayback)
internal data class StageObservedTask(val id: Int, val packageName: String, val userId: Int?)

/** Public session data has no universal Task ID. Ambiguous packages/profiles are never merged. */
internal object StageMediaBinding {
    fun bind(tasks: List<StageObservedTask>, sessions: List<StagePlaybackObservation>): Map<Int, StagePlaybackObservation> {
        val result = mutableMapOf<Int, StagePlaybackObservation>()
        for (task in tasks) {
            if (task.userId == null || tasks.count { it.packageName == task.packageName && it.userId == task.userId } != 1) continue
            val matches = sessions.distinctBy { it.key }.filter { it.key.packageName == task.packageName && it.key.userId == task.userId }
            if (matches.size == 1) result[task.id] = matches.single()
        }
        return result
    }
}
internal enum class StageVideoDirection { LANDSCAPE, PORTRAIT }
internal object StageVideoSignalPolicy {
    // Task frame dimensions alone are not video dimensions. Require agreeing direction signals.
    fun direction(requested: Int?, actual: Int?): StageVideoDirection? = when {
        requested in setOf(0, 6, 8, 11) && actual == 2 -> StageVideoDirection.LANDSCAPE
        requested in setOf(1, 7, 9, 12) && actual == 1 -> StageVideoDirection.PORTRAIT
        else -> null
    }
}

internal class StageDirectionDebounce {
    private var candidate: Pair<Int, StageVideoDirection>? = null
    private var since = 0L
    fun reset() { candidate = null }
    fun update(task: Int, direction: StageVideoDirection?, playing: Boolean, now: Long): StageVideoDirection? {
        if (!playing || direction == null) { reset(); return null }
        val next = task to direction
        if (candidate != next) { candidate = next; since = now }
        return if (now - since >= 700) direction else null
    }
}

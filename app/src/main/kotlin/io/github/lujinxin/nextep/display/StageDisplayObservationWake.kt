package io.github.x647368maker.atomicworkbench.display

/** Owner-Looper only. Moves ONE already scheduled observation earlier on an
 * existing Shell task callback, once per actual display-move reply.
 * No event is treated as proof. The original polling action still queries
 * Binder and performs the caller's identity, deadline and session checks. */
internal class StageDisplayObservationWake(
    private val now: () -> Long,
    private val enqueue: (Long, Runnable) -> Unit,
    private val remove: (Runnable) -> Unit,
) {
    private var pending: Runnable? = null
    private var scheduledAt = 0L
    private var dueAt = 0L
    private var advanced = false

    fun reset() {
        pending?.let(remove)
        pending = null
        advanced = false
    }

    fun schedule(delay: Long, observe: () -> Unit) {
        check(pending == null)
        scheduledAt = now()
        dueAt = scheduledAt + delay
        val task = object : Runnable {
            override fun run() {
                if (pending !== this) return
                pending = null
                observe()
            }
        }
        pending = task
        enqueue(delay, task)
    }

    fun onTaskChanged() {
        val task = pending ?: return
        val t = now()
        // Avoid an immediate burst of early queries or moving an almost-due
        // poll. Keep the original delay if no useful signal arrives.
        if (advanced || t-scheduledAt < 8L || dueAt-t <= 8L) return
        advanced = true
        remove(task)
        enqueue(0L, task)
    }
}

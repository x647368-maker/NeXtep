package io.github.x647368maker.atomicworkbench.task

/** A successful clear is remembered by task-container identity, never by package alone. */
internal class FullscreenBoundsPolicy {
    private val cleared = linkedMapOf<Int, Any>()
    fun needsClear(task: Int, token: Any?, boundsMismatch: Boolean): Boolean =
        token == null || cleared[task] != token || boundsMismatch
    fun markCleared(task: Int, token: Any?) {
        if (token == null) return
        if (cleared.size >= 64) cleared.remove(cleared.keys.first())
        cleared[task] = token
    }
}

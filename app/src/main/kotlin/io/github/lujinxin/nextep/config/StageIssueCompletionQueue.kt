package io.github.x647368maker.atomicworkbench.config

/** Main-thread only. One shared completion flight; stale tickets cannot finish a retry. */
internal class StageIssueCompletionQueue<T> {
    class Ticket<T> internal constructor(val token: Long) {
        internal val listeners = mutableListOf<(T) -> Unit>()
    }
    private var current: Ticket<T>? = null

    fun join(token: Long, listener: (T) -> Unit): Ticket<T>? {
        val existing = current
        if (existing != null) {
            check(existing.token == token) { "Another diagnostic completion is pending" }
            existing.listeners.add(listener)
            return null
        }
        return Ticket<T>(token).also {
            it.listeners.add(listener)
            current = it
        }
    }

    fun owns(ticket: Ticket<T>): Boolean = current === ticket

    fun complete(ticket: Ticket<T>, value: T): Boolean {
        if (!owns(ticket)) return false
        current = null
        val listeners = ticket.listeners.toList()
        ticket.listeners.clear()
        for (listener in listeners) runCatching { listener(value) }
        return true
    }
}

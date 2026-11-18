package io.github.x647368maker.atomicworkbench.workspace

import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-thread, immutable geometry observation snapshot.
 *
 * A fully loaded null is meaningful: the workspace is inactive, so a WM
 * transition must NOT perform a new synchronous settings-provider query.
 * Before the initial subscription callback has delivered a snapshot, keep
 * the established safe direct-read fallback exactly as before.
 */
internal class WorkspaceObservedGeometryCache<T> {
    private data class Entry<T>(val ready: Boolean, val value: T?)
    private val snapshot = AtomicReference(Entry<T>(false, null))

    fun observe(value: T?) {
        snapshot.set(Entry(true, value))
    }

    fun clear() {
        snapshot.set(Entry(false, null))
    }

    fun current(): T? = snapshot.get().value

    fun getOrRead(fallback: () -> T?): T? {
        val entry = snapshot.get()
        return if (entry.ready) entry.value else fallback()
    }
}

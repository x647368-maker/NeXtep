package io.github.x647368maker.atomicworkbench.display

/**
 * Exclusive main-thread ownership for synchronous close/replace operations.
 * A reentrant workspace exit is deferred until the current owner has completed
 * its real task move and any replacement rollback. No task/display ACK is made here.
 */
internal class StageSlotLifecycleGate {
    internal class Lease internal constructor(internal val id: Long)

    private var nextId = 0L
    private var owner: Lease? = null
    private var exitRequested = false
    private var exitPostScheduled = false

    fun acquire(): Lease? {
        if (owner != null || exitRequested) return null
        return Lease(++nextId).also { owner = it }
    }

    fun release(lease: Lease): Boolean {
        check(owner === lease) { "Stale or duplicate slot lifecycle lease release" }
        owner = null
        if (exitRequested && !exitPostScheduled) {
            exitPostScheduled = true
            return true
        }
        return false
    }

    fun requestExitIfOwned(): Boolean {
        if (owner == null) return false
        exitRequested = true
        return true
    }

    fun beginExit() {
        check(owner == null) { "Cannot exit while a slot lifecycle owner is active" }
        exitRequested = true
        exitPostScheduled = false
    }

    fun finishExit() {
        check(owner == null) { "Cannot finish exit while a slot lifecycle owner is active" }
        exitRequested = false
        exitPostScheduled = false
    }

    fun allowsMutation(lease: Lease? = null): Boolean =
        if (owner == null) lease == null && !exitRequested else owner === lease

    val isExitPending: Boolean
        get() = exitRequested
}


/** Strict task-identity check used around asynchronous WindowManager task moves. */
internal object StageTaskIdentityPolicy {
    fun matches(
        expectedTaskId: Int,
        expectedUserId: Int?,
        expectedPackageName: String,
        expectedToken: Any?,
        observedTaskId: Int?,
        observedUserId: Int?,
        observedPackageName: String?,
        observedToken: Any?,
    ): Boolean {
        if (observedTaskId != expectedTaskId || observedUserId != expectedUserId) return false
        return if (expectedToken != null) expectedToken == observedToken
        else expectedPackageName == observedPackageName
    }
}

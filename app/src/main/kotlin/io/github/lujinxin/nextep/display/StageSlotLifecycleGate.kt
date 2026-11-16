package io.github.x647368maker.atomicworkbench.display

/**
 * Main-thread lifecycle lease for synchronous close/replace operations.
 * A reentrant workspace exit is deferred until the outermost mutation settles.
 * No task/display ACK is synthesized by this gate.
 */
internal class StageSlotLifecycleGate {
    private var mutationDepth = 0
    private var exitPending = false
    private var exitPostScheduled = false

    fun beginMutation(): Boolean {
        if (exitPending && mutationDepth == 0) return false
        mutationDepth += 1
        return true
    }

    fun endMutation(): Boolean {
        check(mutationDepth > 0) { "Unbalanced slot lifecycle mutation" }
        mutationDepth -= 1
        if (mutationDepth == 0 && exitPending && !exitPostScheduled) {
            exitPostScheduled = true
            return true
        }
        return false
    }

    fun deferExitIfMutating(): Boolean {
        if (mutationDepth == 0) return false
        exitPending = true
        return true
    }

    fun beginExit() {
        check(mutationDepth == 0) { "Cannot exit while slot ownership is mutating" }
        exitPending = true
        exitPostScheduled = false
    }

    fun finishExit() {
        check(mutationDepth == 0) { "Cannot finish exit while slot ownership is mutating" }
        exitPending = false
        exitPostScheduled = false
    }

    fun allowsMutation(): Boolean = mutationDepth > 0 || !exitPending
}

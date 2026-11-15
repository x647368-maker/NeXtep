package io.github.x647368maker.atomicworkbench.display

/** Owner-only: a settled session + a finished visual may notify one another
 * synchronously. Once the coordinator clears transitionInProgress, a second
 * finish callback must not schedule another full reconciliation. */
internal object StagePhasedFinishGate {
    fun canFinish(current:Boolean,inProgress:Boolean,settled:Boolean,visualFinished:Boolean):Boolean =
        current && inProgress && settled && visualFinished
}

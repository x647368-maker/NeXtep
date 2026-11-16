package io.github.x647368maker.atomicworkbench.systemui

/**
 * Per-tap only. No background capture, refresh or repeating idle timer.
 *
 * v88 route order is live SurfaceControl flight -> short-lived RenderThread
 * snapshot flight -> existing WorkspaceSwapAnimator preview. The snapshot
 * renderer remains a bounded fallback even when the live GPU route is enabled.
 */
internal object StageTransientTapPolicy {
    fun snapshotFallbackEnabled(): Boolean = true
    const val CAPTURE_BUDGET_MS=160L
    const val FIRST_COMMIT_BUDGET_MS=110L
    const val FLIGHT_MS=300L
    const val HANDOFF_BUDGET_MS=480L
    const val MAX_ATTEMPT_MS=1800L
    fun canCaptureSecond(now:Long,deadline:Long):Boolean=now<deadline
    fun canStartMove(valid:Boolean,committed:Boolean,started:Boolean):Boolean=
        valid && committed && !started
    // Check again immediately before the real move, after the asynchronous
    // frame callback/deadline. Do not evaluate the expensive validity probe
    // for a stale session or an already submitted move.
    enum class MoveRequest { IGNORE, STALE, START }
    fun decideMoveRequest(current:Boolean,started:Boolean,valid:()->Boolean):MoveRequest =
        when {
            !current || started -> MoveRequest.IGNORE
            !valid() -> MoveRequest.STALE
            else -> MoveRequest.START
        }
    /** A failed preview may hand back to the established workspace animator only
     * while this attempt still owns the tap and before any real task move starts. */
    fun canFallbackToWorkspacePreview(current:Boolean,moveStarted:Boolean,valid:Boolean):Boolean =
        current && !moveStarted && valid

    fun canHandoff(flightDone:Boolean,moveAcknowledged:Boolean):Boolean=flightDone && moveAcknowledged

    // Recovery is not terminal-commit success. Only a move already submitted
    // and acknowledged may enter the existing bounded stable-output check.
    fun canRecoverUnconfirmedTerminal(moveStarted:Boolean,moveAcknowledged:Boolean,terminalReceiptMissing:Boolean):Boolean =
        moveStarted && moveAcknowledged && terminalReceiptMissing
}

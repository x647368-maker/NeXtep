package io.github.x647368maker.atomicworkbench.systemui

/**
 * Experimental PRIVATE branch trial only. Automatic per-process safety gate.
 * A single cold 28ms GPU mirror miss may recover on the next tap; repeated
 * slow attempts or a severe delay cause a bounded on-demand cooldown.
 * Clear OEM denial still disables for the SystemUI session. The PR53 fallback
 * remains available when live mirroring cannot be used.
 *
 * Does not inspect manufacturer strings or keep a background timer.
 */
internal object StageConcurrentNativeTrialPolicy {
    // Real task owner rebind occurs near the end of a short live GPU flight.
    // OEMs can swap original buffers early when the task move starts.
    const val DURATION_MS=360L
    private const val PREP_BUDGET_MS=28L
    private const val SEVERE_PREP_MS=80L
    private const val SLOW_COOLDOWN_MS=1500L
    private const val SEVERE_COOLDOWN_MS=4000L
    @Volatile private var retryAtMs=0L
    @Volatile private var cooldownReason="none"
    private fun monotonicMs():Long = System.nanoTime()/1_000_000L
    // Do not reparent the real source task while its mirror is still flying.
    // The display move may mutate the leash subtree or buffer/crop on ColorOS.
    private const val MIN_FRAME_COUNT=3
    private const val FINAL_FRAME_TOLERANCE_MS=6L
    @Volatile private var disabled=false
    @Volatile private var disabledReason="none"
    @Volatile private var lastPrepareMs=-1L
    // Only touched on the SystemUI main thread during an explicit swap.
    // Warm first-use reflection can exceed a single frame without OEM failure.
    private var consecutiveSlowPreparations=0

    fun allowed(visible:Boolean, animationEnabled:Boolean, powerSave:Boolean,
                nowMs:Long=monotonicMs()):Boolean =
        !disabled && nowMs>=retryAtMs && visible && animationEnabled && !powerSave

    /** Read-only, explicit reason for a missing animation. Never consumes a retry. */
    fun gateReason(visible:Boolean, animationEnabled:Boolean, powerSave:Boolean,
                   nowMs:Long=monotonicMs()):String = when {
        disabled -> "session_disabled:$disabledReason"
        nowMs<retryAtMs -> "cooldown:$cooldownReason"
        !visible -> "workspace_or_sidebar_not_ready"
        !animationEnabled -> "system_animators_disabled"
        powerSave -> "power_saver"
        else -> "allowed"
    }

    fun fullScreenHost(x:Int,y:Int,width:Int,height:Int,physicalW:Int,physicalH:Int):Boolean =
        x==0 && y==0 && physicalW>1 && physicalH>1 &&
            width>=physicalW && height>=physicalH

    fun acceptPrepareTime(ms:Long, nowMs:Long=monotonicMs()):Boolean {
        lastPrepareMs=ms
        if (disabled || nowMs<retryAtMs || ms < 0L) return false
        if (ms <= PREP_BUDGET_MS) {
            consecutiveSlowPreparations=0
            return true
        }
        consecutiveSlowPreparations++
        // Keep the original per-attempt budget. Slow is not proof of permanent
        // OEM denial; allow an explicit tap to retry after a bounded cooldown.
        if (consecutiveSlowPreparations>=2 || ms>=SEVERE_PREP_MS) {
            cooldownReason="prepare_timeout_${ms}ms_streak${consecutiveSlowPreparations}"
            retryAtMs=nowMs + if(ms>=SEVERE_PREP_MS) SEVERE_COOLDOWN_MS else SLOW_COOLDOWN_MS
            consecutiveSlowPreparations=0
        }
        return false
    }

    /** Do not turn temporary task-leash/geometry readiness into permanent OEM denial. */
    fun transientMirrorOutcome(outcome:String):Boolean = when (outcome) {
        "fallback:detached_or_software",
        "fallback:host_not_full_display",
        "fallback:invalid_bounds",
        "fallback:card_outside_source_root",
        "fallback:no_owned_main_task_mirror",
        // The slot task leash can arrive one Shell callback later. This is
        // not a permanent OEM refusal and must not eat all later animations.
        "fallback:slot_task_leash_not_ready",
        "fallback:invalid_native_handles",
        "fallback:rail_corridor_blocked" -> true
        else -> false
    }

    /**
     * A submitted compositor transaction is not proof of visible pixels.
     * Finish approximately 90% of visible two-card travel before changing real task ownership;
     * if SurfaceFlinger never commits, favor prompt task transfer.
     * No captures, timers or Binder calls in this pure policy.
     */
    fun shouldStartMove(elapsedMs:Long, durationMs:Long,
                        firstTransactionCommitted:Boolean, nativeFrames:Int):Boolean {
        if (elapsedMs < 0L || durationMs <= 0L) return false
        // Starting at 84% used to mutate the producer while the mirror was
        // still in motion (PR63 6.62-6.85s scale snap). The onAnimationEnd
        // callback ALWAYS starts the real move even without a commit event,
        // so a missing compositor callback cannot strand the task.
        return firstTransactionCommitted && nativeFrames >= MIN_FRAME_COUNT &&
            elapsedMs >= durationMs - FINAL_FRAME_TOLERANCE_MS
    }

    /** Old cards can redraw during the flight. Only a slot buffer seen after
     * successful real task exchange is evidence of a fresh target frame.
     */
    fun freshFrameAfterTaskAck(frameAtAck:Long?, currentFrame:Long):Boolean =
        frameAtAck != null && currentFrame > frameAtAck

    // Only used during an active real swap after ownership has been verified.
    // An absent animator-end or detached View must not strand the handoff.
    enum class HandoffAction { WAIT, READY, EXPIRED }
    fun handoffAction(elapsedMs: Long, timeoutMs: Long, frameComplete: Boolean,
                      releasable: Boolean, liveReady: Boolean): HandoffAction =
        when {
            frameComplete && releasable && liveReady -> HandoffAction.READY
            elapsedMs >= timeoutMs -> HandoffAction.EXPIRED
            else -> HandoffAction.WAIT
        }

    fun nextHandoffCheck(nowMs: Long, deadlineMs: Long): Long =
        minOf(nowMs + 16L, deadlineMs)

    fun disableForSession(reason:String) {
        disabledReason=reason.take(100)
        disabled=true
        // Reason is logged by the caller; no app/process data is collected.
    }

    internal fun resetForTests() {
        disabled=false; disabledReason="none"; lastPrepareMs=-1L
        retryAtMs=0L; cooldownReason="none"
        consecutiveSlowPreparations=0
    }
}

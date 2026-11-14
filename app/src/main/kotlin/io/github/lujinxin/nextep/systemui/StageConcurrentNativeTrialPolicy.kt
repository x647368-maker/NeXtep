package io.github.x647368maker.atomicworkbench.systemui

/**
 * Experimental PRIVATE branch trial only. Automatic per-process safety gate.
 * Slow mirror setup rejects that ONE attempt, never every later exchange.
 * An explicit unrecoverable OEM refusal may still disable this private path
 * for the SystemUI session, with an inspectable reason. The PR53 fallback
 * remains available when live mirroring cannot be used.
 *
 * Does not inspect manufacturer strings or keep a background timer.
 */
internal object StageConcurrentNativeTrialPolicy {
    // Real task owner rebind occurs near the end of a short live GPU flight.
    // OEMs can swap original buffers early when the task move starts.
    const val DURATION_MS=360L
    private const val PREP_BUDGET_MS=28L
    // Do not reparent the real source task while its mirror is still flying.
    // The display move may mutate the leash subtree or buffer/crop on ColorOS.
    private const val MIN_FRAME_COUNT=3
    private const val FINAL_FRAME_TOLERANCE_MS=6L
    @Volatile private var disabled=false
    @Volatile private var disabledReason=""
    fun reasonIfDisabled():String = if (disabled) disabledReason else ""

    fun allowed(visible:Boolean, animationEnabled:Boolean, powerSave:Boolean):Boolean =
        !disabled && visible && animationEnabled && !powerSave

    fun fullScreenHost(x:Int,y:Int,width:Int,height:Int,physicalW:Int,physicalH:Int):Boolean =
        x==0 && y==0 && physicalW>1 && physicalH>1 &&
            width>=physicalW && height>=physicalH

    fun acceptPrepareTime(ms:Long):Boolean {
        // A missed frame is NOT proof that this device cannot mirror tasks.
        // Reject the expensive attempt, but allow a warm attempt next tap.
        // No background polling, hot loop or forced animation on slow devices.
        return !disabled && ms >= 0L && ms <= PREP_BUDGET_MS
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

    fun disableForSession(reason:String) {
        disabledReason=reason.take(100)
        disabled=true
    }

    internal fun resetForTests() { disabled=false; disabledReason="" }
}

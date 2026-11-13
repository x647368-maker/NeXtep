package io.github.x647368maker.atomicworkbench.systemui

/**
 * Experimental PRIVATE branch trial only. Automatic per-process safety gate.
 * A single cold 28ms GPU mirror miss may recover on the next tap; repeated
 * slow attempts or a severe delay disable the private native path for safety.
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
    private const val VISUAL_LEAD_IN=0.84f
    private const val NO_COMMIT_FAIL_OPEN_MS=70L
    @Volatile private var disabled=false
    // Only touched on the SystemUI main thread during an explicit swap.
    // Warm first-use reflection can exceed a single frame without OEM failure.
    private var consecutiveSlowPreparations=0

    fun allowed(visible:Boolean, animationEnabled:Boolean, powerSave:Boolean):Boolean =
        !disabled && visible && animationEnabled && !powerSave

    fun fullScreenHost(x:Int,y:Int,width:Int,height:Int,physicalW:Int,physicalH:Int):Boolean =
        x==0 && y==0 && physicalW>1 && physicalH>1 &&
            width>=physicalW && height>=physicalH

    fun acceptPrepareTime(ms:Long):Boolean {
        if (disabled || ms < 0L) return false
        if (ms <= PREP_BUDGET_MS) {
            consecutiveSlowPreparations=0
            return true
        }
        consecutiveSlowPreparations++
        // Never run this attempt if it exceeded 28ms. One ordinary cold miss
        // is recoverable; a second consecutive miss or severe stall locks out.
        if (consecutiveSlowPreparations>=2 || ms>=SEVERE_PREP_MS) disabled=true
        return false
    }

    /** Do not turn temporary task-leash/geometry readiness into permanent OEM denial. */
    fun transientMirrorOutcome(outcome:String):Boolean = when (outcome) {
        "fallback:detached_or_software",
        "fallback:host_not_full_display",
        "fallback:invalid_bounds",
        "fallback:card_outside_source_root",
        "fallback:no_owned_main_task_mirror",
        "fallback:invalid_native_handles" -> true
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
        if (!firstTransactionCommitted) return elapsedMs >= NO_COMMIT_FAIL_OPEN_MS
        return (nativeFrames >= 3 && elapsedMs >= (durationMs * VISUAL_LEAD_IN).toLong()) ||
            elapsedMs >= durationMs - 16L
    }

    /** Old cards can redraw during the flight. Only a slot buffer seen after
     * successful real task exchange is evidence of a fresh target frame.
     */
    fun freshFrameAfterTaskAck(frameAtAck:Long?, currentFrame:Long):Boolean =
        frameAtAck != null && currentFrame > frameAtAck

    fun disableForSession(reason:String) {
        disabled=true
        // Reason is logged by the caller; no app/process data is collected.
    }

    internal fun resetForTests() { disabled=false; consecutiveSlowPreparations=0 }
}

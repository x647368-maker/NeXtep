package io.github.x647368maker.atomicworkbench.systemui

/**
 * Experimental PRIVATE branch trial only. Automatic per-process safety gate.
 * OEM failure or a >28ms mirror setup disables trials until SystemUI restart;
 * ordinary task movement and the PR53 low-cost cue continue normally.
 *
 * Does not inspect manufacturer strings or keep a background timer.
 */
internal object StageConcurrentNativeTrialPolicy {
    // Permit a visible dual-card flight without PR50's 280-360ms task wait.
    const val DURATION_MS=195L
    private const val PREP_BUDGET_MS=28L
    private const val VISUAL_LEAD_IN=0.56f
    private const val NO_COMMIT_FAIL_OPEN_MS=70L
    @Volatile private var disabled=false

    fun allowed(visible:Boolean, animationEnabled:Boolean, powerSave:Boolean):Boolean =
        !disabled && visible && animationEnabled && !powerSave

    fun fullScreenHost(x:Int,y:Int,width:Int,height:Int,physicalW:Int,physicalH:Int):Boolean =
        x==0 && y==0 && physicalW>1 && physicalH>1 &&
            width>=physicalW && height>=physicalH

    fun acceptPrepareTime(ms:Long):Boolean {
        if(ms>PREP_BUDGET_MS) disabled=true
        return !disabled
    }

    /**
     * A submitted compositor transaction is not proof of visible pixels.
     * Wait for a small visual lead-in before changing real task ownership;
     * if SurfaceFlinger never commits, favor prompt task transfer.
     * No captures, timers or Binder calls in this pure policy.
     */
    fun shouldStartMove(elapsedMs:Long, durationMs:Long,
                        firstTransactionCommitted:Boolean, nativeFrames:Int):Boolean {
        if (elapsedMs < 0L || durationMs <= 0L) return false
        if (!firstTransactionCommitted) return elapsedMs >= NO_COMMIT_FAIL_OPEN_MS
        return (nativeFrames >= 2 && elapsedMs >= (durationMs * VISUAL_LEAD_IN).toLong()) ||
            elapsedMs >= durationMs - 16L
    }

    fun disableForSession(reason:String) {
        disabled=true
        // Reason is logged by the caller; no app/process data is collected.
    }

    internal fun resetForTests() { disabled=false }
}

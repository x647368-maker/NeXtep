package io.github.x647368maker.atomicworkbench.systemui

/**
 * Experimental PRIVATE branch trial only. Automatic per-process safety gate.
 * OEM failure or a >12ms mirror setup disables trials until SystemUI restart;
 * ordinary task movement and the PR53 low-cost cue continue normally.
 *
 * Does not inspect manufacturer strings or keep a background timer.
 */
internal object StageConcurrentNativeTrialPolicy {
    const val DURATION_MS=165L
    private const val PREP_BUDGET_MS=12L
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

    fun disableForSession(reason:String) {
        disabled=true
        // Reason is logged by the caller; no app/process data is collected.
    }

    internal fun resetForTests() { disabled=false }
}

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

    // Deliberately narrower than native fullScreenHost admission: buffer
    // cropping uses physical pixels and cannot fit a mismatched viewport.
    fun wallpaperHostMatches(width:Int,height:Int,physicalW:Int,physicalH:Int):Boolean =
        physicalW>1 && physicalH>1 && width==physicalW && height==physicalH

    // Publication of an optional prepared buffer is a separate operation
    // from publication of the ordinary backdrop. Every ownership gate must
    // still match at delivery, including after power or viewport transitions.
    fun wallpaperTexturePublishable(generationCurrent:Boolean,epochCurrent:Boolean,
        frameCurrent:Boolean,visible:Boolean,viewportCurrent:Boolean,eligible:Boolean):Boolean =
        generationCurrent && epochCurrent && frameCurrent && visible && viewportCurrent && eligible

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
        "fallback:mirror_source_changed",
        "fallback:invalid_native_handles",
        "fallback:rail_corridor_blocked" -> true
        else -> false
    }

    // Receipt time and UI dispatch time are deliberately distinct. A real
    // callback received by the original absolute deadline is admissible;
    // an absent, pre-submit or late callback is not. No extended wait.
    fun firstReceiptWithinBudget(submittedMs:Long, receivedMs:Long,budgetMs:Long):Boolean =
        submittedMs>=0L && receivedMs>=submittedMs && budgetMs>=0L &&
            receivedMs-submittedMs<=budgetMs

    /** Only the active experimental GPU flight needs these local identity
     * checks. A missing or changed task leash is not permanent OEM denial. */
    fun sourceIdentityReady(hostAttached:Boolean,handlesValid:Boolean,
                            incomingMatches:()->Boolean,outgoingMatches:()->Boolean):Boolean =
        hostAttached && handlesValid &&
            runCatching { incomingMatches() && outgoingMatches() }.getOrDefault(false)

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

    // The experimental concurrent flight must acknowledge the final
    // compositor transaction before holding the two animated endpoint layers.
    // A missing acknowledgement is not pixel proof: hide the layers and move
    // the real tasks once, without claiming an animated handoff.
    const val TERMINAL_COMMIT_BUDGET_MS=64L
    fun terminalReceiptWithinBudget(submittedMs:Long,receivedMs:Long):Boolean =
        firstReceiptWithinBudget(submittedMs,receivedMs,TERMINAL_COMMIT_BUDGET_MS)
    enum class MovePresentation { ANIMATED, UNMASKED }
    /** Only a pristine, still-owned GPU attempt may hand off before its first submitted frame. */
    fun canFallbackBeforeFirstGpuFrame(current:Boolean,hostAttached:Boolean,
                                        sourceReady:Boolean,moveStarted:Boolean,
                                        submittedFrames:Int):Boolean =
        current && hostAttached && sourceReady && !moveStarted && submittedFrames==0

    fun movePresentation(firstCommitted:Boolean,terminalRequired:Boolean,
                         terminalCommitted:Boolean):MovePresentation =
        if(firstCommitted && (!terminalRequired || terminalCommitted))
            MovePresentation.ANIMATED else MovePresentation.UNMASKED

    // A committed initial frame or previously submitted identical progress
    // already owns this exact transform. Keep live producer updates and all
    // distinct motion; never skip an explicit terminal receipt transaction.
    fun unchangedFlightFrame(concurrent:Boolean, firstCommitted:Boolean, terminal:Boolean,
                             last:Float, next:Float):Boolean =
        concurrent && firstCommitted && !terminal && last.isFinite() &&
            next.isFinite() && last == next

    // A terminal geometry submission is explicit; never rely solely on
    // AnimatorListenerAdapter.onAnimationEnd on vendor SystemUI.
    fun terminalFrameDue(playTimeMs: Long, durationMs: Long, fraction: Float,
                         firstCommitted: Boolean, frames: Int): Boolean =
        durationMs > 0L && (fraction >= 1f || playTimeMs >= durationMs ||
            shouldStartMove(playTimeMs,durationMs,firstCommitted,frames))

    /** Single active-swap fallback based on the actual animator scale.
     * Keep original lifetime watchdog as an upper bound. */
    fun terminalDeadlineDelay(durationMs: Long, scale: Float): Long =
        ((durationMs * (if (scale.isFinite()) scale.coerceIn(0f,4f) else 1f)).toLong() +
            64L).coerceIn(64L,1500L)

    /** Old cards can redraw during the flight. Only a slot buffer seen after
     * successful real task exchange is evidence of a fresh target frame.
     */
    fun freshFrameAfterTaskAck(frameAtAck:Long?, currentFrame:Long):Boolean =
        frameAtAck != null && currentFrame > frameAtAck

    // The ownership checkpoint is after verified destination layout. A strict
    // output identity and a newer callback are necessary, not pixel proof.
    fun sameOutput(oldDisplay:Int,newDisplay:Int,oldTexture:Any?,newTexture:Any?,
                   oldSurface:Any?,newSurface:Any?):Boolean =
        oldDisplay==newDisplay && oldTexture!=null && oldSurface!=null &&
            oldTexture===newTexture && oldSurface===newSurface

    fun freshFrameOnConfirmedOutput(sameOutput:Boolean, baseline:Long?, current:Long):Boolean =
        sameOutput && freshFrameAfterTaskAck(baseline,current)

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

    // A covered adaptive flight can have zero edge gap but different source
    // crop/reflow. The short cover-first blend is optional, never a wait for
    // a missing vendor receipt and never evidence of newly presented pixels.
    const val ADAPTIVE_HANDOFF_REVEAL_MS=48L

    fun adaptiveHandoffBlend(adaptiveCovered:Boolean, firstCommitted:Boolean,
                             incomingCommitted:Boolean, outgoingCommitted:Boolean,
                             endpointFailed:Boolean, remainingMs:Long,
                             animatorScale:Float):Boolean =
        adaptiveCovered && firstCommitted && incomingCommitted && outgoingCommitted &&
            !endpointFailed && animatorScale.isFinite() && animatorScale>0f &&
            (ADAPTIVE_HANDOFF_REVEAL_MS.toDouble()*animatorScale.toDouble()+
                TERMINAL_COMMIT_BUDGET_MS) <= remainingMs.toDouble()

    /** A failed vendor release must not strand the remaining compositor
     * resources or prevent completion of the real exchange. Only on teardown. */
    fun cleanupSteps(vararg steps:()->Unit, onFailure:(Throwable)->Unit) {
        for(step in steps) {
            runCatching(step).onFailure { error -> runCatching { onFailure(error) } }
        }
    }

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

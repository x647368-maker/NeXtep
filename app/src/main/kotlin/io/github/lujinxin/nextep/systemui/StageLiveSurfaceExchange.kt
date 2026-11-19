package io.github.x647368maker.atomicworkbench.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceControl
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import io.github.x647368maker.atomicworkbench.BuildConfig
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class StageMirrorRootContract {
    DETACHED, RETAINED, UNKNOWN;

    // Opposite defaults distinguish a known flag from a missing flag.
    companion object {
        fun fromFlagReads(defaultFalse: Boolean?, defaultTrue: Boolean?): StageMirrorRootContract =
            when {
                defaultFalse == true && defaultTrue == true -> DETACHED
                defaultFalse == false && defaultTrue == false -> RETAINED
                else -> UNKNOWN
            }
    }

    fun detachedOrNull(): Boolean? = when (this) {
        DETACHED -> true
        RETAINED -> false
        UNKNOWN -> null
    }
}

/** Separate missing/unreadable flag evidence from the inferred coordinate contract.
 * Aconfig's two default values distinguish a missing key (or empty package)
 * from a value actually returned by the API. They do not prove OEM behavior.
 */
internal data class StageMirrorRootObservation(
    val contract:StageMirrorRootContract,
    val reason:String,
    val errorType:String?=null,
    val errorCode:Int?=null,
) {
    fun diagnostic():String = reason +
        (errorType?.let { ":$it" } ?: "") +
        (errorCode?.let { ":code=$it" } ?: "")

    companion object {
        fun fromReads(a:Boolean?,b:Boolean?):StageMirrorRootObservation {
            val contract=StageMirrorRootContract.fromFlagReads(a,b)
            val reason=when {
                a==null || b==null -> "invalid_read_type"
                a==false && b==true -> "missing_key_or_empty_package"
                a==true && b==false -> "inconsistent_reads"
                else -> "flag_read"
            }
            return StageMirrorRootObservation(contract,reason)
        }

        fun error(stage:String,error:Throwable):StageMirrorRootObservation {
            val cause=(error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            val code=runCatching {
                if(cause.javaClass.name=="android.os.flagging.AconfigStorageReadException")
                    cause.javaClass.getMethod("getErrorCode").invoke(cause) as? Int
                else null
            }.getOrNull()
            return StageMirrorRootObservation(StageMirrorRootContract.UNKNOWN,stage,
                cause.javaClass.simpleName,code)
        }
    }
}

/**
 * PRIVATE DIAGNOSTIC CANDIDATE ONLY — evidence scope from a captured firmware,
 * not an OEM-wide capability determination. Never carry this override into
 * a stable/release build. The original observation remains UNKNOWN.
 */
internal object StageMirrorEvidenceTrial {
    const val DIAGNOSTIC_REQUIRED = "mirror_evidence_diagnostic_required"

    private fun eligibleUnknown(observed:StageMirrorRootObservation,
                                firmwareScopedDebug:Boolean):Boolean =
        firmwareScopedDebug && observed.contract==StageMirrorRootContract.UNKNOWN &&
            observed.reason in setOf("missing_key_or_empty_package","class_lookup",
                "load_method_lookup","package_load","read_method_lookup",
                "read_default_false","read_default_true")

    fun awaitingDiagnostic(observed:StageMirrorRootObservation,firmwareScopedDebug:Boolean,
                           optInDiagnostic:Boolean):Boolean =
        !optInDiagnostic && eligibleUnknown(observed,firmwareScopedDebug)

    fun resolve(observed:StageMirrorRootObservation,firmwareScopedDebug:Boolean,
                optInDiagnostic:Boolean):StageMirrorRootContract {
        if(!firmwareScopedDebug)return observed.contract
        // An explicit contradictory flag read on the measured firmware
        // cannot be used as proof for either path.
        if(observed.contract==StageMirrorRootContract.RETAINED)
            return StageMirrorRootContract.UNKNOWN
        if(observed.contract!=StageMirrorRootContract.UNKNOWN)return observed.contract
        if(!optInDiagnostic)return StageMirrorRootContract.UNKNOWN
        // Missing keys/packages or inaccessible readers are not a capability
        // signal; only the separately scoped, visible-mirror evidence supports
        // this marked private trial. Do not override inconsistent/invalid reads.
        if(!eligibleUnknown(observed,firmwareScopedDebug))return StageMirrorRootContract.UNKNOWN
        return StageMirrorRootContract.DETACHED
    }
}

/**
 * Experimental real live SurfaceFlinger flight, NOT a bitmap preview.
 *
 * Source is a mirror of the current rail-window ViewRoot. Its crop contains
 * exactly one occupied card and keeps receiving the producer's live buffers.
 * Target is a mirror of the current physical-display task leash. Both mirrors
 * are moved in the SAME SurfaceControl.Transaction on each display frame.
 * Original SurfaceControls are never reparented, scaled, hidden or released.
 *
 * In the private overlapping mode, task migration starts after a compositor
 * commitment and near-complete visual flight, then begins task migration
 * while the mirrors hold their final transforms until real buffers settle. The legacy slow mode still transfers near the end. If the vendor denies mirroring or ownership is stale,
 * create() returns null and the old proven exchange path is unchanged.
 */
internal class StageLiveSurfaceExchange private constructor(
    private val host: FrameLayout,
    private val sourceMirror: SurfaceControl,
    private val mainMirror: SurfaceControl,
    private val sourceCrop: Rect,
    private val mainCrop: Rect,
    private val slotStart: StageLiveSurfaceFlightGeometry.Box,
    private val mainStart: StageLiveSurfaceFlightGeometry.Box,
    private val backdrop: Drawable?,
    private val occlusion: StageGpuMirrorOcclusion,
    private val coveredFlight: Boolean,
    private val incomingStamp: TaskSurfaceCompat.MirrorSourceStamp,
    private val outgoingStamp: TaskSurfaceCompat.MirrorSourceStamp,
    private val detachedRoot: Boolean,
) {
    private val incomingGeometryCrop = StageLiveSurfaceFlightGeometry.Crop(
        sourceCrop.left,sourceCrop.top,sourceCrop.right,sourceCrop.bottom)
    private val outgoingGeometryCrop = StageLiveSurfaceFlightGeometry.Crop(
        mainCrop.left,mainCrop.top,mainCrop.right,mainCrop.bottom)
    private var pairedRailPlan:SmoothPairedRailCandidate.Plan?=null
    // Null only for the existing synthetic compositor fixtures; production
    // create() always binds the settled, owned original task geometry.
    private var mainSourceFrame:TaskSurfaceCompat.MirrorDestinationFrame?=null
    private var mainFirstFrame:StageLiveSurfaceFlightGeometry.MirrorFrame?=null
    private var slotSourceFrame:TaskSurfaceCompat.MirrorDestinationFrame?=null
    private var slotSourceView:io.github.x647368maker.atomicworkbench.display.TaskSwitcherView?=null
    private var slotSourceBounds:RectF?=null
    private var slotSourceTexture:android.view.TextureView?=null
    private var incomingAnchor:StageEndpointContentMapping.Anchor?=null
    private var outgoingAnchor:StageEndpointContentMapping.Anchor?=null

    private fun slotSourceStillMatches():Boolean {
        val original=slotSourceFrame ?: return true
        // A bound slot source exists only for the exact opt-in content trial.
        val now=TaskSurfaceCompat.ownedSlotMirrorFrame(incomingStamp,allowCompletedDemotion=true) ?: return false
        return now.leash===original.leash && now==original &&
            slotSourceView?.textureView===slotSourceTexture &&
            slotSourceView?.mirrorLiveContentBounds()==slotSourceBounds
    }

    private fun localCrop(frame:TaskSurfaceCompat.MirrorDestinationFrame)=
        StageLiveSurfaceFlightGeometry.Crop(frame.crop.left,frame.crop.top,frame.crop.right,frame.crop.bottom)

    private fun destinationStamp(original:TaskSurfaceCompat.MirrorSourceStamp,display:Int):
        TaskSurfaceCompat.MirrorSourceStamp? =
        TaskSurfaceCompat.mirrorSourceStamp(original.taskId,display)?.takeIf {
            it.taskId==original.taskId && it.token==original.token && it.leash===original.leash
        }

    /** A cache/view geometry comparison, not an app buffer or visible-pixel receipt.
     * Only called at the existing ready/reveal boundaries, never by a new poller.
     */
    private fun destinationContentMatches(phase:String):Boolean =
        destinationContentRead(phase)==StageEndpointContentMapping.DestinationRead.READY

    private fun destinationContentRead(phase:String):StageEndpointContentMapping.DestinationRead {
        val up=incomingAnchor ?: return StageEndpointContentMapping.DestinationRead.READY
        val down=outgoingAnchor ?: return StageEndpointContentMapping.DestinationRead.REJECTED
        val diag=io.github.x647368maker.atomicworkbench.logging.StageSurfaceHandoffTrace
        fun missing(reason:String,pending:Boolean=false):StageEndpointContentMapping.DestinationRead {
            val status=if(pending)StageEndpointContentMapping.DestinationRead.PENDING
                       else StageEndpointContentMapping.DestinationRead.REJECTED
            diag.detail(traceAttemptId,"content_real_"+phase+"_"+status.name) {
                "content_real_$phase unconfirmed=$reason status=$status requested_geometry_only"
            }
            return status
        }
        if(endpointWriteFailed.get())return missing("held_write_failed")
        // Do not extend the hold on a changed or unconfirmed task identity.
        val mapper=StageEndpointContentMapping
        val mainStamp=destinationStamp(incomingStamp,outgoingStamp.displayId) ?: return missing("main_identity")
        val slotStamp=destinationStamp(outgoingStamp,incomingStamp.displayId) ?: return missing("slot_identity")
        val slotView=slotSourceView ?: return missing("slot_view")
        if(slotView.textureView!==slotSourceTexture)return missing("slot_texture_identity")
        if(!incomingRebased.get() || !outgoingRebased.get())return missing("held_write",pending=true)
        val main=TaskSurfaceCompat.ownedMainMirrorFrame(mainStamp) ?: return missing("main_geometry",pending=true)
        val slot=TaskSurfaceCompat.heldDestinationSlotMirrorFrame(slotStamp) ?: return missing("verified_slot_geometry",pending=true)
        val slotBounds=slotView.mirrorLiveContentBounds() ?: return missing("slot_view_geometry",pending=true)
        val mainMap=StageEndpointContentMapping.ContentMap(main.scale,main.x,main.y)
        val slotMap=mapper.slotMap(localCrop(slot),StageLiveSurfaceFlightGeometry.Box(
            slotBounds.left,slotBounds.top,slotBounds.right,slotBounds.bottom)) ?: return missing("slot_content_map")
        val upDelta=mapper.mapError(up.target,mainMap,up.targetLocal)
        val downDelta=mapper.mapError(down.target,slotMap,down.targetLocal)
        val upOk=localCrop(main)==up.targetLocal && upDelta<=1f
        val downOk=localCrop(slot)==down.targetLocal && downDelta<=1f
        val status=if(upOk && downOk)StageEndpointContentMapping.DestinationRead.READY
                   else StageEndpointContentMapping.DestinationRead.REJECTED
        diag.detail(traceAttemptId,"content_real_"+phase+"_"+status.name) {
            "content_real_$phase up_err=$upDelta down_err=$downDelta crop_match=$upOk/$downOk requested_geometry_only"
        }
        return status
    }

    private fun mainSourceStillMatches():Boolean {
        val original=mainSourceFrame ?: return true
        val now=TaskSurfaceCompat.ownedMainMirrorFrame(outgoingStamp) ?: return false
        return now.leash===original.leash && now==original
    }
    // Real ViewRoot instrumentation uses this explicit test seam. The ordinary
    // task factory still requires both private debug flags for route selection.
    internal fun preparePairedRailRuntimeTest(
        screen:StageLiveSurfaceFlightGeometry.Box,
        obstacles:List<StageLiveSurfaceFlightGeometry.Box>,
    ):Boolean {
        if(finished.get() || isRunning)return false
        // Covered fixtures exercise the same new paths as the production
        // factory. Fake task identities still fail the migration preflight.
        pairedRailPlan=if(coveredFlight) {
            if(!StageLiveSurfaceFlightGeometry.canUseCoveredFlight(slotStart,mainStart,
                    incomingGeometryCrop,outgoingGeometryCrop,obstacles))return false
            // Match the complete production admission chain at create():
            // new straight > new reversible rail > old certified > legacy
            // adaptive covered. The strict new first-pulse crop guard must
            // reject unsafe NEW paths, but must not make the independently
            // certified fallback invisible to the real compositor test.
            // No fake task move and no relaxation of ANY pixel assertion.
            StageAdaptiveCoveredRailRoute.straightCrossing(slotStart,mainStart,incomingGeometryCrop,
                outgoingGeometryCrop,screen,obstacles,{true})
                ?: StageAdaptiveCoveredRailRoute.railCrossing(slotStart,mainStart,incomingGeometryCrop,
                    outgoingGeometryCrop,screen,obstacles,{true})
                ?: StageCertifiedRailRoute.choose(slotStart,mainStart,incomingGeometryCrop,
                    outgoingGeometryCrop,screen,obstacles,oldCovered=true)
                ?: StageAdaptiveCoveredRailRoute.choose(slotStart,mainStart,incomingGeometryCrop,
                    outgoingGeometryCrop,screen,obstacles,{true})
        } else StageCertifiedRailRoute.choose(slotStart,mainStart,incomingGeometryCrop,
            outgoingGeometryCrop,screen,obstacles,oldCovered=false)
        return pairedRailPlan!=null
    }
    private val ui = Handler(Looper.getMainLooper())
    private val traceAttemptId = StageExchangeTelemetry.currentNativeTapId()
    private fun trace(stage: String) {
        // A flight created outside a recorded tap must not write into a later tap.
        traceAttemptId?.let { StageExchangeTelemetry.nativeEvent(stage, it) }
    }

    private fun traceMirrorAdmission(detached:Boolean?) {
        val diag=io.github.x647368maker.atomicworkbench.logging.StageSurfaceHandoffTrace
        diag.detail(traceAttemptId,"mirror_up") {
            "mirror_up task=${incomingStamp.taskId} src_layer=${diag.layerId(incomingStamp.leash)} mirror_layer=${diag.layerId(sourceMirror)} root=$detached crop=${sourceCrop.flattenToString()} target=$mainStart"
        }
        diag.detail(traceAttemptId,"mirror_down") {
            "mirror_down task=${outgoingStamp.taskId} src_layer=${diag.layerId(outgoingStamp.leash)} mirror_layer=${diag.layerId(mainMirror)} root=$detached crop=${mainCrop.flattenToString()} target=$slotStart"
        }
    }
    private fun traceCoverHideSubmitted(route: String) {
        // Submission returned, not a compositor receipt or proof of presented pixels.
        runCatching { trace("cover_hide_submit:$route up=${incomingStamp.taskId} down=${outgoingStamp.taskId}") }
        io.github.x647368maker.atomicworkbench.logging.StageSurfaceHandoffTrace.mark {
            "cover_hide_submit $route up=${incomingStamp.taskId} down=${outgoingStamp.taskId} attempt=$traceAttemptId"
        }
    }
    private val lifetimeWatchdog = Runnable {
        if (isRunning) {
            trace("lifetime_watchdog frame=$frameComplete move=$moveComplete check=$handoffCheckStarted")
            if (moveComplete && !handoffReady) handoffTimedOut=true
            NeXtepLog.warn("native_swap", "Two-mirror flight exceeded safety lifetime; releasing compositor mirrors")
            stop()
        }
    }
    private val finished = AtomicBoolean(false)
    // Scoped to one flight: even a brief detach/reattach invalidates the
    // compositor parent and original viewport. Never reuse their old stamps.
    @Volatile private var hostDetached=false
    private val detachCleanup=Runnable { stop() }
    private val attachmentListener=object:View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v:View) = Unit
        override fun onViewDetachedFromWindow(v:View) {
            hostDetached=true
            // Avoid mutating View children inside framework detach dispatch.
            ui.post(detachCleanup)
        }
    }
    private var animator: ValueAnimator? = null
    private var revealAnimator: ValueAnimator? = null
    private var revealCommitDeadline: Runnable? = null
    private var revealMotionDeadline: Runnable? = null
    private var handoffCheckStarted = false
    private var handoffCheck: Runnable? = null
    private var flightDeadline: Runnable? = null
    private var pendingFirstFlight: Runnable? = null
    private var deferredMove: Runnable? = null
    private var lastHandoffWait = ""
    private var endpointGap = 0f
    private var heldWriter: TaskSurfaceCompat.HeldMirrorWriter? = null
    private val incomingRebased=AtomicBoolean(false)
    private val outgoingRebased=AtomicBoolean(false)
    private val endpointWriteFailed=AtomicBoolean(false)
    // The first matching destination rebase receipt is stronger than a local
    // frame write, but is NOT a fresh producer buffer or visible-pixel proof.
    private val incomingRebaseReceiptArmed=AtomicBoolean(false)
    private val outgoingRebaseReceiptArmed=AtomicBoolean(false)
    private val incomingRebaseCommitted=AtomicBoolean(false)
    private val outgoingRebaseCommitted=AtomicBoolean(false)

    // After the terminal frame, the source producer can change its bounds
    // AND its local root transform. Keep the mirror in the same destination
    // card using the exact owned frame written in the native transaction.
    // No visibility/alpha changes, no screenshot, no original-task mutation.
    private fun armHeldEndpointWriter() {
        val inStamp=incomingStamp
        val outStamp=outgoingStamp
        if (heldWriter!=null || !isRunning) return
        val density=host.resources.displayMetrics.density
        val radiusSetter=cornerMethod
        val writer=TaskSurfaceCompat.HeldMirrorWriter { transaction, frame ->
            if (finished.get() || hostDetached || endpointWriteFailed.get()) return@HeldMirrorWriter
            val incoming=frame.taskId==inStamp.taskId
            val stamp=if(incoming) inStamp else outStamp
            val expectedDisplay=if(incoming) outStamp.displayId else inStamp.displayId
            if (frame.taskId!=stamp.taskId || frame.displayId!=expectedDisplay ||
                frame.token!=stamp.token || frame.leash!==stamp.leash) return@HeldMirrorWriter
            val mirror=if(incoming) sourceMirror else mainMirror
            if(!mirror.isValid) return@HeldMirrorWriter
            val target=if(incoming) mainStart else slotStart
            val anchor=if(incoming)incomingAnchor else outgoingAnchor
            val root=anchor?.let {
                StageEndpointContentMapping.rootMap(frame.scale,frame.x,frame.y,detachedRoot)
            }
            val anchored=anchor?.let { StageEndpointContentMapping.held(it,localCrop(frame),checkNotNull(root)) }
            if(anchor!=null && anchored==null) {
                endpointWriteFailed.set(true)
                trace("held_content_anchor_unrepresentable")
                return@HeldMirrorWriter
            }
            // Preserve #173 for paths outside the exact, opt-in content trial.
            val mainFit=if(anchor==null && incoming) StageHeldEndpointPolicy.mainDestinationFit(
                target.left,target.top,target.right,target.bottom,
                frame.crop.left,frame.crop.top,frame.crop.right,frame.crop.bottom,
                frame.scale,frame.x,frame.y,detachedRoot) else null
            if(incoming && mainFit==null) {
                if(anchor==null) {
                    endpointWriteFailed.set(true)
                    trace("held_main_coordinate_unrepresentable")
                    return@HeldMirrorWriter
                }
            }
            val covered=if(anchor==null && !incoming && coveredFlight) StageHeldEndpointPolicy.coveredFit(
                target.left,target.top,target.right,target.bottom,
                frame.crop.left,frame.crop.top,frame.crop.right,frame.crop.bottom,
                frame.scale,frame.x,frame.y,detachedRoot) else null
            val fit=anchored ?: (mainFit ?: (covered ?: StageHeldEndpointPolicy.fit(
                target.left,target.top,target.right,target.bottom,
                frame.crop.left,frame.crop.top,frame.crop.right,frame.crop.bottom,
                frame.scale,frame.x,frame.y,detachedRoot))) ?: return@HeldMirrorWriter
            runCatching {
                if (finished.get() || hostDetached || !mirror.isValid) return@runCatching
                // Crop and transform are submitted with the task's new frame.
                // Keep corner radius optional (the same OEM API as the flight).
                transaction.setCrop(mirror,Rect(fit.left,fit.top,fit.right,fit.bottom))
                transaction.setScale(mirror,fit.scale,fit.scale)
                transaction.setPosition(mirror,fit.x,fit.y)
                if(radiusSetter!=null) runCatching {
                    radiusSetter.invoke(transaction,mirror,(24f*density/fit.scale).coerceIn(0f,120f*density))
                }
                val diag=io.github.x647368maker.atomicworkbench.logging.StageSurfaceHandoffTrace
                if(diag.evidence.hasOpenCapture())diag.writer(traceAttemptId,"held_mirror",frame.taskId,mirror,
                    fit.scale,fit.scale,fit.x,fit.y,fit.left,fit.top,fit.right,fit.bottom)
                val rebaseFlag=if(incoming) incomingRebased else outgoingRebased
                if(rebaseFlag.compareAndSet(false,true)) runCatching {
                    if(anchor!=null) {
                        val phase=if(incoming)"up" else "down"
                        val effective=StageEndpointContentMapping.compose(
                            StageEndpointContentMapping.ContentMap(fit.scale,fit.x,fit.y),checkNotNull(root))
                        val error=StageEndpointContentMapping.mapError(anchor.target,effective,anchor.commonLocal)
                        diag.detail(traceAttemptId,"content_held_"+phase) {
                            "content_held_$phase task=${frame.taskId} effective=${effective.scale},${effective.x},${effective.y} " +
                                "terminal_delta_px=$error crop=${fit.left},${fit.top},${fit.right},${fit.bottom} intent_only"
                        }
                    }
                    trace("held_endpoint_fit_"+(if(anchored!=null)"content_anchor" else if(mainFit!=null)"main_coordinates" else if(covered!=null)"cover" else "contain")+
                        "_"+(if(incoming)"incoming" else "outgoing")+
                        "_crop="+fit.left+","+fit.top+","+fit.right+","+fit.bottom)
                }
                // Observe the existing matched destination transaction once
                // per card, with no new transaction, thread or frame query.
                // Do not retry registration on every unrelated Shell commit.
                if(coveredFlight && pairedRailPlan!=null && Build.VERSION.SDK_INT>=33) {
                    val armed=if(incoming) incomingRebaseReceiptArmed else outgoingRebaseReceiptArmed
                    val committed=if(incoming) incomingRebaseCommitted else outgoingRebaseCommitted
                    if(armed.compareAndSet(false,true)) runCatching {
                        transaction.addTransactionCommittedListener(Executor { it.run() }) {
                            if(!finished.get() && !hostDetached) committed.set(true)
                        }
                    }
                }
            }.onFailure { endpointWriteFailed.set(true) }
        }
        if(TaskSurfaceCompat.registerHeldMirrorWriter(writer)) heldWriter=writer
    }

    private fun disarmHeldEndpointWriter() {
        heldWriter?.let(TaskSurfaceCompat::unregisterHeldMirrorWriter)
        heldWriter=null
    }
    // Reuse one compositor transaction for the entire flight; avoid allocating
    // and closing a Binder-backed Transaction on every animation frame.
    private var frameTransaction: SurfaceControl.Transaction? = null
    private var sentMove = false
    private var moveComplete = false
    private var frameComplete = false
    private var handoffReady = false
    private var handoffTimedOut = false
    private var moveCompletedAt = -1L
    private var concurrentHandoff = false
    private var finishCallback: (() -> Unit)? = null
    private var lastProgress = 0f
    private var unchangedFrameSkips = 0
    private var nativeFrameCount = 0
    private var firstTransactionCommitted = false
    private var terminalTransactionCommitted = false
    private var unmaskedMove = false
    private var previousFrameAtMs = 0L
    private var biggestFrameGapMs = 0L
    private var gapsOver24Ms = 0
    private var maxSubmitNanos = 0L
    private var submitOver120HzBudget = 0
    private var submitOver60HzBudget = 0
    // Flight-local scalar counters; no per-frame trace strings or extra work queues.
    private var maxPrepareNanos = 0L
    private var maxApplyNanos = 0L
    private var prepareOver120HzBudget = 0
    private var applyOver120HzBudget = 0
    private var startedAtMs = 0L
    // Optional SystemUI-only API, resolved ONCE rather than per animation frame.
    private var cornersSupported = true
    private val cornerMethod = runCatching {
        SurfaceControl.Transaction::class.java.getDeclaredMethod(
            "setCornerRadius", SurfaceControl::class.java, Float::class.javaPrimitiveType,
        ).apply { isAccessible=true }
    }.getOrNull()
    private val background = object : View(host.context) {
        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(12, 14, 21)
        }
        private val clip = Path()
        override fun onDraw(canvas: Canvas) {
            val save = canvas.save()
            clip.reset()
            clip.addRect(slotStart.left,slotStart.top,slotStart.right,slotStart.bottom,Path.Direction.CW)
            clip.addRect(mainStart.left,mainStart.top,mainStart.right,mainStart.bottom,Path.Direction.CW)
            canvas.clipPath(clip)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),clearPaint)
            backdrop?.setBounds(0,0,width,height)
            backdrop?.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    val isRunning: Boolean get() = !finished.get() && finishCallback != null
    val description: String get() = "native_two_owned_task_mirrors_atomic_mask"

    /** Both views must be attached to a valid, hardware-composited host. */
    companion object {
        private const val MIN_DURATION_MS = 280L
        private const val MAX_DURATION_MS = 360L
        private const val HANDOFF_TIMEOUT_MS = 600L
        private const val MAX_LIFETIME_MS = 1800L
        private const val READY_REVEAL_MS = 96L
        // A short one-shot admission window; never run a hidden flight for its full duration.
        private const val FIRST_COMMIT_BUDGET_MS = 48L
        private const val ENDPOINT_GAP_PX = 2f
        // One bounded wait for the replacement real-task and slot buffers.
        // Shorter than the overall 1800ms watchdog; no idle polling.
        private const val CONCURRENT_HANDOFF_TIMEOUT_MS = 480L
        // Do not reparent actual app tasks while either reflected producer is
        // halfway across the display: ColorOS can replace the live buffer
        // mid-flight, reproducing the exact flash/snap seen in 3717.mp4.
        // The normal phased transfer begins at the *end* of visual travel.
        private const val MOVE_PROGRESS = .94f
        // AOSP has two mirror-root coordinate contracts (detached_mirror).
        // Read the actual read-only flag once, never set system flags or infer
        // from a manufacturer/model. An unreadable flag is not proof of retained
        // coordinates: refuse this route before acquiring any mirror or cover.
        private val mirrorRootObservation: StageMirrorRootObservation by lazy {
            var stage="class_lookup"
            try {
                val type=Class.forName("android.os.flagging.AconfigPackage")
                stage="load_method_lookup"
                val load=type.getMethod("load",String::class.java)
                stage="package_load"
                val pkg=load.invoke(null,"com.android.graphics.surfaceflinger.flags")
                    ?: error("null package")
                stage="read_method_lookup"
                val read=type.getMethod("getBooleanFlagValue",String::class.java,
                    Boolean::class.javaPrimitiveType)
                stage="read_default_false"
                val a=read.invoke(pkg,"detached_mirror",false) as? Boolean
                stage="read_default_true"
                val b=read.invoke(pkg,"detached_mirror",true) as? Boolean
                StageMirrorRootObservation.fromReads(a,b)
            } catch (error:Exception) {
                StageMirrorRootObservation.error(stage,error)
            } catch (error:LinkageError) {
                StageMirrorRootObservation.error(stage,error)
            }
        }
        // Exact, non-unique firmware build fingerprint digest (not a device
        // serial or user identifier). No model/brand/OS-family allowlist.
        // Only this private debug branch + existing active voluntary diagnostic
        // + exact captured firmware can run the evidence candidate.
        private const val EVIDENCE_TRIAL_FIRMWARE_SHA256 =
            "e5084857edcc3d0d5fa1b4b0cfb93d0d4f00ad19925ce94be86e2f47f134d24f"
        private val evidenceTrialFirmware by lazy {
            BuildConfig.DEBUG && BuildConfig.ATOMIC_NATIVE_SURFACE_TRIAL &&
                Build.VERSION.SDK_INT==37 && runCatching {
                    val digest=java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Build.FINGERPRINT.toByteArray(Charsets.UTF_8))
                    val hex=digest.joinToString("") { byte ->
                        (byte.toInt() and 255).toString(16).padStart(2,'0')
                    }
                    hex==EVIDENCE_TRIAL_FIRMWARE_SHA256
                }.getOrDefault(false)
        }
        @Volatile private var lastOutcome = "not_attempted"
        fun lastOutcome(): String = lastOutcome
        private fun markUnavailable(why: String): StageLiveSurfaceExchange? {
            lastOutcome = "fallback:" + why
            return null
        }

        private fun planPairedRail(
            from:StageLiveSurfaceFlightGeometry.Box,to:StageLiveSurfaceFlightGeometry.Box,
            incoming:StageLiveSurfaceFlightGeometry.Crop,outgoing:StageLiveSurfaceFlightGeometry.Crop,
            screen:StageLiveSurfaceFlightGeometry.Box,obstacles:List<StageLiveSurfaceFlightGeometry.Box>,
        ):SmoothPairedRailCandidate.Plan? = runCatching {
            PairedRailCandidate.plan(from,to,incoming.right-incoming.left,incoming.bottom-incoming.top,
                outgoing.right-outgoing.left,outgoing.bottom-outgoing.top,screen,obstacles)
                ?.let { SmoothPairedRailCandidate.smooth(it,obstacles) }
        }.getOrNull()

        fun create(
            host: FrameLayout,
            sourceSlotView: View,
            sourceTaskId: Int,
            sourceDisplayId: Int,
            mainTaskId: Int,
            sourceBounds: Rect,
            mainBounds: Rect,
            otherSlotBounds: List<Rect>,
            fullScreenWidth: Int,
            fullScreenHeight: Int,
            backdrop: Drawable?,
            wallpaperCoverFactory:(()->StageGpuMirrorOcclusion?)?=null,
        ): StageLiveSurfaceExchange? {
            val setupStarted=SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT < 31 || !host.isAttachedToWindow ||
                !sourceSlotView.isAttachedToWindow || !host.isHardwareAccelerated
            ) return markUnavailable("detached_or_software")
            if (sourceSlotView.rootView === host.rootView)
                return markUnavailable("single_window_root")
            // All animation coordinates are physical display pixels. Refuse
            // composition into an inset/clipped subwindow instead of quietly
            // drawing invisible motion and misreporting success to the user.
            val hostOrigin=IntArray(2)
            host.rootView.getLocationOnScreen(hostOrigin)
            if (hostOrigin[0] != 0 || hostOrigin[1] != 0 ||
                host.rootView.width < fullScreenWidth ||
                host.rootView.height < fullScreenHeight)
                return markUnavailable("host_not_full_display")
            val from = StageLiveSurfaceFlightGeometry.Box(
                sourceBounds.left.toFloat(),sourceBounds.top.toFloat(),
                sourceBounds.right.toFloat(),sourceBounds.bottom.toFloat(),
            )
            val to = StageLiveSurfaceFlightGeometry.Box(
                mainBounds.left.toFloat(),mainBounds.top.toFloat(),
                mainBounds.right.toFloat(),mainBounds.bottom.toFloat(),
            )
            if (!from.valid() || !to.valid() || fullScreenWidth < 2 || fullScreenHeight < 2)
                return markUnavailable("invalid_bounds")
            val observation=mirrorRootObservation
            val inEvidenceScope=evidenceTrialFirmware
            val diagnosticOptIn=inEvidenceScope &&
                io.github.x647368maker.atomicworkbench.logging.StageSurfaceHandoffTrace.evidence
                    .ticket(SystemClock.elapsedRealtime())!=null
            val contract=StageMirrorEvidenceTrial.resolve(observation,inEvidenceScope,diagnosticOptIn)
            val evidenceTrial=contract==StageMirrorRootContract.DETACHED &&
                observation.contract==StageMirrorRootContract.UNKNOWN
            StageExchangeTelemetry.nativeEvent("mirror_root_observation="+observation.diagnostic())
            if(inEvidenceScope) StageExchangeTelemetry.nativeEvent(
                "mirror_evidence_trial="+(if(evidenceTrial)"private_detached_candidate" else
                    if(observation.contract==StageMirrorRootContract.RETAINED)"contradictory_flag_rejected"
                    else if(diagnosticOptIn)"not_needed_or_rejected" else "diagnostic_not_armed"))
            val detached=contract.detachedOrNull() ?: run {
                StageExchangeTelemetry.nativeEvent("mirror_root=unknown")
                if(StageMirrorEvidenceTrial.awaitingDiagnostic(observation,inEvidenceScope,diagnosticOptIn))
                    return markUnavailable(StageMirrorEvidenceTrial.DIAGNOSTIC_REQUIRED)
                return markUnavailable("mirror_root_unknown")
            }
            val outgoingBefore=TaskSurfaceCompat.mirrorSourceStamp(mainTaskId,0)
                ?: return markUnavailable("main_source_frame_unsettled")
            val originalMain=TaskSurfaceCompat.ownedMainMirrorFrame(outgoingBefore)
                ?: return markUnavailable("main_source_frame_unsettled")
            val sourceCropLocal=originalMain.crop
            val firstMain=StageLiveSurfaceFlightGeometry.mainMirrorStart(
                to,StageLiveSurfaceFlightGeometry.Crop(sourceCropLocal.left,sourceCropLocal.top,
                    sourceCropLocal.right,sourceCropLocal.bottom),
                originalMain.scale,originalMain.x,originalMain.y,detached)
                ?: return markUnavailable("main_source_mapping_unrepresentable")
            val outgoingCrop=firstMain.crop
            // Vendor APIs can throw after the first handle has been acquired.
            // Every early return and constructor failure must release all
            // locally owned surfaces independently; never release task leashes.
            var ownedOutgoing:SurfaceControl?=null
            var ownedIncoming:SurfaceControl?=null
            var ownedMasks:StageGpuMirrorOcclusion?=null
            var transferred=false
            try {
                StageExchangeTelemetry.nativeEvent(
                    "mirror_root=${if(evidenceTrial) "detached_evidence_trial" else if(detached) "detached" else "retained"}")
                // The mirror must follow the app TASK, not the rail's ViewRoot.
                // That root switches the producer during the two-stage exchange,
                // which caused PR59 to show the new app twice while still flying.
                StageExchangeTelemetry.nativeEvent(
                    TaskSurfaceCompat.describeOwnedMainPresentation(mainTaskId))
                val incomingBefore=TaskSurfaceCompat.mirrorSourceStamp(sourceTaskId,sourceDisplayId)
                val outgoing = TaskSurfaceCompat.createOwnedMainMirror(mainTaskId)
                    ?: return markUnavailable("no_owned_main_task_mirror")
                ownedOutgoing=outgoing
                val slotMirror = TaskSurfaceCompat.createOwnedSlotTaskMirror(
                    sourceTaskId, sourceDisplayId,
                    allowCompletedDemotion=inEvidenceScope && diagnosticOptIn,
                ) ?: return markUnavailable("slot_task_leash_not_ready")
                val incoming = slotMirror.surface
                ownedIncoming=incoming
                val localCrop = Rect(slotMirror.crop)
                val obstacles = otherSlotBounds.map {
                    StageLiveSurfaceFlightGeometry.Box(
                        it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(),
                    )
                }
                if (!StageLiveSurfaceFlightGeometry.corridorAvoidsOtherSlots(
                        from, to, localCrop.width(), localCrop.height(), obstacles,
                        mainCropWidth=outgoingCrop.right-outgoingCrop.left,
                        mainCropHeight=outgoingCrop.bottom-outgoingCrop.top)) {
                    return markUnavailable("rail_corridor_blocked")
                }
                val incomingCrop = StageLiveSurfaceFlightGeometry.Crop(
                    localCrop.left, localCrop.top, localCrop.right, localCrop.bottom)
                val explicitPair=BuildConfig.ATOMIC_NATIVE_SURFACE_TRIAL && BuildConfig.ATOMIC_PAIRED_RAIL_TRIAL
                val explicitPlan=if(explicitPair) {
                    val began=SystemClock.elapsedRealtimeNanos()
                    planPairedRail(from,to,incomingCrop,outgoingCrop,StageLiveSurfaceFlightGeometry.Box(
                        0f,0f,fullScreenWidth.toFloat(),fullScreenHeight.toFloat()),obstacles).also { plan ->
                        StageExchangeTelemetry.nativeEvent("paired_prepare_us="+
                            (SystemClock.elapsedRealtimeNanos()-began)/1000L+" accepted="+(plan!=null))
                    }
                } else null
                // First preserve the established crop decision. The optional
                // certified route can improve the crossing without adding a
                // new crop mode or losing either visible endpoint.
                val oldCovered = explicitPlan==null && StageLiveSurfaceFlightGeometry.canUseCoveredFlight(
                    from, to, incomingCrop, outgoingCrop, obstacles)
                var adaptiveCovered=false
                val certifiedPlan=if(BuildConfig.ATOMIC_NATIVE_SURFACE_TRIAL && !explicitPair) {
                    val began=SystemClock.elapsedRealtimeNanos()
                    // Soft deadline: bound search at each small planning step,
                    // retain margin for source identity/occlusion and the
                    // existing outer 28ms total preparation admission.
                    val deadline=minOf(setupStarted+20_000_000L,began+4_000_000L)
                    runCatching {
                        val screen=StageLiveSurfaceFlightGeometry.Box(0f,0f,fullScreenWidth.toFloat(),fullScreenHeight.toFloat())
                        val withinBudget={SystemClock.elapsedRealtimeNanos()<deadline}
                        val direct = if (oldCovered) StageAdaptiveCoveredRailRoute.straightCrossing(
                            from, to, incomingCrop, outgoingCrop, screen, obstacles, withinBudget,
                        ) else null
                        val rail = if (oldCovered && direct == null) StageAdaptiveCoveredRailRoute.railCrossing(
                            from, to, incomingCrop, outgoingCrop, screen, obstacles, withinBudget,
                        ) else null
                        if (direct != null || rail != null) {
                            adaptiveCovered = true
                            StageExchangeTelemetry.nativeEvent(
                                if (direct != null) "paired_straight_crossing" else "paired_rail_crossing",
                            )
                        }
                        direct ?: rail ?: StageCertifiedRailRoute.choose(from,to,incomingCrop,outgoingCrop,screen,
                            obstacles,oldCovered,withinBudget) ?: if(oldCovered && withinBudget()) {
                            StageAdaptiveCoveredRailRoute.choose(from,to,incomingCrop,outgoingCrop,screen,
                                obstacles,withinBudget).also { adaptiveCovered=it!=null }
                        } else null
                    }.onFailure {
                        StageExchangeTelemetry.nativeEvent("certified_pair_planner_rejected")
                    }.getOrNull().also { plan ->
                        StageExchangeTelemetry.nativeEvent("certified_pair_us="+
                            (SystemClock.elapsedRealtimeNanos()-began)/1000L+" accepted="+(plan!=null))
                    }
                } else null
                val pairedPlan=explicitPlan ?: certifiedPlan
                val coveredFlight=oldCovered && (pairedPlan==null || adaptiveCovered)
                StageExchangeTelemetry.nativeEvent(
                    "mirror_crop_mode=" + when {
                        adaptiveCovered -> "paired_covered_adaptive"
                        certifiedPlan!=null -> "paired_contain_certified"
                        explicitPlan!=null -> "paired_contain_trial"
                        coveredFlight -> "covered_safe"
                        else -> "contain_reveal"
                    })
                if (!outgoing.isValid || !incoming.isValid) {
                    return markUnavailable("invalid_native_handles")
                }
                // Both mirrors must still refer to the observed task/leash pair.
                // Never animate a replacement task, or mistake a changing producer
                // for permanent OEM denial. Release before attaching any layer.
                val stableIncoming=incomingBefore?.takeIf {
                    it.sameSource(TaskSurfaceCompat.mirrorSourceStamp(sourceTaskId,sourceDisplayId))
                }
                val stableOutgoing=outgoingBefore?.takeIf {
                    it.sameSource(TaskSurfaceCompat.mirrorSourceStamp(mainTaskId,0))
                }
                if (stableIncoming==null || stableOutgoing==null ||
                    TaskSurfaceCompat.ownedMainMirrorFrame(stableOutgoing)!=originalMain) {
                    return markUnavailable("mirror_source_changed")
                }
                // Borrow the texture only after every source/geometry gate.
                // If a ready cache is absent or the vendor refuses a layer,
                // retain the original opaque safety cover; never expose duplicates.
                // A full-screen root may be larger than the physical
                // display. append() crops against the actual host dimensions,
                // so only borrow a physical texture when they match exactly.
                val textureFits=StageConcurrentNativeTrialPolicy.wallpaperHostMatches(
                    host.width,host.height,fullScreenWidth,fullScreenHeight)
                val textured=if(textureFits)
                    runCatching { wallpaperCoverFactory?.invoke() }.getOrNull() else null
                if(!textureFits && wallpaperCoverFactory!=null)
                    StageExchangeTelemetry.nativeEvent("wallpaper_cover_host_mismatch")
                val masks = textured ?: StageGpuMirrorOcclusion.create()
                    ?: return markUnavailable("atomic_occlusion_unavailable")
                StageExchangeTelemetry.nativeEvent("mirror_cover="+
                    if(textured!=null)"cached_wallpaper" else "solid_safe")
                ownedMasks=masks
                val exchange=StageLiveSurfaceExchange(
                    host, incoming, outgoing, localCrop,
                    outgoingCrop.toAndroidRect(),
                    from,to,backdrop,masks,coveredFlight,
                    stableIncoming,stableOutgoing,
                    detached,
                )
                exchange.pairedRailPlan=pairedPlan
                // Do not substitute a different start into a certified motion
                // envelope. Accept only if its existing first mapped content
                // is already equivalent to the owned task's exact transform.
                val plannedFirst=exchange.flightFrames(0f)?.second
                    ?: return markUnavailable("main_source_mapping_unrepresentable")
                if(!StageLiveSurfaceFlightGeometry.sameMirrorContent(firstMain,plannedFirst))
                    return markUnavailable("main_source_mapping_unrepresentable")
                exchange.mainSourceFrame=originalMain
                exchange.mainFirstFrame=firstMain
                // Phase 1 content-only experiment: same exact tested firmware,
                // private debug/native opt-in as #173. Outside this scope keep
                // the accepted coordinate-only implementation byte-for-byte.
                if(inEvidenceScope && diagnosticOptIn) {
                    val slotView=sourceSlotView as? io.github.x647368maker.atomicworkbench.display.TaskSwitcherView
                        ?: return markUnavailable("slot_content_mapping_unavailable")
                    val originalSlot=TaskSurfaceCompat.ownedSlotMirrorFrame(stableIncoming,allowCompletedDemotion=true)
                        ?: return markUnavailable("slot_content_mapping_unavailable")
                    if(originalSlot.crop!=localCrop)return markUnavailable("slot_content_mapping_unavailable")
                    val slotRect=slotView.mirrorLiveContentBounds()
                        ?: return markUnavailable("slot_content_mapping_unavailable")
                    val slotBox=StageLiveSurfaceFlightGeometry.Box(slotRect.left,slotRect.top,slotRect.right,slotRect.bottom)
                    val mapper=StageEndpointContentMapping
                    val firstSlotMap=mapper.slotMap(incomingCrop,slotBox)
                        ?: return markUnavailable("slot_content_mapping_unavailable")
                    val firstSlot=StageLiveSurfaceFlightGeometry.mainMirrorStart(slotBox,incomingCrop,
                        firstSlotMap.scale,firstSlotMap.x,firstSlotMap.y,true)
                        ?: return markUnavailable("slot_content_mapping_unavailable")
                    val rawFirst=exchange.rawFlightFrames(0f)
                        ?: return markUnavailable("content_anchor_unrepresentable")
                    if(!StageLiveSurfaceFlightGeometry.sameMirrorContent(firstSlot,rawFirst.first))
                        return markUnavailable("slot_content_mapping_unavailable")
                    val rawEnd=exchange.rawFlightFrames(1f)
                        ?: return markUnavailable("content_anchor_unrepresentable")
                    val mainMap=StageEndpointContentMapping.ContentMap(originalMain.scale,originalMain.x,originalMain.y)
                    val mainLocal=exchange.localCrop(originalMain)
                    val up=mapper.anchor(mapper.rootMap(originalSlot.scale,originalSlot.x,originalSlot.y,detached),
                        incomingCrop,incomingCrop,mainLocal,mainMap,to,firstSlot,rawFirst.first,rawEnd.first)
                        ?: return markUnavailable("content_anchor_unrepresentable")
                    val down=mapper.anchor(mapper.rootMap(originalMain.scale,originalMain.x,originalMain.y,detached),
                        outgoingCrop,mainLocal,incomingCrop,firstSlotMap,slotBox,firstMain,rawFirst.second,rawEnd.second)
                        ?: return markUnavailable("content_anchor_unrepresentable")
                    exchange.slotSourceFrame=originalSlot
                    exchange.slotSourceView=slotView
                    exchange.slotSourceTexture=slotView.textureView
                    exchange.slotSourceBounds=RectF(slotRect)
                    exchange.incomingAnchor=up
                    exchange.outgoingAnchor=down
                    StageExchangeTelemetry.nativeEvent("endpoint_content_anchor_trial=both")
                }
                exchange.traceMirrorAdmission(detached)
                // Transfer ownership only once the exchange object exists.
                lastOutcome="capable:two_owned_tasks_and_atomic_masks"
                transferred=true
                return exchange
            } catch(error:Exception) {
                runCatching { NeXtepLog.warn("native_swap","Native mirror acquisition failed",error) }
                return markUnavailable("native_acquisition_exception")
            } finally {
                if(!transferred) StageConcurrentNativeTrialPolicy.cleanupSteps(
                    { ownedMasks?.release() },
                    { ownedIncoming?.release() },
                    { ownedOutgoing?.release() },
                    onFailure={ NeXtepLog.warn("native_swap","Native acquisition cleanup failed",it) },
                )
            }
        }

        private fun createWindowMirror(view: View): SurfaceControl? = runCatching {
            val attached = view.rootSurfaceControl ?: return@runCatching null
            // Android 17/API37 exposes createMirror() on AttachedSurfaceControl.
            // On older ColorOS system UIs, try the existing reflected ViewRoot
            // control; failure disables this mode instead of mutating a task.
            val publicMirror=runCatching {
                attached.javaClass.methods.firstOrNull {
                    it.name=="createMirror" && it.parameterCount==0
                }?.invoke(attached) as? SurfaceControl
            }.getOrNull()
            if (publicMirror?.isValid == true) return@runCatching publicMirror
            publicMirror?.release()
            val getRoot = View::class.java.getDeclaredMethod("getViewRootImpl")
                .apply { isAccessible=true }
            val root=getRoot.invoke(view) ?: return@runCatching null
            val field=generateSequence(root.javaClass as Class<*>?) { it.superclass }
                .mapNotNull { klass ->
                    runCatching { klass.getDeclaredField("mSurfaceControl") }.getOrNull()
                }.firstOrNull() ?: return@runCatching null
            val surface=field.apply { isAccessible=true }.get(root) as? SurfaceControl
                ?: return@runCatching null
            if (!surface.isValid) return@runCatching null
            TaskSurfaceCompat.mirrorObservedSurface(surface)
        }.onFailure { NeXtepLog.warn("native_swap", "ViewRoot live mirror not permitted", it) }.getOrNull()
    }

    /**
     * Returns false before task mutation if WM denies either mirror reparent.
     * Once running, caller's normal coordinator owns rollback on task failures.
     */
    fun start(
        durationMs: Int,
        moveAsync: (((Boolean) -> Unit) -> Unit)?,
        move: () -> Boolean,
        liveReady: () -> Boolean,
        canRelease: () -> Boolean,
        onFinished: () -> Unit,
        immediateMove: Boolean = false,
        onVisualUnavailable: ((String) -> Boolean)? = null,
    ): Boolean {
        if (finishCallback != null || finished.get()) return false
        concurrentHandoff = immediateMove
        if(!mainSourceStillMatches() || !slotSourceStillMatches() ||
            (incomingAnchor!=null && !concurrentHandoff)) {
            finished.set(true)
            cleanupWithoutCallback()
            return false
        }
        val finalIncoming = StageLiveSurfaceFlightGeometry.mirrorFrame(
            slotStart, mainStart, incomingGeometryCrop, 1f, coveredFlight)
        val finalOutgoing = StageLiveSurfaceFlightGeometry.mirrorFrame(
            mainStart, slotStart, outgoingGeometryCrop, 1f, coveredFlight)
        if (finalIncoming == null || finalOutgoing == null) {
            finished.set(true)
            cleanupWithoutCallback()
            return false
        }
        val incomingGap = StageLiveSurfaceFlightGeometry.endpointGapPx(mainStart, finalIncoming.box)
        val outgoingGap = StageLiveSurfaceFlightGeometry.endpointGapPx(slotStart, finalOutgoing.box)
        // Keep the historical reveal policy and original motion route. This
        // compatibility metric is not evidence of a continuous content map.
        endpointGap = maxOf(incomingGap, outgoingGap)
        trace("endpoint_gap_px up=${incomingGap.toInt()} down=${outgoingGap.toInt()}" +
            " up_fit=${finalIncoming.box.width.toInt()}x${finalIncoming.box.height.toInt()}" +
            " down_fit=${finalOutgoing.box.width.toInt()}x${finalOutgoing.box.height.toInt()}" +
            if(incomingAnchor!=null)" route_only" else "")
        if(incomingAnchor!=null) {
            val up=checkNotNull(incomingAnchor)
            val down=checkNotNull(outgoingAnchor)
            val diag=io.github.x647368maker.atomicworkbench.logging.StageSurfaceHandoffTrace
            diag.detail(traceAttemptId,"content_domain_gap") {
                val u=StageLiveSurfaceFlightGeometry.endpointGapPx(mainStart,up.terminal.box)
                val d=StageLiveSurfaceFlightGeometry.endpointGapPx(slotStart,down.terminal.box)
                "content_domain_gap up=$u down=$d common_crop_only buffer_latch_unobserved"
            }
        }
        startedAtMs = SystemClock.uptimeMillis()
        pairedRailPlan?.let {
            trace((if(coveredFlight)"paired_covered_adaptive" else "paired_contain_trial")+" bound="+it.bound+" trim="+it.trim)
        }
        trace(
            "mirror_start crop=${sourceCrop.width()}x${sourceCrop.height()}" +
            " main=${mainStart.width.toInt()}x${mainStart.height.toInt()}")
        finishCallback = onFinished
        // An executor queued on the UI thread cannot distinguish an absent
        // compositor receipt from a blocked UI dispatch. Record the actual
        // callback arrival once, without touching Views or transaction state
        // on the callback thread. Only the UI thread owns admission or move.
        val firstReceiptAt=AtomicLong(-1L)
        var firstSubmitAt=-1L
        val receiptExecutor=Executor { command -> command.run() }
        fun receiptInBudget():Boolean = StageConcurrentNativeTrialPolicy.firstReceiptWithinBudget(
            firstSubmitAt,firstReceiptAt.get(),FIRST_COMMIT_BUDGET_MS)
        try {
            check(host.isAttachedToWindow && !hostDetached)
            host.addOnAttachStateChangeListener(attachmentListener)
            val root = host.rootSurfaceControl ?: error("Host surface detached before native flight")
            frameTransaction = SurfaceControl.Transaction()
            val tx = root.buildReparentTransaction(sourceMirror)
                ?: error("No host transaction for incoming live rail")
            try {
                val other = root.buildReparentTransaction(mainMirror)
                    ?: error("No host transaction for outgoing live task")
                try { tx.merge(other) } finally { other.close() }
                // Mask both stationary producer regions in the SAME GPU
                // transaction as the task mirrors; never allow a third live
                // original card to peek through in the middle of flight.
                occlusion.append(
                    root, tx,
                    Rect(slotStart.left.toInt(),slotStart.top.toInt(),
                        slotStart.right.toInt(),slotStart.bottom.toInt()),
                    Rect(mainStart.left.toInt(),mainStart.top.toInt(),
                        mainStart.right.toInt(),mainStart.bottom.toInt()),
                    host.width, host.height,
                )
                tx.setLayer(sourceMirror,StageGpuCoverPolicy.SOURCE_MIRROR_LAYER)
                tx.setLayer(mainMirror,StageGpuCoverPolicy.MAIN_MIRROR_LAYER)
                tx.setCrop(sourceMirror,sourceCrop)
                tx.setCrop(mainMirror,mainCrop)
                tx.setVisibility(sourceMirror,true)
                tx.setVisibility(mainMirror,true)
                applyFrame(tx,0f)
                if (Build.VERSION.SDK_INT >= 33) {
                    // A committed transaction is proof that SurfaceFlinger
                    // accepted both layer parents, NOT proof of visible pixels.
                    if(concurrentHandoff) {
                        tx.addTransactionCommittedListener(receiptExecutor) {
                            val received=SystemClock.uptimeMillis()
                            if (!finished.get() && !hostDetached &&
                                firstReceiptAt.compareAndSet(-1L,received)) {
                                ui.post {
                                    if(isRunning && !hostDetached && !sentMove && receiptInBudget()) {
                                        pendingFirstFlight?.let { pending ->
                                            ui.removeCallbacks(pending)
                                            pending.run()
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        tx.addTransactionCommittedListener(host.context.mainExecutor) {
                            if (finished.get() || hostDetached) return@addTransactionCommittedListener
                            firstTransactionCommitted = true
                            trace(
                                "first_gpu_transaction_committed@${SystemClock.uptimeMillis()-startedAtMs}ms")
                            NeXtepLog.info("native_swap", "Two real compositor mirror layers accepted in SurfaceFlinger")
                        }
                    }
                }
                firstSubmitAt=SystemClock.uptimeMillis()
                tx.apply()
            } finally { tx.close() }
        } catch (error: Throwable) {
            NeXtepLog.warn("native_swap", "Native mirror parenting failed before task move", error)
            finishCallback = null
            finished.set(true)
            cleanupWithoutCallback()
            return false
        }

        fun sourceIdentityReadyNow():Boolean = runCatching {
            StageConcurrentNativeTrialPolicy.sourceIdentityReady(
                host.isAttachedToWindow && !hostDetached,
                sourceMirror.isValid && mainMirror.isValid,
                { incomingStamp.sameSource(TaskSurfaceCompat.mirrorSourceStamp(
                    incomingStamp.taskId,incomingStamp.displayId)) && slotSourceStillMatches() },
                { outgoingStamp.sameSource(TaskSurfaceCompat.mirrorSourceStamp(
                    outgoingStamp.taskId,outgoingStamp.displayId)) && mainSourceStillMatches() })
        }.getOrElse {
            runCatching { NeXtepLog.warn("native_swap","Source preflight failed",it) }
            false
        }

        fun fallbackBeforeFirstFrame(reason:String):Boolean {
            if(onVisualUnavailable==null)return false
            val eligible=StageConcurrentNativeTrialPolicy.canFallbackBeforeFirstGpuFrame(
                isRunning,host.isAttachedToWindow && !hostDetached,
                sourceIdentityReadyNow(),sentMove || moveComplete,nativeFrameCount)
            if(!eligible)return false
            val finish=terminate(false,"fallback:$reason") ?: return false
            trace("native_visual_fallback:$reason")
            val accepted=runCatching { onVisualUnavailable.invoke(reason) }.getOrDefault(false)
            if(accepted)return true
            // No visual backend accepted the still-owned tap. Keep the existing
            // coordinator as the sole migration owner and finish after its ACK.
            val claimed=AtomicBoolean(false)
            val deadline=Runnable {
                if(claimed.compareAndSet(false,true)) runCatching { finish() }
            }
            if(moveAsync!=null) {
                ui.postDelayed(deadline,MAX_LIFETIME_MS)
                runCatching {
                    moveAsync { _ ->
                        if(claimed.compareAndSet(false,true)) ui.post {
                            ui.removeCallbacks(deadline)
                            runCatching { finish() }
                        }
                    }
                }.onFailure {
                    if(claimed.compareAndSet(false,true)) {
                        ui.removeCallbacks(deadline)
                        runCatching { finish() }
                    }
                }
            } else {
                runCatching(move)
                runCatching { finish() }
            }
            return true
        }

        // Terminal compositor receipt is recorded on arrival, independently
        // of a congested UI queue. Only the UI lane applies the result.
        val terminalReceiptAt=AtomicLong(-1L)
        var terminalSubmitAt=-1L
        fun confirmTerminalReceipt():Boolean {
            if(!concurrentHandoff || !frameComplete || !isRunning || sentMove || hostDetached)return false
            if(!StageConcurrentNativeTrialPolicy.terminalReceiptWithinBudget(
                    terminalSubmitAt,terminalReceiptAt.get()))return false
            if(!terminalTransactionCommitted) {
                terminalTransactionCommitted=true
                trace("native_terminal_transaction_committed")
                trace("terminal_receipt_ms="+(terminalReceiptAt.get()-terminalSubmitAt)+
                    " ui_dispatch_ms="+(SystemClock.uptimeMillis()-terminalReceiptAt.get()))
            }
            return true
        }
        fun commitMove() {
            if (!isRunning || sentMove) return
            confirmTerminalReceipt()
            if(hostDetached || !host.isAttachedToWindow){stop();return}
            // A visible concurrent flight must reach a confirmed endpoint before
            // either producer can be reparented. Retire the attempt through the
            // coordinator's existing rollback instead of exposing a naked move.
            if(StageConcurrentNativeTrialPolicy.rejectUnconfirmedVisibleMove(concurrentHandoff,
                    firstTransactionCommitted,terminalTransactionCommitted)) {
                trace("native_move_rejected:terminal_unconfirmed")
                stop()
                return
            }
            // Recheck once immediately before the real migration. Observed
            // task stamps use local cached state, not a synchronous task query.
            // A rejected preflight leaves task rollback/reconciliation to the
            // existing exchange coordinator and runs normal visual cleanup.
            val sourceReady=sourceIdentityReadyNow()
            if (!sourceReady) {
                trace("native_move_rejected:source_changed_or_detached")
                stop()
                return
            }
            // A missing compositor acknowledgement is not permission to
            // hold an unproven animated endpoint over the real task. Remove
            // both mirrors and covers in one existing transaction, then
            // proceed once with the ordinary task exchange. If hiding itself
            // fails, stop before any real task mutation.
            val presentation=StageConcurrentNativeTrialPolicy.movePresentation(
                firstTransactionCommitted,concurrentHandoff,terminalTransactionCommitted)
            if(presentation==StageConcurrentNativeTrialPolicy.MovePresentation.UNMASKED) {
                val hidden=runCatching {
                    val tx=frameTransaction ?: error("No native transaction")
                    tx.setVisibility(sourceMirror,false)
                    tx.setVisibility(mainMirror,false)
                    occlusion.hide(tx)
                    tx.apply()
                }.onFailure { NeXtepLog.warn("native_swap","Unconfirmed flight could not be hidden",it) }.isSuccess
                if(!hidden){stop();return}
                traceCoverHideSubmitted("unmasked")
                unmaskedMove=true
                trace("native_unmasked_fallback:first=$firstTransactionCommitted terminal=$terminalTransactionCommitted")
            }
            if (concurrentHandoff && frameComplete && !unmaskedMove) {
                armHeldEndpointWriter()
                if(incomingAnchor!=null && heldWriter==null) {
                    trace("native_move_rejected:content_writer_unavailable")
                    stop()
                    return
                }
            }
            sentMove = true
            trace(
                "real_move_requested@${SystemClock.uptimeMillis()-startedAtMs}ms frames=$nativeFrameCount")
            runCatching { trace("move_tasks up=${incomingStamp.taskId} down=${outgoingStamp.taskId}") }
            if (moveAsync != null) {
                // First callback owns the result, even if the provider throws
                // after calling it synchronously. Do not discard a verified
                // migration or allow a second callback to retract it.
                val ackClaimed=AtomicBoolean(false)
                runCatching {
                    moveAsync { ok ->
                        if(ackClaimed.compareAndSet(false,true)) StageAckDispatch.dispatch(
                            Looper.myLooper()==ui.looper, { action -> ui.post(action) }, Runnable {
                            if (!isRunning || moveComplete) return@Runnable
                            if (hostDetached || !host.isAttachedToWindow || !ok) { stop(); return@Runnable }
                            moveComplete = true
                            moveCompletedAt = SystemClock.uptimeMillis()
                            trace(
                                "real_move_ack@${moveCompletedAt-startedAtMs}ms")
                            if(unmaskedMove)stop() else finishIfReady(liveReady,canRelease)
                        })
                    }
                }.onFailure {
                    if(ackClaimed.compareAndSet(false,true)) {
                        runCatching { NeXtepLog.warn("native_swap", "Real phased move failed",it) }
                        stop()
                    } else runCatching { trace("native_move_exception_after_callback") }
                }
            } else {
                val ok=runCatching(move).getOrDefault(false)
                if (!ok) { stop(); return }
                moveComplete=true
                moveCompletedAt=SystemClock.uptimeMillis()
                trace(
                    "sync_move_ack@${moveCompletedAt-startedAtMs}ms")
                if(unmaskedMove)stop() else finishIfReady(liveReady,canRelease)
            }
        }
        // One-shot cleanup if a device sleeps/locks between frame callback
        // and move acknowledgment. No per-frame background polling.
        ui.postDelayed(lifetimeWatchdog,MAX_LIFETIME_MS)
        val flightDuration=if (immediateMove) durationMs.toLong().coerceIn(320L,380L)
            else durationMs.toLong().coerceIn(MIN_DURATION_MS,MAX_DURATION_MS)
        fun submitFlightFrame(fraction: Float,terminal:Boolean=false): Boolean {
            if (!isRunning) return false
            if(hostDetached || !host.isAttachedToWindow){stop();return false}
            if(StageConcurrentNativeTrialPolicy.unchangedFlightFrame(
                    concurrentHandoff,firstTransactionCommitted,terminal,lastProgress,fraction)) {
                // The initial transaction has already committed progress 0.
                // Later exact duplicates also need no new compositor submit.
                // Do not count skipped callbacks as actual transaction frames.
                unchangedFrameSkips++
                return true
            }
            return runCatching {
                val submitStarted=SystemClock.elapsedRealtimeNanos()
                val frame=frameTransaction ?: error("Flight transaction is unavailable")
                applyFrame(frame,fraction)
                // Register before submitting the exact terminal geometry.
                // Capture the one-shot receipt before dispatch to the UI.
                // A late UI callback must neither discard a timely receipt
                // nor turn an actually late one into an animated handoff.
                if(terminal && concurrentHandoff && Build.VERSION.SDK_INT>=33) {
                    runCatching {
                        frame.addTransactionCommittedListener(receiptExecutor) {
                            if(terminalReceiptAt.compareAndSet(-1L,SystemClock.uptimeMillis())) {
                                ui.post {
                                    if(confirmTerminalReceipt())deferredMove?.let { pending ->
                                        ui.removeCallbacks(pending)
                                        ui.post(pending)
                                    }
                                }
                            }
                        }
                    }.onFailure { trace("native_terminal_commit_callback_unavailable") }
                }
                // Separate geometry/transaction setters (and terminal listener setup)
                // from apply() itself. Neither duration implies that pixels were presented.
                val applyStarted=SystemClock.elapsedRealtimeNanos()
                if(terminal && concurrentHandoff)terminalSubmitAt=SystemClock.uptimeMillis()
                frame.apply()
                val submittedAt=SystemClock.elapsedRealtimeNanos()
                val submitNanos=submittedAt-submitStarted
                val prepareNanos=applyStarted-submitStarted
                val applyNanos=submittedAt-applyStarted
                if(prepareNanos>maxPrepareNanos)maxPrepareNanos=prepareNanos
                if(applyNanos>maxApplyNanos)maxApplyNanos=applyNanos
                if(prepareNanos>8_333_333L)prepareOver120HzBudget++
                if(applyNanos>8_333_333L)applyOver120HzBudget++
                if(submitNanos>maxSubmitNanos)maxSubmitNanos=submitNanos
                if(submitNanos>8_333_333L)submitOver120HzBudget++
                if(submitNanos>16_666_667L)submitOver60HzBudget++
                lastProgress=fraction
                val now=SystemClock.uptimeMillis()
                if(previousFrameAtMs>0L) {
                    val gap=now-previousFrameAtMs
                    if(gap>biggestFrameGapMs)biggestFrameGapMs=gap
                    if(gap>24L)gapsOver24Ms++
                }
                previousFrameAtMs=now
                nativeFrameCount++
                true
            }.getOrElse { error ->
                NeXtepLog.warn("native_swap","Real dual-layer frame failed",error)
                if(!fallbackBeforeFirstFrame("frame_submit_failed")) {
                    if(isRunning && !sentMove) commitMove() else stop()
                }
                false
            }
        }
        fun completeFlight(reason: String) {
            if (!isRunning || frameComplete) return
            // Force the exact final geometry in one existing dual-mirror
            // transaction BEFORE mutating either live task producer.
            // Transaction.apply is submission, not proof of visible pixels.
            if (!submitFlightFrame(1f,terminal=true)) return
            frameComplete=true
            confirmTerminalReceipt()
            trace("flight_complete:$reason@${SystemClock.uptimeMillis()-startedAtMs}ms")
            flightDeadline?.let { ui.removeCallbacks(it) }
            flightDeadline=null
            if (!sentMove) {
                // Leave the animator update/listener stack before the real
                // move performs task Binder work. At most one deferred move.
                val movePost=Runnable {
                    deferredMove=null
                    if (isRunning) commitMove()
                }
                deferredMove=movePost
                if(concurrentHandoff && Build.VERSION.SDK_INT>=33 && !terminalTransactionCommitted)
                    ui.postAtTime(movePost,terminalSubmitAt+
                        StageConcurrentNativeTrialPolicy.TERMINAL_COMMIT_BUDGET_MS)
                else ui.post(movePost)
            } else finishIfReady(liveReady,canRelease)
        }
        // Independent one-shot fallback for missing terminal update/listener.
        // During a real flight only; cancelled on completion or cleanup.
        val deadline=Runnable { completeFlight("deadline") }
        val flightAnimator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=flightDuration
            // Geometry supplies the C2 ease for each physical phase. Use a
            // single uniform clock in the concurrent experimental flight,
            // not an additional global ease that compresses the second phase.
            interpolator=if(immediateMove) android.view.animation.LinearInterpolator()
                else PathInterpolator(.4f,0f,.2f,1f)
            addUpdateListener {
                if (!isRunning || frameComplete) return@addUpdateListener
                val fraction=it.animatedValue as Float
                if (immediateMove && StageConcurrentNativeTrialPolicy.terminalFrameDue(
                        it.currentPlayTime,it.duration,fraction,firstTransactionCommitted,nativeFrameCount)) {
                    completeFlight("terminal_update")
                    return@addUpdateListener
                }
                if (!submitFlightFrame(fraction)) return@addUpdateListener
                if (!immediateMove &&
                    it.currentPlayTime >= (it.duration*MOVE_PROGRESS).toLong() &&
                    firstTransactionCommitted && !sentMove) commitMove()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    completeFlight("animator_end")
                }
            })
        }
        // Own the animator before any start or synchronous callback. In the
        // experimental route, start its clock only after the initial dual-root
        // transaction commits. An ACK is admission, not proof of visible pixels.
        animator=flightAnimator
        val beginFlight=Runnable {
            pendingFirstFlight=null
            if(!isRunning)return@Runnable
            if(hostDetached || !host.isAttachedToWindow){stop();return@Runnable}
            if(concurrentHandoff) {
                // Both ACK and deadline paths enter this same one-shot main
                // action. A late callback must not resurrect a retired flight.
                val received=firstReceiptAt.get()
                val now=SystemClock.uptimeMillis()
                firstTransactionCommitted=StageConcurrentNativeTrialPolicy.firstReceiptWithinBudget(
                    firstSubmitAt,received,FIRST_COMMIT_BUDGET_MS)
                trace("first_commit_receipt_ms="+
                    (if(received>=0L) received-firstSubmitAt else -1L)+
                    " ui_dispatch_ms="+(now-firstSubmitAt)+
                    " budget_ms="+FIRST_COMMIT_BUDGET_MS+
                    " accepted="+firstTransactionCommitted)
                if(firstTransactionCommitted) {
                    trace("first_gpu_transaction_committed@${received-startedAtMs}ms")
                }
            }
            if(concurrentHandoff && !firstTransactionCommitted) {
                trace("native_first_commit_timeout:visual_fallback_then_unmasked_move")
                if(!fallbackBeforeFirstFrame("first_commit_timeout")) commitMove()
                return@Runnable
            }
            flightDeadline=deadline
            ui.postDelayed(deadline,StageConcurrentNativeTrialPolicy.terminalDeadlineDelay(
                flightDuration,ValueAnimator.getDurationScale()))
            runCatching { flightAnimator.start() }.onFailure {
                runCatching { NeXtepLog.warn("native_swap","Animation clock start failed",it) }
                if(!fallbackBeforeFirstFrame("animator_start_failed")) {
                    if(isRunning && !sentMove) commitMove() else stop()
                }
            }
        }
        // PR58: ColorOS can replace a mirrored producer's content as soon as
        // task migration begins. Keep almost all card travel visible BEFORE
        // owner changes; then hold endpoint overlays until the new buffers
        // settle, with a bounded timeout. If compositor commit never arrives,
        // fail open promptly and preserve the old reliable task swap.
        // No bitmap, second WindowManager root, or persistent background work.
        lastOutcome=if (immediateMove) "active:lead_in_concurrent_gpu_mirrors"
            else "active:two_live_gpu_mirrors"
        NeXtepLog.info("native_swap","GPU mirrors submitted; pixel visibility not yet proven")
        if(concurrentHandoff) {
            pendingFirstFlight=beginFlight
            // The absolute receipt deadline is measured from submission,
            // not from the eventual main-thread callback or timer dispatch.
            // Always queue UI admission until start() has returned; the
            // owning controller must get a chance to publish this flight.
            val wakeAt=if(receiptInBudget())SystemClock.uptimeMillis()
                else firstSubmitAt+FIRST_COMMIT_BUDGET_MS
            if(!ui.postAtTime(beginFlight,wakeAt))stop()
        } else beginFlight.run()
        return true
    }

    private fun pairFrame(box:StageLiveSurfaceFlightGeometry.Box,crop:StageLiveSurfaceFlightGeometry.Crop):
        StageLiveSurfaceFlightGeometry.MirrorFrame {
        val w=crop.right-crop.left;val h=crop.bottom-crop.top
        val scale=minOf(box.width/w,box.height/h)
        check(scale.isFinite() && scale>0f && scale<=10f)
        val bw=w*scale;val bh=h*scale
        val fit=StageLiveSurfaceFlightGeometry.Box(box.cx-bw*.5f,box.cy-bh*.5f,box.cx+bw*.5f,box.cy+bh*.5f)
        return StageLiveSurfaceFlightGeometry.MirrorFrame(scale,
            StageLiveSurfaceFlightGeometry.mirrorPosition(fit.left,crop.left,scale),
            StageLiveSurfaceFlightGeometry.mirrorPosition(fit.top,crop.top,scale),crop,fit)
    }
    private fun rawFlightFrames(p:Float):Pair<StageLiveSurfaceFlightGeometry.MirrorFrame,StageLiveSurfaceFlightGeometry.MirrorFrame>? {
        val paired=pairedRailPlan?.at(p)
        // At p=0 keep the original complete source; at every later frame
        // the planned viewport is independent of the uniformly scaled crop.
        val incoming=(if(paired!=null && coveredFlight && p>0f)
            StageAdaptiveCoveredRailRoute.frame(paired.incoming,incomingGeometryCrop)
        else if(paired!=null && !coveredFlight)pairFrame(paired.incoming,incomingGeometryCrop)
        else StageLiveSurfaceFlightGeometry.mirrorFrame(
            slotStart,mainStart,incomingGeometryCrop,p,coveredFlight,
        )) ?: return null
        val outgoing=(if(paired!=null && coveredFlight && p>0f)
            StageAdaptiveCoveredRailRoute.frame(paired.outgoing,outgoingGeometryCrop)
        else if(paired!=null && !coveredFlight)pairFrame(paired.outgoing,outgoingGeometryCrop)
        else StageLiveSurfaceFlightGeometry.mirrorFrame(
            mainStart,slotStart,outgoingGeometryCrop,p,coveredFlight,
        )) ?: return null
        return incoming to outgoing
    }

    private fun flightFrames(p:Float):Pair<StageLiveSurfaceFlightGeometry.MirrorFrame,StageLiveSurfaceFlightGeometry.MirrorFrame>? {
        val raw=rawFlightFrames(p) ?: return null
        val up=incomingAnchor ?: return raw
        val down=outgoingAnchor ?: return null
        val first=StageEndpointContentMapping.flight(raw.first,up,p) ?: return null
        val second=StageEndpointContentMapping.flight(raw.second,down,p) ?: return null
        return first to second
    }

    private fun applyFrame(transaction: SurfaceControl.Transaction, p: Float) {
        val (incoming,plannedOutgoing)=flightFrames(p) ?: error("Unsafe GPU rail path")
        val outgoing=if(p==0f) mainFirstFrame ?: plannedOutgoing else plannedOutgoing
        // Both task mirrors are updated by the same GPU transaction. Cover
        // mode changes only their transient crop, never original task bounds.
        if (coveredFlight || incomingAnchor!=null) {
            transaction.setCrop(sourceMirror,incoming.crop.toAndroidRect())
            transaction.setCrop(mainMirror,outgoing.crop.toAndroidRect())
        }
        transaction.setScale(sourceMirror,incoming.scale,incoming.scale)
        transaction.setPosition(sourceMirror,incoming.x,incoming.y)
        transaction.setScale(mainMirror,outgoing.scale,outgoing.scale)
        transaction.setPosition(mainMirror,outgoing.x,outgoing.y)
        if(p==0f || p==1f) {
            val diag=io.github.x647368maker.atomicworkbench.logging.StageSurfaceHandoffTrace
            val phase=if(p==0f)"initial" else "terminal"
            diag.detail(traceAttemptId,"flight_up_$phase") {
                "flight_up_$phase task=${incomingStamp.taskId} s=${incoming.scale} xy=${incoming.x},${incoming.y} crop=${incoming.crop}"
            }
            diag.detail(traceAttemptId,"flight_down_$phase") {
                "flight_down_$phase task=${outgoingStamp.taskId} s=${outgoing.scale} xy=${outgoing.x},${outgoing.y} crop=${outgoing.crop}"
            }
            if(p==1f && incomingAnchor!=null) {
                val up=checkNotNull(incomingAnchor)
                val down=checkNotNull(outgoingAnchor)
                val m=StageEndpointContentMapping
                val u=m.compose(StageEndpointContentMapping.ContentMap(incoming.scale,incoming.x,incoming.y),up.sourceRoot)
                val d=m.compose(StageEndpointContentMapping.ContentMap(outgoing.scale,outgoing.x,outgoing.y),down.sourceRoot)
                diag.detail(traceAttemptId,"content_terminal") {
                    "content_terminal up=${u.scale},${u.x},${u.y} down=${d.scale},${d.x},${d.y} " +
                        "delta_px=${m.mapError(up.target,u,up.commonLocal)}/${m.mapError(down.target,d,down.commonLocal)} intent_only"
                }
            }
        }
        // Preserve the previously accepted 24dp-equivalent round cards while
        // their compositor scale changes. A parent layer's corner radius uses
        // source-layer units, hence divide by the current uniform scale.
        val setter=cornerMethod
        if (cornersSupported && setter != null) {
            val d=host.resources.displayMetrics.density
            val sourceRadius=(24f*d / incoming.scale).coerceIn(0f,120f*d)
            val mainRadius=(24f*d / outgoing.scale).coerceIn(0f,120f*d)
            runCatching {
                setter.invoke(transaction,sourceMirror,sourceRadius)
                setter.invoke(transaction,mainMirror,mainRadius)
            }.onFailure {
                cornersSupported=false
                NeXtepLog.warn("native_swap","OEM refused mirrored-card corner rounding",it)
            }
        }
    }

    private fun finishIfReady(liveReady: () -> Boolean, canRelease: () -> Boolean) {
        // Task migration may detach/reparent the old host. View.postOnAnimation
        // can queue work until attachment; it is NOT a reliable release timer.
        // Arm from the real move ACK even if an animator-end callback was lost.
        if (!isRunning || !moveComplete || handoffCheckStarted) return
        handoffCheckStarted = true
        val timeout=if (concurrentHandoff) CONCURRENT_HANDOFF_TIMEOUT_MS
            else HANDOFF_TIMEOUT_MS
        val deadline=moveCompletedAt + timeout
        trace("handoff_check_begin frame=$frameComplete host_attached=${host.isAttachedToWindow}")
        val check = object : Runnable {
            override fun run() {
                if (!isRunning) return
                if(hostDetached || !host.isAttachedToWindow){stop();return}
                val now=SystemClock.uptimeMillis()
                val releasable=frameComplete && runCatching { canRelease() }.getOrDefault(false)
                val live=releasable && runCatching { liveReady() }.getOrDefault(false)
                val contentRead=if(live && incomingAnchor!=null)
                    runCatching { destinationContentRead("ready") }
                        .getOrDefault(StageEndpointContentMapping.DestinationRead.REJECTED)
                    else StageEndpointContentMapping.DestinationRead.READY
                val reason=when {
                    !frameComplete -> "flight_end_missing"
                    !releasable -> "session_unsettled"
                    !live -> "target_not_ready"
                    contentRead==StageEndpointContentMapping.DestinationRead.PENDING -> "content_geometry_pending"
                    contentRead==StageEndpointContentMapping.DestinationRead.REJECTED -> "content_geometry_rejected"
                    else -> "ready"
                }
                if (reason != lastHandoffWait) {
                    lastHandoffWait=reason
                    trace("handoff_state=$reason host_attached=${host.isAttachedToWindow}")
                }
                when (StageEndpointContentMapping.destinationHandoff(
                    now-moveCompletedAt,timeout,frameComplete,releasable,live,contentRead)) {
                    StageEndpointContentMapping.DestinationHandoff.REJECTED -> {
                        trace("content_target_geometry_rejected")
                        stop()
                    }
                    StageEndpointContentMapping.DestinationHandoff.WAIT ->
                        ui.postAtTime(this,StageConcurrentNativeTrialPolicy.nextHandoffCheck(
                            SystemClock.uptimeMillis(),deadline))
                    StageEndpointContentMapping.DestinationHandoff.READY -> {
                        trace("handoff_target_ready@${now-startedAtMs}ms")
                        val gapReveal=endpointGap > ENDPOINT_GAP_PX && firstTransactionCommitted
                        // Identical outer boxes can still hide a crop/content
                        // discontinuity at the adaptive endpoint. Only blend
                        // when both matching target-transform receipts exist.
                        // Unsupported OEM receipts never cause extra waiting.
                        val adaptiveBlend=StageConcurrentNativeTrialPolicy.adaptiveHandoffBlend(
                            concurrentHandoff && coveredFlight && pairedRailPlan!=null,
                            firstTransactionCommitted,incomingRebaseCommitted.get(),
                            outgoingRebaseCommitted.get(),endpointWriteFailed.get(),
                            deadline-now,ValueAnimator.getDurationScale())
                        if (gapReveal || adaptiveBlend) {
                            val duration=if(gapReveal) READY_REVEAL_MS
                                else StageConcurrentNativeTrialPolicy.ADAPTIVE_HANDOFF_REVEAL_MS
                            trace(if(gapReveal) "handoff_reveal_gap" else "handoff_reveal_adaptive_zero_gap")
                            revealReadyDestination(liveReady,canRelease,duration)
                        } else {
                            handoffReady=true
                            stop()
                        }
                    }
                    StageEndpointContentMapping.DestinationHandoff.EXPIRED -> {
                        handoffTimedOut=true
                        trace("handoff_timeout:$reason@${now-startedAtMs}ms")
                        // Do not classify a timed-out visual handoff as ready.
                        // Real ownership is already confirmed; release boundedly,
                        // leaving the existing task/session reconciliation intact.
                        stop()
                    }
                }
            }
        }
        handoffCheck=check
        check.run()
    }

    // An aspect-mismatched live buffer cannot cover both target dimensions
    // without stretching/cropping. Once the real task is confirmed ready,
    // reveal it over a short bounded GPU-only handoff rather than a hard cut.
    // No new surfaces, screenshots, task resizes or work while idle.
    private fun revealReadyDestination(liveReady: () -> Boolean, canRelease: () -> Boolean,
                                       durationMs:Long) {
        if (!isRunning || revealAnimator != null) return
        // Ready at the preceding main-loop check is not evidence that the
        // reveal itself has completed. Do not mark an aborted fade as success.
        if (!runCatching { canRelease() && liveReady() }.getOrDefault(false)) {
            trace("endpoint_reveal_rejected")
            stop()
            return
        }
        var finalAlphaSubmitted=false
        var finalAlphaCommitted=false
        // Terminal submission is the owner of completion, not the vendor
        // ValueAnimator end callback. ColorOS can lose that callback after
        // the real destination is already ready (89 device traces #67-71).
        val requireCommit=pairedRailPlan!=null && Build.VERSION.SDK_INT>=33
        fun finishReveal() {
            if(!isRunning)return
            handoffReady=finalAlphaSubmitted &&
                (!requireCommit || finalAlphaCommitted) &&
                !hostDetached && host.isAttachedToWindow &&
                runCatching { canRelease() && liveReady() &&
                    destinationContentMatches("reveal_end") }.getOrDefault(false)
            trace(if(handoffReady) "endpoint_reveal_end" else "endpoint_reveal_unconfirmed")
            stop()
        }
        fun submit(progress:Float,origin:String) {
            if(!isRunning || finalAlphaSubmitted)return
            if(hostDetached || !host.isAttachedToWindow){stop();return}
            if(!runCatching { canRelease() && liveReady() }.getOrDefault(false)) {
                trace("endpoint_reveal_target_lost")
                stop()
                return
            }
            val alpha=StageLiveSurfaceFlightGeometry.handoffReveal(progress)
            val terminal=alpha.cover==0f && alpha.mirror==0f
            runCatching {
                val tx=frameTransaction ?: error("Handoff transaction unavailable")
                occlusion.setRevealAlpha(tx,alpha.cover)
                tx.setAlpha(sourceMirror,alpha.mirror)
                tx.setAlpha(mainMirror,alpha.mirror)
                if(requireCommit && terminal) {
                    runCatching {
                        tx.addTransactionCommittedListener(host.context.mainExecutor) {
                            if(!isRunning || hostDetached)return@addTransactionCommittedListener
                            finalAlphaCommitted=true
                            trace("endpoint_reveal_commit")
                            if(finalAlphaSubmitted)finishReveal()
                        }
                    }.onFailure { runCatching { trace("endpoint_reveal_commit_listener_unavailable") } }
                }
                tx.apply()
                if(terminal) {
                    finalAlphaSubmitted=true
                    revealMotionDeadline?.let { ui.removeCallbacks(it) }
                    revealMotionDeadline=null
                    trace("endpoint_reveal_terminal:$origin")
                    if(requireCommit && !finalAlphaCommitted) {
                        val timeout=Runnable {
                            revealCommitDeadline=null
                            if(isRunning) {
                                trace("endpoint_reveal_commit_timeout")
                                finishReveal()
                            }
                        }
                        revealCommitDeadline=timeout
                        if(!ui.postDelayed(timeout,StageConcurrentNativeTrialPolicy.TERMINAL_COMMIT_BUDGET_MS))finishReveal()
                    } else finishReveal()
                }
            }.onFailure { stop() }
        }
        trace("endpoint_reveal_start gap=${endpointGap.toInt()}px")
        val reveal=ValueAnimator.ofFloat(0f,1f).apply {
            duration=durationMs
            interpolator=PathInterpolator(.2f,0f,.2f,1f)
            addUpdateListener { submit(it.animatedValue as Float,"update") }
            addListener(object:AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation:Animator) {
                    submit(1f,"animator_end")
                }
            })
        }
        revealAnimator=reveal
        // A stopped/missing pulse or end callback may not retain the mirrors
        // until the 1800ms global watchdog. One independent bounded terminal
        // attempt, using the original target/ownership/output gate.
        val remaining=MAX_LIFETIME_MS-(SystemClock.uptimeMillis()-startedAtMs)-
            StageConcurrentNativeTrialPolicy.TERMINAL_COMMIT_BUDGET_MS
        val delay=minOf(remaining.coerceAtLeast(0L),
            StageConcurrentNativeTrialPolicy.terminalDeadlineDelay(durationMs,ValueAnimator.getDurationScale()))
        val motionTimeout=Runnable {
            revealMotionDeadline=null
            if(isRunning && !finalAlphaSubmitted) {
                trace("endpoint_reveal_motion_deadline")
                submit(1f,"deadline")
            }
        }
        revealMotionDeadline=motionTimeout
        if(!ui.postDelayed(motionTimeout,delay)) {
            revealMotionDeadline=null
            submit(1f,"queue_unavailable")
            return
        }
        runCatching { reveal.start() }.onFailure {
            runCatching { NeXtepLog.warn("native_swap","Endpoint reveal start failed",it) }
            submit(1f,"start_failed")
        }
    }

    fun stop() {
        terminate(notifyFinished=true)
    }

    /** Retire compositor resources either normally or while transferring the
     * same tap to a safer visual backend. The latter must not finish the owner. */
    private fun terminate(notifyFinished:Boolean,outcomeOverride:String?=null): (() -> Unit)? {
        if (finished.getAndSet(true)) return null
        val callback=finishCallback
        finishCallback=null
        ui.removeCallbacks(lifetimeWatchdog)
        pendingFirstFlight?.let { ui.removeCallbacks(it) }
        pendingFirstFlight=null
        handoffCheck?.let { ui.removeCallbacks(it) }
        handoffCheck=null
        flightDeadline?.let { ui.removeCallbacks(it) }
        flightDeadline=null
        deferredMove?.let { ui.removeCallbacks(it) }
        deferredMove=null
        revealCommitDeadline?.let { ui.removeCallbacks(it) }
        revealCommitDeadline=null
        revealMotionDeadline?.let { ui.removeCallbacks(it) }
        revealMotionDeadline=null
        lastOutcome=outcomeOverride ?: if(!unmaskedMove && firstTransactionCommitted && nativeFrameCount>1 &&
            moveComplete && handoffReady)
            "completed:gpu_transactions_and_live_handoff_pixels_not_proven"
        else "ended:committed=$firstTransactionCommitted frames=$nativeFrameCount " +
            "terminal=$terminalTransactionCommitted unmasked=$unmaskedMove " +
            "move=$moveComplete liveReady=$handoffReady timedOut=$handoffTimedOut"
        // Only a normally completed anchored GPU handoff can certify this
        // exact demotion for a subsequent tap inside the still-active
        // reservation. Never certify fallback, timeout, cancellation, or the
        // fake-task compositor tests. Proof is of admission, not pixel continuity.
        val completedSlotStamp=if(notifyFinished && outcomeOverride==null &&
            incomingAnchor!=null && outgoingAnchor!=null && !unmaskedMove &&
            firstTransactionCommitted && terminalTransactionCommitted && nativeFrameCount>1 &&
            moveComplete && handoffReady && !handoffTimedOut && !endpointWriteFailed.get() &&
            !hostDetached && host.isAttachedToWindow &&
            Looper.myLooper()==ui.looper)
            runCatching { destinationStamp(outgoingStamp,incomingStamp.displayId) }.getOrNull()
            else null
        val completedSlotTicket=completedSlotStamp?.let {
            runCatching { TaskSurfaceCompat.captureSlotMirrorCompletion(it) }.getOrNull()
        }
        val flight=animator
        val reveal=revealAnimator
        animator=null
        revealAnimator=null
        StageConcurrentNativeTrialPolicy.cleanupSteps(
            { disarmHeldEndpointWriter() },
            {
                trace("gpu_end frames=$nativeFrameCount max_callback_gap=${biggestFrameGapMs}ms" +
                    " gaps_over24ms=$gapsOver24Ms handoff=$handoffReady timeout=$handoffTimedOut")
                if(nativeFrameCount>0) trace(
                    "gpu_submit frames=$nativeFrameCount max_us=${maxSubmitNanos / 1000}"+
                        " over_8333us=$submitOver120HzBudget over_16667us=$submitOver60HzBudget")
                if(nativeFrameCount>0) trace(
                    "gpu_parts n=$nativeFrameCount prep_us=${maxPrepareNanos / 1000}"+
                        " apply_us=${maxApplyNanos / 1000} prep8=$prepareOver120HzBudget"+
                        " apply8=$applyOver120HzBudget")
                if(nativeFrameCount>0) trace("gpu_unchanged_skipped=$unchangedFrameSkips")
                if(concurrentHandoff) trace("endpoint_rebase up="+incomingRebased.get()+
                    " down="+outgoingRebased.get()+" fail="+endpointWriteFailed.get())
                if(concurrentHandoff && coveredFlight && pairedRailPlan!=null)
                    trace("endpoint_rebase_receipts up="+incomingRebaseCommitted.get()+
                        " down="+outgoingRebaseCommitted.get()+" pixels_not_proven")
                NeXtepLog.info("native_swap",
                    "Native dual-surface finished: GPU transactions=$nativeFrameCount " +
                    "initialCommitted=$firstTransactionCommitted realTaskMoved=$moveComplete")
            },
            { flight?.removeAllListeners(); flight?.removeAllUpdateListeners() },
            { flight?.cancel() },
            { reveal?.removeAllListeners(); reveal?.removeAllUpdateListeners() },
            { reveal?.cancel() },
            {
                val cleaned=cleanupWithoutCallback()
                if(cleaned && completedSlotStamp!=null && completedSlotTicket!=null &&
                    !hostDetached && host.isAttachedToWindow &&
                    !sourceMirror.isValid && !mainMirror.isValid &&
                    TaskSurfaceCompat.recordSlotMirrorCompletion(completedSlotStamp,completedSlotTicket)) {
                    trace("slot_mirror_completed_demotion_verified")
                }
            },
            { if(notifyFinished) callback?.invoke() },
            onFailure={ NeXtepLog.warn("native_swap","Native animation teardown step failed",it) },
        )
        return callback
    }

    private fun cleanupWithoutCallback():Boolean {
        var cleaned=true
        val ownedTransaction=frameTransaction
        frameTransaction=null
        StageConcurrentNativeTrialPolicy.cleanupSteps(
            { host.removeOnAttachStateChangeListener(attachmentListener) },
            { ui.removeCallbacks(detachCleanup) },
            { disarmHeldEndpointWriter() },
            {
                SurfaceControl.Transaction().use { tx ->
                    if (sourceMirror.isValid) tx.setVisibility(sourceMirror,false)
                    if (mainMirror.isValid) tx.setVisibility(mainMirror,false)
                    occlusion.hide(tx)
                    tx.apply()
                    traceCoverHideSubmitted(if(handoffTimedOut) "timeout" else if(handoffReady) "ready" else "not_ready")
                }
            },
            { sourceMirror.release() },
            { mainMirror.release() },
            { ownedTransaction?.close() },
            { occlusion.release() },
            { (background.parent as? FrameLayout)?.removeView(background) },
            onFailure={
                cleaned=false
                NeXtepLog.warn("native_swap","Native resource cleanup step failed",it)
            },
        )
        return cleaned
    }
}

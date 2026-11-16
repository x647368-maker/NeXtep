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
    // Real ViewRoot instrumentation uses this explicit test seam. The ordinary
    // task factory still requires both private debug flags for route selection.
    internal fun preparePairedRailRuntimeTest(
        screen:StageLiveSurfaceFlightGeometry.Box,
        obstacles:List<StageLiveSurfaceFlightGeometry.Box>,
    ):Boolean {
        if(finished.get() || isRunning)return false
        // The covered fixture must reproduce the old rejection and admit only
        // the actual adaptive route. No production gate or task identity is bypassed.
        pairedRailPlan=if(coveredFlight) {
            if(!StageLiveSurfaceFlightGeometry.canUseCoveredFlight(slotStart,mainStart,
                    incomingGeometryCrop,outgoingGeometryCrop,obstacles))return false
            if(StageCertifiedRailRoute.choose(slotStart,mainStart,incomingGeometryCrop,
                    outgoingGeometryCrop,screen,obstacles,oldCovered=true)!=null)return false
            StageAdaptiveCoveredRailRoute.choose(slotStart,mainStart,incomingGeometryCrop,
                outgoingGeometryCrop,screen,obstacles)
        } else StageCertifiedRailRoute.choose(slotStart,mainStart,incomingGeometryCrop,
            outgoingGeometryCrop,screen,obstacles,oldCovered=false)
        return pairedRailPlan!=null
    }
    private val ui = Handler(Looper.getMainLooper())
    private val traceAttemptId = StageExchangeTelemetry.currentNativeTapId()
    private fun trace(stage: String) =
        StageExchangeTelemetry.nativeEvent(stage, traceAttemptId)
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
            val fit=StageHeldEndpointPolicy.fit(
                target.left,target.top,target.right,target.bottom,
                frame.crop.left,frame.crop.top,frame.crop.right,frame.crop.bottom,
                frame.scale,frame.x,frame.y,detachedRoot) ?: return@HeldMirrorWriter
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
                (if(incoming) incomingRebased else outgoingRebased).set(true)
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
        // from a manufacturer/model. Older/opaque OEMs keep the legacy contract
        // and log unknown instead of claiming the flag was observed.
        private val detachedMirrorRoot: Boolean? by lazy {
            runCatching {
                val type=Class.forName("android.os.flagging.AconfigPackage")
                val pkg=type.getMethod("load",String::class.java)
                    .invoke(null,"com.android.graphics.surfaceflinger.flags")
                val read=type.getMethod("getBooleanFlagValue",String::class.java,
                    Boolean::class.javaPrimitiveType)
                val a=read.invoke(pkg,"detached_mirror",false) as Boolean
                val b=read.invoke(pkg,"detached_mirror",true) as Boolean
                if(a==b) a else null
            }.getOrNull()
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
            val detached=detachedMirrorRoot
            val outgoingCrop=StageLiveSurfaceFlightGeometry.mainMirrorCrop(
                to,fullScreenWidth,fullScreenHeight,detached==true)
                ?: return markUnavailable("invalid_bounds")
            // Vendor APIs can throw after the first handle has been acquired.
            // Every early return and constructor failure must release all
            // locally owned surfaces independently; never release task leashes.
            var ownedOutgoing:SurfaceControl?=null
            var ownedIncoming:SurfaceControl?=null
            var ownedMasks:StageGpuMirrorOcclusion?=null
            var transferred=false
            try {
                StageExchangeTelemetry.nativeEvent(
                    "mirror_root=${detached?.let { if(it) "detached" else "retained" } ?: "unknown_legacy"}")
                // The mirror must follow the app TASK, not the rail's ViewRoot.
                // That root switches the producer during the two-stage exchange,
                // which caused PR59 to show the new app twice while still flying.
                StageExchangeTelemetry.nativeEvent(
                    TaskSurfaceCompat.describeOwnedMainPresentation(mainTaskId))
                val outgoingBefore=TaskSurfaceCompat.mirrorSourceStamp(mainTaskId,0)
                val incomingBefore=TaskSurfaceCompat.mirrorSourceStamp(sourceTaskId,sourceDisplayId)
                val outgoing = TaskSurfaceCompat.createOwnedMainMirror(mainTaskId)
                    ?: return markUnavailable("no_owned_main_task_mirror")
                ownedOutgoing=outgoing
                val slotMirror = TaskSurfaceCompat.createOwnedSlotTaskMirror(
                    sourceTaskId, sourceDisplayId,
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
                        StageCertifiedRailRoute.choose(from,to,incomingCrop,outgoingCrop,screen,
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
                if (stableIncoming==null || stableOutgoing==null) {
                    return markUnavailable("mirror_source_changed")
                }
                val masks = StageGpuMirrorOcclusion.create()
                    ?: return markUnavailable("atomic_occlusion_unavailable")
                ownedMasks=masks
                val exchange=StageLiveSurfaceExchange(
                    host, incoming, outgoing, localCrop,
                    outgoingCrop.toAndroidRect(),
                    from,to,backdrop,masks,coveredFlight,
                    stableIncoming,stableOutgoing,
                    detached==true,
                )
                exchange.pairedRailPlan=pairedPlan
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
        endpointGap = maxOf(incomingGap, outgoingGap)
        trace("endpoint_gap_px up=${incomingGap.toInt()} down=${outgoingGap.toInt()}" +
            " up_fit=${finalIncoming.box.width.toInt()}x${finalIncoming.box.height.toInt()}" +
            " down_fit=${finalOutgoing.box.width.toInt()}x${finalOutgoing.box.height.toInt()}")
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
                    incomingStamp.taskId,incomingStamp.displayId)) },
                { outgoingStamp.sameSource(TaskSurfaceCompat.mirrorSourceStamp(
                    outgoingStamp.taskId,outgoingStamp.displayId)) })
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
                unmaskedMove=true
                trace("native_unmasked_fallback:first=$firstTransactionCommitted terminal=$terminalTransactionCommitted")
            }
            sentMove = true
            if (concurrentHandoff && frameComplete && !unmaskedMove) armHeldEndpointWriter()
            trace(
                "real_move_requested@${SystemClock.uptimeMillis()-startedAtMs}ms frames=$nativeFrameCount")
            if (moveAsync != null) {
                // First callback owns the result, even if the provider throws
                // after calling it synchronously. Do not discard a verified
                // migration or allow a second callback to retract it.
                val ackClaimed=AtomicBoolean(false)
                runCatching {
                    moveAsync { ok ->
                        if(ackClaimed.compareAndSet(false,true)) ui.post {
                            if (!isRunning || moveComplete) return@post
                            if (hostDetached || !host.isAttachedToWindow || !ok) { stop(); return@post }
                            moveComplete = true
                            moveCompletedAt = SystemClock.uptimeMillis()
                            trace(
                                "real_move_ack@${moveCompletedAt-startedAtMs}ms")
                            if(unmaskedMove)stop() else finishIfReady(liveReady,canRelease)
                        }
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
    private fun applyFrame(transaction: SurfaceControl.Transaction, p: Float) {
        val paired=pairedRailPlan?.at(p)
        // At p=0 keep the original complete source; at every later frame
        // the planned viewport is independent of the uniformly scaled crop.
        val incoming=(if(paired!=null && coveredFlight && p>0f)
            StageAdaptiveCoveredRailRoute.frame(paired.incoming,incomingGeometryCrop)
        else if(paired!=null && !coveredFlight)pairFrame(paired.incoming,incomingGeometryCrop)
        else StageLiveSurfaceFlightGeometry.mirrorFrame(
            slotStart,mainStart,incomingGeometryCrop,p,coveredFlight,
        )) ?: error("Unsafe incoming GPU rail path")
        val outgoing=(if(paired!=null && coveredFlight && p>0f)
            StageAdaptiveCoveredRailRoute.frame(paired.outgoing,outgoingGeometryCrop)
        else if(paired!=null && !coveredFlight)pairFrame(paired.outgoing,outgoingGeometryCrop)
        else StageLiveSurfaceFlightGeometry.mirrorFrame(
            mainStart,slotStart,outgoingGeometryCrop,p,coveredFlight,
        )) ?: error("Unsafe outgoing GPU rail path")
        // Both task mirrors are updated by the same GPU transaction. Cover
        // mode changes only their transient crop, never original task bounds.
        if (coveredFlight) {
            transaction.setCrop(sourceMirror,incoming.crop.toAndroidRect())
            transaction.setCrop(mainMirror,outgoing.crop.toAndroidRect())
        }
        transaction.setScale(sourceMirror,incoming.scale,incoming.scale)
        transaction.setPosition(sourceMirror,incoming.x,incoming.y)
        transaction.setScale(mainMirror,outgoing.scale,outgoing.scale)
        transaction.setPosition(mainMirror,outgoing.x,outgoing.y)
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
                val reason=when {
                    !frameComplete -> "flight_end_missing"
                    !releasable -> "session_unsettled"
                    !live -> "target_not_ready"
                    else -> "ready"
                }
                if (reason != lastHandoffWait) {
                    lastHandoffWait=reason
                    trace("handoff_state=$reason host_attached=${host.isAttachedToWindow}")
                }
                when (StageConcurrentNativeTrialPolicy.handoffAction(
                    now-moveCompletedAt,timeout,frameComplete,releasable,live)) {
                    StageConcurrentNativeTrialPolicy.HandoffAction.WAIT ->
                        ui.postAtTime(this,StageConcurrentNativeTrialPolicy.nextHandoffCheck(
                            SystemClock.uptimeMillis(),deadline))
                    StageConcurrentNativeTrialPolicy.HandoffAction.READY -> {
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
                    StageConcurrentNativeTrialPolicy.HandoffAction.EXPIRED -> {
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
        var revealEnded=false
        // Only the independently gated paired route changes its handoff.
        // Submission and compositor acknowledgment are different evidence.
        val requireCommit=pairedRailPlan!=null && Build.VERSION.SDK_INT>=33
        fun finishReveal() {
            if(!isRunning)return
            handoffReady=finalAlphaSubmitted &&
                (!requireCommit || finalAlphaCommitted) &&
                !hostDetached && host.isAttachedToWindow &&
                runCatching { canRelease() && liveReady() }.getOrDefault(false)
            trace(if(handoffReady) "endpoint_reveal_end" else "endpoint_reveal_unconfirmed")
            stop()
        }
        trace("endpoint_reveal_start gap=${endpointGap.toInt()}px")
        val reveal = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = PathInterpolator(.2f, 0f, .2f, 1f)
            addUpdateListener {
                if (!isRunning) return@addUpdateListener
                if(hostDetached || !host.isAttachedToWindow){stop();return@addUpdateListener}
                if(requireCommit && finalAlphaSubmitted)return@addUpdateListener
                // A task/viewport may change during the reveal itself. Do not
                // submit another alpha frame over a stale destination owner.
                if(!runCatching { canRelease() && liveReady() }.getOrDefault(false)) {
                    trace("endpoint_reveal_target_lost")
                    stop()
                    return@addUpdateListener
                }
                val alpha = StageLiveSurfaceFlightGeometry.handoffReveal(it.animatedValue as Float)
                runCatching {
                    val tx = frameTransaction ?: error("Handoff transaction unavailable")
                    occlusion.setRevealAlpha(tx, alpha.cover)
                    tx.setAlpha(sourceMirror, alpha.mirror)
                    tx.setAlpha(mainMirror, alpha.mirror)
                    val terminal=alpha.cover==0f && alpha.mirror==0f
                    if(requireCommit && terminal) {
                        runCatching {
                            tx.addTransactionCommittedListener(host.context.mainExecutor) {
                                if(!isRunning || hostDetached)return@addTransactionCommittedListener
                                finalAlphaCommitted=true
                                trace("endpoint_reveal_commit")
                                if(revealEnded)finishReveal()
                            }
                        }.onFailure { runCatching { trace("endpoint_reveal_commit_listener_unavailable") } }
                    }
                    tx.apply()
                    if(terminal) finalAlphaSubmitted=true
                }.onFailure { stop() }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isRunning) return
                    revealEnded=true
                    if(requireCommit && finalAlphaSubmitted && !finalAlphaCommitted &&
                        !hostDetached && host.isAttachedToWindow) {
                        // One bounded wait, no polling and no new Surface. If
                        // the compositor never confirms, release the visual
                        // resources, but never report a confirmed handoff.
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
            })
        }
        revealAnimator = reveal
        runCatching { reveal.start() }.onFailure {
            runCatching { NeXtepLog.warn("native_swap","Endpoint reveal start failed",it) }
            stop()
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
        lastOutcome=outcomeOverride ?: if(!unmaskedMove && firstTransactionCommitted && nativeFrameCount>1 &&
            moveComplete && handoffReady)
            "completed:gpu_transactions_and_live_handoff_pixels_not_proven"
        else "ended:committed=$firstTransactionCommitted frames=$nativeFrameCount " +
            "terminal=$terminalTransactionCommitted unmasked=$unmaskedMove " +
            "move=$moveComplete liveReady=$handoffReady timedOut=$handoffTimedOut"
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
            { cleanupWithoutCallback() },
            { if(notifyFinished) callback?.invoke() },
            onFailure={ NeXtepLog.warn("native_swap","Native animation teardown step failed",it) },
        )
        return callback
    }

    private fun cleanupWithoutCallback() {
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
                }
            },
            { sourceMirror.release() },
            { mainMirror.release() },
            { ownedTransaction?.close() },
            { occlusion.release() },
            { (background.parent as? FrameLayout)?.removeView(background) },
            onFailure={ NeXtepLog.warn("native_swap","Native resource cleanup step failed",it) },
        )
    }
}

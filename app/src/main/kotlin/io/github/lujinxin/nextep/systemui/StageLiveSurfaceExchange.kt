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
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import java.util.concurrent.atomic.AtomicBoolean

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
) {
    private val incomingGeometryCrop = StageLiveSurfaceFlightGeometry.Crop(
        sourceCrop.left,sourceCrop.top,sourceCrop.right,sourceCrop.bottom)
    private val outgoingGeometryCrop = StageLiveSurfaceFlightGeometry.Crop(
        mainCrop.left,mainCrop.top,mainCrop.right,mainCrop.bottom)
    private val ui = Handler(Looper.getMainLooper())
    private val traceAttemptId = StageExchangeTelemetry.currentNativeTapId()
    private fun trace(stage: String) =
        StageExchangeTelemetry.nativeEvent(stage, traceAttemptId)
    private val lifetimeWatchdog = Runnable {
        if (isRunning) {
            trace("lifetime_watchdog frame=$frameComplete move=$moveComplete check=$handoffCheckStarted")
            NeXtepLog.warn("native_swap", "Two-mirror flight exceeded safety lifetime; releasing compositor mirrors")
            stop()
        }
    }
    private val finished = AtomicBoolean(false)
    private var animator: ValueAnimator? = null
    private var revealAnimator: ValueAnimator? = null
    private var handoffCheckStarted = false
    private var handoffCheck: Runnable? = null
    private var lastHandoffWait = ""
    private var endpointGap = 0f
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
    private var nativeFrameCount = 0
    private var firstTransactionCommitted = false
    private var previousFrameAtMs = 0L
    private var biggestFrameGapMs = 0L
    private var gapsOver24Ms = 0
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
            StageExchangeTelemetry.nativeEvent(
                "mirror_root=${detached?.let { if(it) "detached" else "retained" } ?: "unknown_legacy"}")
            // The mirror must follow the app TASK, not the rail's ViewRoot.
            // That root switches the producer during the two-stage exchange,
            // which caused PR59 to show the new app twice while still flying.
            StageExchangeTelemetry.nativeEvent(
                TaskSurfaceCompat.describeOwnedMainPresentation(mainTaskId))
            val outgoing = TaskSurfaceCompat.createOwnedMainMirror(mainTaskId)
                ?: return markUnavailable("no_owned_main_task_mirror")
            val slotMirror = TaskSurfaceCompat.createOwnedSlotTaskMirror(
                sourceTaskId, sourceDisplayId,
            ) ?: run {
                outgoing.release()
                return markUnavailable("slot_task_leash_not_ready")
            }
            val incoming = slotMirror.surface
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
                outgoing.release()
                incoming.release()
                return markUnavailable("rail_corridor_blocked")
            }
            val incomingCrop = StageLiveSurfaceFlightGeometry.Crop(
                localCrop.left, localCrop.top, localCrop.right, localCrop.bottom)
            val coveredFlight = StageLiveSurfaceFlightGeometry.canUseCoveredFlight(
                from, to, incomingCrop, outgoingCrop, obstacles)
            StageExchangeTelemetry.nativeEvent(
                "mirror_crop_mode=" + if (coveredFlight) "covered_safe" else "contain_reveal")
            if (!outgoing.isValid || !incoming.isValid) {
                outgoing.release()
                incoming.release()
                return markUnavailable("invalid_native_handles")
            }
            val masks = StageGpuMirrorOcclusion.create() ?: run {
                outgoing.release()
                incoming.release()
                return markUnavailable("atomic_occlusion_unavailable")
            }
            lastOutcome="capable:two_owned_tasks_and_atomic_masks"
            return StageLiveSurfaceExchange(
                host, incoming, outgoing, localCrop,
                outgoingCrop.toAndroidRect(),
                from,to,backdrop,masks,coveredFlight,
            )
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
        trace(
            "mirror_start crop=${sourceCrop.width()}x${sourceCrop.height()}" +
            " main=${mainStart.width.toInt()}x${mainStart.height.toInt()}")
        val root = host.rootSurfaceControl ?: return cleanupWithoutCallback().let { false }
        frameTransaction = SurfaceControl.Transaction()
        finishCallback = onFinished
        try {
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
                    tx.addTransactionCommittedListener(host.context.mainExecutor) {
                        if (finished.get()) return@addTransactionCommittedListener
                        firstTransactionCommitted = true
                        trace(
                            "first_gpu_transaction_committed@${SystemClock.uptimeMillis()-startedAtMs}ms")
                        NeXtepLog.info("native_swap", "Two real compositor mirror layers accepted in SurfaceFlinger")
                    }
                }
                tx.apply()
            } finally { tx.close() }
        } catch (error: Throwable) {
            NeXtepLog.warn("native_swap", "Native mirror parenting failed before task move", error)
            finishCallback = null
            finished.set(true)
            cleanupWithoutCallback()
            return false
        }

        fun commitMove() {
            if (!isRunning || sentMove) return
            sentMove = true
            trace(
                "real_move_requested@${SystemClock.uptimeMillis()-startedAtMs}ms frames=$nativeFrameCount")
            if (moveAsync != null) {
                runCatching {
                    moveAsync { ok ->
                        ui.post {
                            if (!isRunning) return@post
                            if (!ok) { stop(); return@post }
                            moveComplete = true
                            moveCompletedAt = SystemClock.uptimeMillis()
                            trace(
                                "real_move_ack@${moveCompletedAt-startedAtMs}ms")
                            finishIfReady(liveReady,canRelease)
                        }
                    }
                }.onFailure {
                    NeXtepLog.warn("native_swap", "Real phased move failed",it)
                    stop()
                }
            } else {
                val ok=runCatching(move).getOrDefault(false)
                if (!ok) { stop(); return }
                moveComplete=true
                moveCompletedAt=SystemClock.uptimeMillis()
                trace(
                    "sync_move_ack@${moveCompletedAt-startedAtMs}ms")
                finishIfReady(liveReady,canRelease)
            }
        }
        // One-shot cleanup if a device sleeps/locks between frame callback
        // and move acknowledgment. No per-frame background polling.
        ui.postDelayed(lifetimeWatchdog,MAX_LIFETIME_MS)
        animator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=if (immediateMove) durationMs.toLong().coerceIn(320L,380L)
                else durationMs.toLong().coerceIn(MIN_DURATION_MS,MAX_DURATION_MS)
            interpolator=PathInterpolator(.4f,0f,.2f,1f)
            addUpdateListener {
                if (!isRunning) return@addUpdateListener
                val fraction=(it.animatedValue as Float)
                lastProgress=fraction
                runCatching {
                    val frame=frameTransaction ?: error("Flight transaction is unavailable")
                    applyFrame(frame,fraction)
                    frame.apply()
                    val now=SystemClock.uptimeMillis()
                    if(previousFrameAtMs>0L) {
                        val gap=now-previousFrameAtMs
                        if(gap>biggestFrameGapMs)biggestFrameGapMs=gap
                        if(gap>24L)gapsOver24Ms++
                    }
                    previousFrameAtMs=now
                    nativeFrameCount++
                }.onFailure { error ->
                    NeXtepLog.warn("native_swap","Real dual-layer frame failed",error)
                    stop()
                }
                // PathInterpolator changes the rendered fraction; defer the
                // real Binder move until the last portion of *wall clock*
                // motion, so mirrored live apps are actually seen travelling.
                if (immediateMove && !sentMove &&
                    StageConcurrentNativeTrialPolicy.shouldStartMove(
                        it.currentPlayTime,it.duration,firstTransactionCommitted,nativeFrameCount)) {
                    NeXtepLog.info("native_swap",
                        "PR55 real task handoff t=${it.currentPlayTime}ms " +
                            "committed=$firstTransactionCommitted frames=$nativeFrameCount")
                    commitMove()
                } else if (!immediateMove &&
                    it.currentPlayTime >= (it.duration*MOVE_PROGRESS).toLong() &&
                    firstTransactionCommitted && !sentMove) commitMove()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isRunning) return
                    frameComplete=true
                    trace("flight_complete@${SystemClock.uptimeMillis()-startedAtMs}ms")
                    if (!sentMove) commitMove()
                    finishIfReady(liveReady,canRelease)
                }
            })
            start()
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
        return true
    }

    private fun applyFrame(transaction: SurfaceControl.Transaction, p: Float) {
        val incoming=StageLiveSurfaceFlightGeometry.mirrorFrame(
            slotStart,mainStart,incomingGeometryCrop,p,coveredFlight,
        ) ?: error("Unsafe incoming GPU rail path")
        val outgoing=StageLiveSurfaceFlightGeometry.mirrorFrame(
            mainStart,slotStart,outgoingGeometryCrop,p,coveredFlight,
        ) ?: error("Unsafe outgoing GPU rail path")
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
                        handoffReady=true
                        trace("handoff_ready@${now-startedAtMs}ms")
                        if (endpointGap > ENDPOINT_GAP_PX && firstTransactionCommitted) {
                            revealReadyDestination(liveReady,canRelease)
                        } else stop()
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
    private fun revealReadyDestination(liveReady: () -> Boolean, canRelease: () -> Boolean) {
        if (!isRunning || revealAnimator != null) return
        if (!runCatching { canRelease() && liveReady() }.getOrDefault(false)) {
            stop()
            return
        }
        trace("endpoint_reveal_start gap=${endpointGap.toInt()}px")
        val reveal = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = READY_REVEAL_MS
            interpolator = PathInterpolator(.2f, 0f, .2f, 1f)
            addUpdateListener {
                if (!isRunning) return@addUpdateListener
                val alpha = StageLiveSurfaceFlightGeometry.handoffReveal(it.animatedValue as Float)
                runCatching {
                    val tx = frameTransaction ?: error("Handoff transaction unavailable")
                    occlusion.setRevealAlpha(tx, alpha.cover)
                    tx.setAlpha(sourceMirror, alpha.mirror)
                    tx.setAlpha(mainMirror, alpha.mirror)
                    tx.apply()
                }.onFailure { stop() }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isRunning) return
                    trace("endpoint_reveal_end")
                    stop()
                }
            })
        }
        revealAnimator = reveal
        reveal.start()
    }

    fun stop() {
        if (finished.getAndSet(true)) return
        val callback=finishCallback
        finishCallback=null
        ui.removeCallbacks(lifetimeWatchdog)
        handoffCheck?.let { ui.removeCallbacks(it) }
        handoffCheck=null
        lastOutcome=if(firstTransactionCommitted && nativeFrameCount>1 &&
            moveComplete && handoffReady)
            "completed:gpu_transactions_and_live_handoff_pixels_not_proven"
        else "ended:committed=$firstTransactionCommitted frames=$nativeFrameCount " +
            "move=$moveComplete liveReady=$handoffReady timedOut=$handoffTimedOut"
        trace(
            "gpu_end frames=$nativeFrameCount max_callback_gap=${biggestFrameGapMs}ms" +
            " gaps_over24ms=$gapsOver24Ms handoff=$handoffReady timeout=$handoffTimedOut")
        NeXtepLog.info("native_swap",
            "Native dual-surface finished: GPU transactions=$nativeFrameCount " +
            "initialCommitted=$firstTransactionCommitted realTaskMoved=$moveComplete")
        animator?.removeAllListeners()
        animator?.cancel()
        animator=null
        revealAnimator?.removeAllListeners()
        revealAnimator?.removeAllUpdateListeners()
        revealAnimator?.cancel()
        revealAnimator=null
        cleanupWithoutCallback()
        callback?.invoke()
    }

    private fun cleanupWithoutCallback() {
        runCatching {
            SurfaceControl.Transaction().use { tx ->
                if (sourceMirror.isValid) tx.setVisibility(sourceMirror,false)
                if (mainMirror.isValid) tx.setVisibility(mainMirror,false)
                occlusion.hide(tx)
                tx.apply()
            }
        }
        sourceMirror.release()
        frameTransaction?.let { runCatching { it.close() } }
        frameTransaction=null
        mainMirror.release()
        occlusion.release()
        runCatching { (background.parent as? FrameLayout)?.removeView(background) }
    }
}

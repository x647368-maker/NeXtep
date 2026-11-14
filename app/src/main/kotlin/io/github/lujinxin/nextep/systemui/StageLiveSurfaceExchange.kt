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
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry
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
) {
    private val ui = Handler(Looper.getMainLooper())
    private val lifetimeWatchdog = Runnable {
        if (isRunning) {
            NeXtepLog.warn("native_swap", "Two-mirror flight exceeded safety lifetime; releasing compositor mirrors")
            stop()
        }
    }
    private val finished = AtomicBoolean(false)
    private var animator: ValueAnimator? = null
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
    private var lastAnimationCallbackAt = 0L
    private var worstAnimationCallbackGapMs = 0L
    private var slowAnimationCallbackGaps = 0
    private var firstTransactionCommitted = false
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
        // One bounded wait for the replacement real-task and slot buffers.
        // Shorter than the overall 1800ms watchdog; no idle polling.
        private const val CONCURRENT_HANDOFF_TIMEOUT_MS = 480L
        // Do not reparent actual app tasks while either reflected producer is
        // halfway across the display: ColorOS can replace the live buffer
        // mid-flight, reproducing the exact flash/snap seen in 3717.mp4.
        // The normal phased transfer begins at the *end* of visual travel.
        private const val MOVE_PROGRESS = .94f
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
            // The mirror must follow the app TASK, not the rail's ViewRoot.
            // That root switches the producer during the two-stage exchange,
            // which caused PR59 to show the new app twice while still flying.
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
            StageExchangeTelemetry.nativeEvent(TaskSurfaceCompat.describeOwnedMainFrame(mainTaskId))
            StageExchangeTelemetry.nativeEvent(
                "rawMirrorCrop slot=${localCrop.width()}x${localCrop.height()} " +
                    "main=${fullScreenWidth}x${fullScreenHeight} " +
                    "requestedVisibleMain=${mainBounds.width()}x${mainBounds.height()}"
            )
            val obstacles = otherSlotBounds.map {
                StageLiveSurfaceFlightGeometry.Box(
                    it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(),
                )
            }
            if (!StageLiveSurfaceFlightGeometry.corridorAvoidsOtherSlots(
                    from, to, localCrop.width(), localCrop.height(), obstacles)) {
                outgoing.release()
                incoming.release()
                return markUnavailable("rail_corridor_blocked")
            }
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
                Rect(0,0,fullScreenWidth,fullScreenHeight),
                from,to,backdrop,masks,
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
                        firstTransactionCommitted = true
                        StageExchangeTelemetry.nativeEvent("sf_transaction_committed_not_pixel_present")
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
            StageExchangeTelemetry.nativeEvent(
                "task_move_requested progress=$lastProgress frames=$nativeFrameCount"
            )
            if (moveAsync != null) {
                runCatching {
                    moveAsync { ok ->
                        ui.post {
                            if (!isRunning) return@post
                            StageExchangeTelemetry.nativeEvent("task_move_ack_ok=$ok")
                            if (!ok) { stop(); return@post }
                            moveComplete = true
                            moveCompletedAt = SystemClock.uptimeMillis()
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
                // Animator callback gaps are NOT the actual rendered GPU FPS.
                // Cheap counters only: no String allocations on normal frames.
                val frameAt=SystemClock.uptimeMillis()
                if (lastAnimationCallbackAt > 0L) {
                    val gap=(frameAt-lastAnimationCallbackAt).coerceAtLeast(0L)
                    worstAnimationCallbackGapMs=maxOf(worstAnimationCallbackGapMs,gap)
                    if(gap>=33L) slowAnimationCallbackGaps++
                }
                lastAnimationCallbackAt=frameAt
                if(nativeFrameCount==0) StageExchangeTelemetry.nativeEvent("first_anim_callback")
                runCatching {
                    val frame=frameTransaction ?: error("Flight transaction is unavailable")
                    applyFrame(frame,fraction)
                    frame.apply()
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
                    StageExchangeTelemetry.nativeEvent(
                        "animation_end submittedFrames=$nativeFrameCount " +
                            "maxCallbackGap=${worstAnimationCallbackGapMs}ms gapOver33=$slowAnimationCallbackGaps"
                    )
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
        val incoming=StageLiveSurfaceFlightGeometry.railCorridorFlight(
            slotStart,mainStart,sourceCrop.width(),sourceCrop.height(),p,
        ) ?: error("Unsafe incoming GPU rail path")
        // Unlike the sidebar ViewRoot, WM Shell's main-task leash is ALREADY
        // fitted to its visible main rectangle. Mirroring that hierarchy
        // preserves the owned task's scale and translation. The outer mirror
        // therefore starts at identity (1, 0, 0) and moves the *visible main
        // bounds*, or the main application would shrink a second time.
        val outgoing=StageLiveSurfaceFlightGeometry.railCorridorFlight(
            mainStart,slotStart,mainStart.width.toInt(),mainStart.height.toInt(),p,
        ) ?: error("Unsafe outgoing GPU rail path")
        transaction.setScale(sourceMirror,incoming.scale,incoming.scale)
        transaction.setPosition(sourceMirror,
            incoming.x-sourceCrop.left*incoming.scale,
            incoming.y-sourceCrop.top*incoming.scale)
        transaction.setScale(mainMirror,outgoing.scale,outgoing.scale)
        transaction.setPosition(mainMirror,
            outgoing.x-mainStart.left*outgoing.scale,
            outgoing.y-mainStart.top*outgoing.scale)
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
        if (!isRunning || !frameComplete || !moveComplete) return
        fun check() {
            if (!isRunning) return
            val elapsed=SystemClock.uptimeMillis()-moveCompletedAt
            val ready=runCatching { canRelease() && liveReady() }.getOrDefault(false)
            val timeout=if (concurrentHandoff) CONCURRENT_HANDOFF_TIMEOUT_MS
                else HANDOFF_TIMEOUT_MS
            if (ready || elapsed >= timeout) {
                handoffReady=ready
                handoffTimedOut=!ready
                StageExchangeTelemetry.nativeEvent(
                    "buffer_handoff_probe ready=$ready timeout=$handoffTimedOut elapsed=${elapsed}ms"
                )
                NeXtepLog.info("native_swap",
                    "Real GPU layer handoff ready=$ready elapsed=${elapsed}ms")
                host.postOnAnimation { stop() }
            } else host.postOnAnimation { check() }
        }
        check()
    }

    fun stop() {
        if (finished.getAndSet(true)) return
        val callback=finishCallback
        finishCallback=null
        ui.removeCallbacks(lifetimeWatchdog)
        lastOutcome=if(firstTransactionCommitted && nativeFrameCount>1 &&
            moveComplete && handoffReady)
            "completed:gpu_transactions_and_live_handoff_pixels_not_proven"
        else "ended:committed=$firstTransactionCommitted frames=$nativeFrameCount " +
            "move=$moveComplete liveReady=$handoffReady timedOut=$handoffTimedOut"
        StageExchangeTelemetry.nativeEvent(
            "mirror_release submitted=$nativeFrameCount committed=$firstTransactionCommitted " +
                "move=$moveComplete handoff=$handoffReady timeout=$handoffTimedOut"
        )
        NeXtepLog.info("native_swap",
            "Native dual-surface finished: GPU transactions=$nativeFrameCount " +
            "initialCommitted=$firstTransactionCommitted realTaskMoved=$moveComplete")
        animator?.removeAllListeners()
        animator?.cancel()
        animator=null
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

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
 * The two actual tasks are migrated near the end of the physical animation;
 * mirrors vanish only after the normal slot/main task buffers are ready (or
 * a bounded timeout). If the vendor denies mirroring or ownership is stale,
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
) {
    private val ui = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean(false)
    private var animator: ValueAnimator? = null
    private var sentMove = false
    private var moveComplete = false
    private var frameComplete = false
    private var moveCompletedAt = -1L
    private var finishCallback: (() -> Unit)? = null
    private var lastProgress = 0f
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
    val description: String get() = "native_two_mirrors"

    /** Both views must be attached to a valid, hardware-composited host. */
    companion object {
        private const val MIN_DURATION_MS = 280L
        private const val MAX_DURATION_MS = 360L
        private const val HANDOFF_TIMEOUT_MS = 600L
        private const val MOVE_PROGRESS = .72f

        fun create(
            host: FrameLayout,
            sourceSlotView: View,
            mainTaskId: Int,
            sourceBounds: Rect,
            mainBounds: Rect,
            fullScreenWidth: Int,
            fullScreenHeight: Int,
            backdrop: Drawable?,
        ): StageLiveSurfaceExchange? {
            if (Build.VERSION.SDK_INT < 31 || !host.isAttachedToWindow ||
                !sourceSlotView.isAttachedToWindow || !host.isHardwareAccelerated
            ) return null
            if (sourceSlotView.rootView === host.rootView) return null
            val from = StageLiveSurfaceFlightGeometry.Box(
                sourceBounds.left.toFloat(),sourceBounds.top.toFloat(),
                sourceBounds.right.toFloat(),sourceBounds.bottom.toFloat(),
            )
            val to = StageLiveSurfaceFlightGeometry.Box(
                mainBounds.left.toFloat(),mainBounds.top.toFloat(),
                mainBounds.right.toFloat(),mainBounds.bottom.toFloat(),
            )
            if (!from.valid() || !to.valid() || fullScreenWidth < 2 || fullScreenHeight < 2)
                return null
            val sourceRoot = sourceSlotView.rootView
            val origin=IntArray(2)
            sourceRoot.getLocationOnScreen(origin)
            val localCrop = StageLiveSurfaceFlightGeometry.localCrop(
                from,origin[0],origin[1],sourceRoot.width,sourceRoot.height,
            ) ?: return null
            // Never mirror a different active task. The native presenter
            // verifies that taskId still owns the exact observed WM leash.
            val outgoing = TaskSurfaceCompat.createOwnedMainMirror(mainTaskId) ?: return null
            val incoming = try {
                createWindowMirror(sourceRoot) ?: run { outgoing.release(); return null }
            } catch (error: Throwable) {
                outgoing.release()
                NeXtepLog.warn("native_swap", "Side-rail mirror unavailable", error)
                return null
            }
            if (!outgoing.isValid || !incoming.isValid) {
                outgoing.release()
                incoming.release()
                return null
            }
            return StageLiveSurfaceExchange(
                host,incoming,outgoing,localCrop,
                Rect(0,0,fullScreenWidth,fullScreenHeight),
                from,to,backdrop,
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
    ): Boolean {
        if (finishCallback != null || finished.get()) return false
        val root = host.rootSurfaceControl ?: return cleanupWithoutCallback().let { false }
        finishCallback = onFinished
        try {
            host.addView(background,FrameLayout.LayoutParams(-1,-1))
            val tx = root.buildReparentTransaction(sourceMirror)
                ?: error("No host transaction for incoming live rail")
            val other = root.buildReparentTransaction(mainMirror)
                ?: error("No host transaction for outgoing live task")
            try { tx.merge(other) } finally { other.close() }
            try {
                tx.setLayer(sourceMirror,100001)
                tx.setLayer(mainMirror,100000)
                tx.setCrop(sourceMirror,sourceCrop)
                tx.setCrop(mainMirror,mainCrop)
                tx.setVisibility(sourceMirror,true)
                tx.setVisibility(mainMirror,true)
                applyFrame(tx,0f)
                tx.apply()
            } finally { tx.close() }
        } catch (error: Throwable) {
            NeXtepLog.warn("native_swap", "Native mirror parenting failed before task move", error)
            cleanupWithoutCallback()
            return false
        }

        fun commitMove() {
            if (!isRunning || sentMove) return
            sentMove = true
            if (moveAsync != null) {
                runCatching {
                    moveAsync { ok ->
                        ui.post {
                            if (!isRunning) return@post
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
        animator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=durationMs.toLong().coerceIn(MIN_DURATION_MS,MAX_DURATION_MS)
            interpolator=PathInterpolator(.22f,0f,.12f,1f)
            addUpdateListener {
                if (!isRunning) return@addUpdateListener
                val fraction=(it.animatedValue as Float)
                lastProgress=fraction
                runCatching {
                    SurfaceControl.Transaction().use { frame ->
                        applyFrame(frame,fraction)
                        frame.apply()
                    }
                }.onFailure { error ->
                    NeXtepLog.warn("native_swap","Real dual-layer frame failed",error)
                    stop()
                }
                if (fraction >= MOVE_PROGRESS && !sentMove) commitMove()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isRunning) return
                    frameComplete=true
                    if (!sentMove) commitMove()
                    finishIfReady(liveReady,canRelease)
                }
            })
            start()
        }
        NeXtepLog.info("native_swap","Started TWO real GPU mirror surfaces; no bitmap captures or task recreation")
        return true
    }

    private fun applyFrame(transaction: SurfaceControl.Transaction, p: Float) {
        val incoming=StageLiveSurfaceFlightGeometry.flight(
            slotStart,mainStart,sourceCrop.width(),sourceCrop.height(),p,
        ) ?: error("Invalid incoming native transform")
        val outgoing=StageLiveSurfaceFlightGeometry.flight(
            mainStart,slotStart,mainCrop.width(),mainCrop.height(),p,
        ) ?: error("Invalid outgoing native transform")
        transaction.setScale(sourceMirror,incoming.scale,incoming.scale)
        transaction.setPosition(sourceMirror,
            incoming.x-sourceCrop.left*incoming.scale,
            incoming.y-sourceCrop.top*incoming.scale)
        transaction.setScale(mainMirror,outgoing.scale,outgoing.scale)
        transaction.setPosition(mainMirror,
            outgoing.x-mainCrop.left*outgoing.scale,
            outgoing.y-mainCrop.top*outgoing.scale)
    }

    private fun finishIfReady(liveReady: () -> Boolean, canRelease: () -> Boolean) {
        if (!isRunning || !frameComplete || !moveComplete) return
        fun check() {
            if (!isRunning) return
            val elapsed=SystemClock.uptimeMillis()-moveCompletedAt
            val ready=runCatching { canRelease() && liveReady() }.getOrDefault(false)
            if (ready || elapsed >= HANDOFF_TIMEOUT_MS) {
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
                tx.apply()
            }
        }
        sourceMirror.release()
        mainMirror.release()
        runCatching { (background.parent as? FrameLayout)?.removeView(background) }
    }
}

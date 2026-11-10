package io.github.x647368maker.atomicworkbench.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import io.github.x647368maker.atomicworkbench.config.WorkspaceConfigContract
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceTransitionSpec

/** Own previews only for the exchange: animate both frames, cover the display
 * move, then hand off to the live buffers. Cancellation releases every frame. */
internal class WorkspaceSwapAnimator(private val context: Context) {
    private var overlay: SwapPreviewView? = null
    private var animator: ValueAnimator? = null
    private var completion: (() -> Unit)? = null
    private var releaseAllowed: () -> Boolean = { true }
    private var cancelRequested = false
    private var generation = 0
    private var handoff=StageStableHandoff()

    val isRunning: Boolean get() = overlay != null

    fun animate(
        source: Bitmap?, target: Bitmap?,
        start: RectF, sourceBounds: RectF, targetBounds: RectF,
        host: FrameLayout,
        backdrop: Drawable?,
        stationaryBackground: Bitmap?,
        durationMs: Int,
        returningHome: Boolean,
        sourceQuad: FloatArray?, targetQuad: FloatArray?,
        move: () -> Boolean,
        isLiveReady: () -> Boolean,
        moveAsync: ((((Boolean) -> Unit) -> Unit))? = null,
        canRelease: () -> Boolean = { true },
        freezeGeometry: Boolean = false,
        onFinished: () -> Unit,
    ) {
        cancel()
        check(overlay == null) { "Previous exchange still owns its preview" }
        releaseAllowed = canRelease
        cancelRequested = false
        if (!host.isAttachedToWindow || !host.isHardwareAccelerated || source == null || start.isEmpty ||
            sourceBounds.isEmpty || targetBounds.isEmpty
        ) {
            source?.recycle()
            if (target !== source) target?.recycle()
            stationaryBackground?.recycle()
            if (moveAsync != null) moveAsync { onFinished() }
            else try { move() } finally { onFinished() }
            return
        }
        val token = ++generation
        handoff=StageStableHandoff()
        val preview = SwapPreviewView(context, source, target, start, sourceBounds, targetBounds, backdrop, stationaryBackground,
            sourceQuad, targetQuad, freezeGeometry)
        // Reuse the non-touchable full-display content panel. Creating and
        // removing another system window can disturb HOME visibility on exchange.
        val added = runCatching {
            host.addView(preview, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }.onFailure {
            NeXtepLog.warn("workspace_swap", "Preview host unavailable", it)
        }.isSuccess
        if (!added) {
            preview.release()
            if (moveAsync != null) moveAsync { onFinished() }
            else try { move() } finally { onFinished() }
            return
        }
        overlay = preview
        completion = onFinished
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            // Static tap previews have no animation to play. Start task migration
            // after the existing render-commit callback instead of wasting the
            // configured 200ms on repeated identical frames. Keep HOME and drag
            // animation durations, live-frame handoff and rollback untouched.
            duration = StageSwapTimingPolicy.previewDurationMs(durationMs, freezeGeometry, returningHome)
            interpolator = PathInterpolator(0.22f, 0f, 0.12f, 1f)
            addUpdateListener {
                preview.progress = it.animatedValue as Float
                preview.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != token) return
                    animator = null
                    preview.progress = 1f
                    // Wait for the final preview to reach the render swap chain
                    // before a synchronous Binder move can block task relayout.
                    var moveRequested = false
                    fun commitMove() {
                        if (generation != token || moveRequested) return
                        moveRequested = true
                        fun completed(moved: Boolean) {
                            if (generation != token) return
                            if (!moved || cancelRequested) { cancel(); return }
                            val delay = if (returningHome) HOME_HANDOFF_DELAY_MS else MIN_HANDOFF_DELAY_MS
                            awaitLiveFrame(preview, token, SystemClock.uptimeMillis(), delay, isLiveReady)
                        }
                        if (moveAsync != null) moveAsync(::completed)
                        else completed(runCatching(move).onFailure {
                            NeXtepLog.warn("workspace_swap", "Exchange callback failed", it)
                        }.getOrDefault(false))
                    }
                    runCatching {
                        preview.viewTreeObserver.registerFrameCommitCallback {
                            preview.post { commitMove() }
                        }
                    }.onFailure { NeXtepLog.warn("workspace_swap", "Frame commit callback unavailable", it) }
                    // A cancelled/missing render commit must not lock task exchange forever.
                    preview.postDelayed({ commitMove() }, 160)
                    preview.invalidate()
                }
            })
            start()
        }
    }

    private fun awaitLiveFrame(
        preview: SwapPreviewView,
        token: Int,
        movedAt: Long,
        minimumDelayMs: Long,
        isLiveReady: () -> Boolean,
    ) {
        if (generation != token) return
        val elapsed = SystemClock.uptimeMillis() - movedAt
        val ready = elapsed >= minimumDelayMs && runCatching(isLiveReady).getOrDefault(false)
        val decision=handoff.decide(ready,elapsed >= MAX_HANDOFF_DELAY_MS)
        val stable=decision==StageStableHandoff.Decision.READY
        if (decision!=StageStableHandoff.Decision.WAIT) {
            io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry.handoff(stable,elapsed)
            if (!stable) NeXtepLog.warn("workspace_swap", "Live frame handoff timed out")
            // Reveal the live buffers together on the next frame. Crossfading
            // different app frames creates double edges and makes the tail blur.
            preview.postOnAnimation {
                if (generation == token) cancel()
            }
        } else {
            preview.postOnAnimation { awaitLiveFrame(preview, token, movedAt, minimumDelayMs, isLiveReady) }
        }
    }

    fun cancel() {
        if (!releaseAllowed()) {
            cancelRequested = true
            return
        }
        releaseAllowed = { true }
        generation += 1
        animator?.cancel()
        animator = null
        val previous = overlay
        val finished = completion
        overlay = null
        completion = null
        if (previous != null) {
            runCatching { (previous.parent as? FrameLayout)?.removeView(previous) }
            previous.release()
        }
        finished?.invoke()
    }

    private companion object {
        const val MIN_HANDOFF_DELAY_MS = 80L
        // HOME's wallpaper zoom can continue after the task has resumed.
        const val HOME_HANDOFF_DELAY_MS = WorkspaceTransitionSpec.DURATION_MS + MIN_HANDOFF_DELAY_MS
        const val MAX_HANDOFF_DELAY_MS = 600L
    }
}

private class SwapPreviewView(
    context: Context,
    private val source: Bitmap,
    private val target: Bitmap?,
    private val start: RectF,
    private val sourceBounds: RectF,
    private val targetBounds: RectF,
    private val backdrop: Drawable?,
    private val stationaryBackground: Bitmap?,
    private val sourceQuad: FloatArray?,
    private val targetQuad: FloatArray?,
    private val freezeGeometry: Boolean,
) : View(context) {
    var progress = 0f
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val destination = RectF()
    private val fitted = RectF()
    private val sourceRect = Rect()
    private val projection = android.graphics.Matrix()
    private val outline = Path()
    private val backdropClip = Path().apply {
        addRect(sourceBounds, Path.Direction.CW)
        if (target != null) addRect(targetBounds, Path.Direction.CW)
    }
    private var released = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onDraw(canvas: Canvas) {
        if (released) return
        // Erase the stationary originals only inside the participating windows.
        // Other slots and the control area remain visible throughout the exchange.
        val save = canvas.save()
        canvas.clipPath(backdropClip)
        canvas.drawColor(Color.BLACK)
        backdrop?.setBounds(0, 0, width, height)
        backdrop?.draw(canvas)
        canvas.restoreToCount(save)

        stationaryBackground?.let { canvas.drawBitmap(it, null, sourceBounds, bitmapPaint) }
        if (freezeGeometry) {
            // Tap-to-swap: keep both original window frames perfectly stationary.
            // The real tasks migrate under the preview and appear only at handoff.
            // Do not interpolate width, height or 3D quad for these live windows.
            if (target != null) drawFixedFrame(canvas, target, targetBounds)
            drawFixedFrame(canvas, source, sourceBounds)
            return
        }
        if (target != null) drawFrame(canvas, target, targetBounds, sourceBounds, targetQuad, sourceQuad)
        drawFrame(canvas, source, start, targetBounds, sourceQuad, targetQuad)
    }

    private fun drawFixedFrame(canvas: Canvas, bitmap: Bitmap, bounds: RectF) {
        val fit = StageFixedFrameGeometry.fit(bitmap.width, bitmap.height,
            bounds.left, bounds.top, bounds.right, bounds.bottom)
        destination.set(fit.left, fit.top, fit.right, fit.bottom)
        outline.reset()
        val radius = io.github.x647368maker.atomicworkbench.framework.WindowCornerPolicy.radius(
            bounds.width(), bounds.height(), resources.displayMetrics.density)
        outline.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(outline)
        canvas.drawColor(Color.BLACK)
        // Uniform scale + letterbox. Never stretch video, faces or text.
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
        canvas.restoreToCount(save)
    }

    private fun drawFrame(canvas: Canvas, bitmap: Bitmap, from: RectF, to: RectF,
        fromQuad: FloatArray?, toQuad: FloatArray?) {
        val frame = StageExchangeMotion.frame(
            StageExchangeMotion.Box(from.left, from.top, from.right, from.bottom),
            StageExchangeMotion.Box(to.left, to.top, to.right, to.bottom), progress)
        val box = frame.box
        fun normalized(quad: FloatArray?, bounds: RectF, axis: Int, corner: Int): Float {
            if (quad == null || quad.size != 8 || quad.any { !it.isFinite() }) {
                return if (axis == 0) (if (corner in 1..2) 1f else 0f)
                    else (if (corner >= 2) 1f else 0f)
            }
            return if (axis == 0) (quad[corner * 2] - bounds.left) / bounds.width()
                else (quad[corner * 2 + 1] - bounds.top) / bounds.height()
        }
        val quad = FloatArray(8)
        for (corner in 0..3) {
            for (axis in 0..1) {
                // start may be a drag rectangle; source geometry remains tied to its live card.
                val sourceBase = if (fromQuad === sourceQuad) sourceBounds else targetBounds
                val targetBase = if (toQuad === targetQuad) targetBounds else sourceBounds
                val a = normalized(fromQuad, sourceBase, axis, corner)
                val b = normalized(toQuad, targetBase, axis, corner)
                val unit = a + (b - a) * progress
                quad[corner * 2 + axis] = if (axis == 0) box.left + box.width * unit else box.top + box.height * unit
            }
        }
        val bitmapCorners = floatArrayOf(0f, 0f, bitmap.width.toFloat(), 0f,
            bitmap.width.toFloat(), bitmap.height.toFloat(), 0f, bitmap.height.toFloat())
        if (!projection.setPolyToPoly(bitmapCorners, 0, quad, 0, 4)) return
        outline.reset()
        fitted.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val scale = minOf(box.width / bitmap.width, box.height / bitmap.height).coerceAtLeast(.001f)
        val radius = io.github.x647368maker.atomicworkbench.framework.WindowCornerPolicy.radius(box.width, box.height,
            resources.displayMetrics.density) / scale
        outline.addRoundRect(fitted, radius, radius, Path.Direction.CW)
        outline.transform(projection)
        val save = canvas.save()
        canvas.clipPath(outline)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, projection, bitmapPaint)
        canvas.restoreToCount(save)
    }

    fun release() {
        if (released) return
        released = true
        source.recycle()
        if (target !== source) target?.recycle()
        stationaryBackground?.recycle()
    }
}

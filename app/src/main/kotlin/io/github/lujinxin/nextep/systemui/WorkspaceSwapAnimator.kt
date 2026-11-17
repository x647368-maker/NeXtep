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
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicBoolean
import io.github.x647368maker.atomicworkbench.config.WorkspaceConfigContract
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceTransitionSpec

/** Own previews only for the exchange: animate both frames, cover the display
 * move, then hand off to the live buffers. Cancellation releases every frame. */
internal class WorkspaceSwapAnimator(private val context: Context) {
    private var overlay: SwapPreviewView? = null
    private var animator: ValueAnimator? = null
    private var tapAccentAnimator: ValueAnimator? = null
    private var completion: (() -> Unit)? = null
    private var releaseAllowed: () -> Boolean = { true }
    private var cancelRequested = false
    private var generation = 0
    private var handoff=StageStableHandoff()
    private val ui=Handler(Looper.getMainLooper())
    private var moveObserver:ViewTreeObserver?=null
    private var moveCommit:Runnable?=null
    private var moveDeadline:Runnable?=null
    private var detachListener:View.OnAttachStateChangeListener?=null

    private fun clearMoveAdmission(){
        moveCommit?.let { cb ->
            runCatching { moveObserver?.let { if(it.isAlive)it.unregisterFrameCommitCallback(cb) } }
        }
        moveDeadline?.let { ui.removeCallbacks(it) }
        moveObserver=null;moveCommit=null;moveDeadline=null
    }

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
        uniformTapMorph: Boolean = false,
        sharedMotion: StageSnapshotMotion.PairFlight? = null,
        protectedBounds: List<RectF> = emptyList(),
        opaqueBackdropColor: Int? = null,
        sourceIsPreview: Boolean = true,
        targetIsPreview: Boolean = false,
        onFinished: () -> Unit,
    ) {
        cancel()
        check(overlay == null) { "Previous exchange still owns its preview" }
        releaseAllowed = canRelease
        cancelRequested = false
        // The visual rejection path is still a real async task move. It must
        // finish exactly once on UI even if the producer invokes the callback
        // twice, from a worker, or throws after submitting the move.
        fun fallbackMoveOnce() {
            val finished=AtomicBoolean(false)
            fun finishOnce() {
                if(!finished.compareAndSet(false,true))return
                if(Looper.myLooper()==ui.looper)onFinished()
                else ui.post { onFinished() }
            }
            if(moveAsync!=null)runCatching { moveAsync { finishOnce() } }.onFailure {
                NeXtepLog.warn("workspace_swap","Visual fallback move submission failed",it)
                finishOnce()
            } else try { move() } finally { finishOnce() }
        }
        // A translucent/no-wallpaper preview would expose a stale real task
        // when its contained bitmap leaves an uncovered strip. Never start
        // a Canvas flight without an already prepared opaque backing.
        val backdropReady = StagePreviewFillPolicy.canStart(
            opaqueBackdropColor!=null,backdrop?.opacity==PixelFormat.OPAQUE)
        if (!backdropReady) NeXtepLog.warn("workspace_swap","Canvas preview rejected: opaque backdrop unavailable")
        if (!backdropReady || !host.isAttachedToWindow || !host.isHardwareAccelerated || source == null || start.isEmpty ||
            sourceBounds.isEmpty || targetBounds.isEmpty
        ) {
            source?.recycle()
            if (target !== source) target?.recycle()
            stationaryBackground?.recycle()
            fallbackMoveOnce()
            return
        }
        val token = ++generation
        handoff=StageStableHandoff()
        val preview = SwapPreviewView(context, source, target, start, sourceBounds, targetBounds, backdrop, stationaryBackground,
            sourceQuad, targetQuad, freezeGeometry, uniformTapMorph,
            sharedMotion?.takeIf { uniformTapMorph && !freezeGeometry && !returningHome && target!=null },
            protectedBounds,sourceIsPreview,targetIsPreview,opaqueBackdropColor)
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
            fallbackMoveOnce()
            return
        }
        overlay = preview
        completion = onFinished
        val attachment=object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View)=Unit
            override fun onViewDetachedFromWindow(v:View) {
                // A detached View no longer owns a frame clock. Do not
                // remove children inside framework detach dispatch. If a
                // real migration is pending, cancel() keeps its release gate.
                ui.post { if(generation==token && overlay===preview)cancel() }
            }
        }
        detachListener=attachment
        preview.addOnAttachStateChangeListener(attachment)
        val concurrentTap = StageParallelTapMotionPolicy.concurrent(
            uniformTapMorph, returningHome, ValueAnimator.areAnimatorsEnabled(), moveAsync != null)
        var moveRequested = false
        var moveResultReceived = false
        var movedAt = -1L
        var animationDone = false
        var handoffStarted = false
        fun enterHandoffIfReady() {
            if (generation != token || handoffStarted ||
                !StageParallelTapMotionPolicy.canHandoff(animationDone, movedAt >= 0L)) return
            handoffStarted = true
            val delay = if (returningHome) HOME_HANDOFF_DELAY_MS else MIN_HANDOFF_DELAY_MS
            awaitLiveFrame(preview, token, movedAt, delay, isLiveReady)
        }
        fun completed(moved: Boolean) {
            if(!StageParallelTapMotionPolicy.acceptMoveResult(
                    generation==token && overlay===preview,moveRequested,moveResultReceived))return
            moveResultReceived=true
            if (!moved || cancelRequested) { this@WorkspaceSwapAnimator.cancel(); return }
            movedAt = SystemClock.uptimeMillis()
            enterHandoffIfReady()
        }
        fun receiveMoveResult(moved:Boolean){
            if(Looper.myLooper()==ui.looper)completed(moved)
            else ui.post { completed(moved) }
        }
        fun commitMove() {
            if (generation != token || moveRequested) return
            clearMoveAdmission()
            if(!preview.isAttachedToWindow){cancel();return}
            moveRequested = true
            if (moveAsync != null) runCatching { moveAsync(::receiveMoveResult) }.onFailure {
                NeXtepLog.warn("workspace_swap","Async exchange submission failed",it)
                // A synchronous successful first ACK cannot be retracted.
                receiveMoveResult(false)
            }
            else completed(runCatching(move).onFailure {
                NeXtepLog.warn("workspace_swap", "Exchange callback failed", it)
            }.getOrDefault(false))
        }
        fun scheduleMove() {
            // First rendered preview frame commits before the Binder task move.
            // Full two-way visual movement continues while live buffers migrate.
            if(generation!=token || moveRequested || moveDeadline!=null)return
            val callback=Runnable { ui.post { commitMove() } }
            val deadline=Runnable { commitMove() }
            moveCommit=callback
            moveDeadline=deadline
            runCatching {
                val observer=preview.viewTreeObserver
                moveObserver=observer
                observer.registerFrameCommitCallback(callback)
            }.onFailure { NeXtepLog.warn("workspace_swap", "Frame commit callback unavailable", it) }
            // The deadline belongs to the UI looper, not a detached View.
            // Cancellation unregisters the observer and removes this one shot.
            ui.postDelayed(deadline,160L)
            preview.invalidate()
        }
        // No double effects: this short outline only runs for a fixed
        // stationary preview fallback, not during cross-device morph motion.
        if (StageSwapTapAccentPolicy.shouldAnimate(freezeGeometry, returningHome,
                ValueAnimator.areAnimatorsEnabled())) {
            tapAccentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = StageSwapTapAccentPolicy.DURATION_MS
                addUpdateListener {
                    if (generation == token && overlay === preview) {
                        preview.tapAccentProgress = it.animatedValue as Float
                        preview.invalidate()
                    }
                }
                start()
            }
        }
        if (concurrentTap) scheduleMove()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            // A bounded short visual motion, with no extra delay before
            // real task migration; drag and HOME keep the original timing.
            // Keep an actually visible, continuous scale in BOTH phased and
            // legacy tap paths. The previous async-only condition let a tap
            // choose a frozen one-frame "animation" despite valid geometry.
            duration = if (uniformTapMorph && !returningHome)
                StageParallelTapMotionPolicy.durationMs(durationMs)
                else StageSwapTimingPolicy.previewDurationMs(durationMs, freezeGeometry, returningHome)
            // The certified paired route already owns easing. Do not ease
            // its clock a second time in the final preview renderer.
            interpolator = when {
                // P0-2a: the admitted pair now maps raw time in one per-tap
                // ExchangeMotionPlan, including the certified internal easing.
                preview.usesMotionPlan -> android.animation.TimeInterpolator { it }
                preview.usesSharedRoute -> android.animation.TimeInterpolator { it }
                // Match the snapshot backend clock when the same bounded
                // non-certified paired corridor is admitted.
                preview.usesPairedRoute -> PathInterpolator(.32f,.08f,.36f,1f)
                else -> PathInterpolator(0.22f, 0f, 0.12f, 1f)
            }
            addUpdateListener {
                preview.progress = it.animatedValue as Float
                preview.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != token) return
                    animator = null
                    preview.progress = 1f
                    preview.invalidate()
                    animationDone = true
                    if (!concurrentTap) scheduleMove()
                    else if (!moveRequested) commitMove()
                    enterHandoffIfReady()
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
        if(!preview.isAttachedToWindow){cancel();return}
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
        clearMoveAdmission()
        tapAccentAnimator?.cancel()
        tapAccentAnimator = null
        animator?.cancel()
        animator = null
        val previous = overlay
        val finished = completion
        overlay = null
        completion = null
        val attachment=detachListener
        detachListener=null
        if(attachment!=null)previous?.removeOnAttachStateChangeListener(attachment)
        if (previous != null) {
            val host = previous.parent as? FrameLayout
            runCatching { host?.removeView(previous) }
            if (previous.hasGpuBackedFrame() &&
                host?.isAttachedToWindow == true && host.isHardwareAccelerated) {
                // Bitmap.wrapHardwareBuffer owns a GPU reference used by the
                // most recently submitted RenderThread display list. Wait for
                // the *removal* frame's commit before releasing its buffer.
                // A one-shot main-looper fallback prevents leaks on detach or
                // OEMs that omit frame-commit callbacks; it does not postpone
                // the real-task handoff or schedule any periodic work.
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                val observer = host.viewTreeObserver
                lateinit var onCommitted: Runnable
                val release = Runnable {
                    // The observer owns this callback until it is consumed or
                    // explicitly unregistered. Do not retain an old GPU Bitmap
                    // when the screen locks before the removal frame appears.
                    runCatching {
                        if (observer.isAlive) observer.unregisterFrameCommitCallback(onCommitted)
                    }
                    main.removeCallbacks(onCommitted)
                    previous.release()
                }
                onCommitted = Runnable { main.post(release) }
                runCatching {
                    if (observer.isAlive) {
                        observer.registerFrameCommitCallback(onCommitted)
                        host.invalidate()
                    } else {
                        main.post(release)
                    }
                }.onFailure {
                    NeXtepLog.warn("workspace_swap", "GPU preview release frame callback unavailable", it)
                    main.post(release)
                }
                // One-shot fallback only. No repeating redraw or background loop.
                main.postDelayed(release, GPU_RELEASE_FALLBACK_MS)
            } else previous.release()
        }
        finished?.invoke()
    }

    private companion object {
        const val MIN_HANDOFF_DELAY_MS = 80L
        // HOME's wallpaper zoom can continue after the task has resumed.
        const val HOME_HANDOFF_DELAY_MS = WorkspaceTransitionSpec.DURATION_MS + MIN_HANDOFF_DELAY_MS
        const val MAX_HANDOFF_DELAY_MS = 600L
        const val GPU_RELEASE_FALLBACK_MS = 200L
    }
}

internal class SwapPreviewView(
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
    private val uniformTapMorph: Boolean,
    private val sharedMotion: StageSnapshotMotion.PairFlight?,
    protectedBounds: List<RectF>,
    sourceIsPreview:Boolean=true,
    targetIsPreview:Boolean=false,
    private val opaqueBackdropColor:Int?=null,
) : View(context) {
    private val protectedCards=protectedBounds.map { RectF(it) }
    private val motionPlan=sharedMotion?.let(ExchangeMotionPlan::fromExistingPair)
    val usesMotionPlan get()=motionPlan!=null
    val usesSharedRoute get()=sharedMotion?.usesSharedClock==true
    val usesPairedRoute get()=sharedMotion!=null
    private val upCorners=sharedMotion?.up?.let{StageSnapshotMotion.cornerTrack(it,true,resources.displayMetrics.density)}
    private val downCorners=sharedMotion?.down?.let{StageSnapshotMotion.cornerTrack(it,false,resources.displayMetrics.density)}
    private val sourceFrameRadius=StageSnapshotMotion.endpointRadius(sourceBounds.width(),sourceBounds.height(),
        resources.displayMetrics.density,sourceIsPreview)
    private val targetFrameRadius=StageSnapshotMotion.endpointRadius(targetBounds.width(),targetBounds.height(),
        resources.displayMetrics.density,targetIsPreview)
    var progress = 0f
    var tapAccentProgress = 0f
    private val tapAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2f
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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

    private fun drawSafeBacking(canvas:Canvas) {
        // The opaque sampled wallpaper underlay prevents live-task leakage
        // through a translucent drawable. The full prepared wallpaper is then
        // drawn in display coordinates, including intermediate letterboxes.
        opaqueBackdropColor?.let { canvas.drawColor(it) }
        backdrop?.setBounds(0,0,width,height)
        backdrop?.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        if (released) return
        // Erase the stationary originals only inside the participating windows.
        // Other slots and the control area remain visible throughout the exchange.
        val save = canvas.save()
        canvas.clipPath(backdropClip)
        if(uniformTapMorph)protectedCards.forEach { canvas.clipOutRect(it) }
        drawSafeBacking(canvas)
        canvas.restoreToCount(save)

        stationaryBackground?.let { canvas.drawBitmap(it, null, sourceBounds, bitmapPaint) }
        if (freezeGeometry) {
            // Tap-to-swap: keep both original window frames perfectly stationary.
            // The real tasks migrate under the preview and appear only at handoff.
            // Do not interpolate width, height or 3D quad for these live windows.
            if (target != null) drawFixedFrame(canvas,target,targetBounds,targetFrameRadius)
            drawFixedFrame(canvas,source,sourceBounds,sourceFrameRadius)
            val alpha = StageSwapTapAccentPolicy.opacity(tapAccentProgress)
            if (alpha > 0) {
                tapAccentPaint.color = Color.argb(alpha, 198, 165, 255)
                val radius = resources.displayMetrics.density * 22f
                canvas.drawRoundRect(sourceBounds, radius, radius, tapAccentPaint)
                if (target != null)
                    canvas.drawRoundRect(targetBounds, radius, radius, tapAccentPaint)
            }
            return
        }
        if (uniformTapMorph) {
            val pair=sharedMotion
            val sharedSave=canvas.save()
            protectedCards.forEach { canvas.clipOutRect(it) }
            if(pair!=null && target!=null) {
                // Match either the certified or scored snapshot corridor.
                // Preserve full-size source bitmaps, uniform contain and
                // every protected stationary rail card.
                drawSharedMotionFrame(canvas,target,pair.down,checkNotNull(downCorners))
                drawSharedMotionFrame(canvas,source,pair.up,checkNotNull(upCorners))
                canvas.restoreToCount(sharedSave)
                return
            }
            // All supported form factors use the same two-way movement, but
            // never map the bitmap corners onto a changing quadrilateral.
            // A uniform fit and letterbox protects text/video proportions
            // while the real tasks are moved and their live buffers settle.
            if (target != null) drawUniformMotionFrame(canvas,target,targetBounds,sourceBounds,
                targetFrameRadius,sourceFrameRadius)
            drawUniformMotionFrame(canvas,source,start,targetBounds,sourceFrameRadius,targetFrameRadius)
            canvas.restoreToCount(sharedSave)
            return
        }
        if (target != null) drawFrame(canvas, target, targetBounds, sourceBounds, targetQuad, sourceQuad)
        drawFrame(canvas, source, start, targetBounds, sourceQuad, targetQuad)
    }

    private fun drawSharedMotionFrame(canvas:Canvas, bitmap:Bitmap, flight:StageSnapshotMotion.Flight,
                                      corners:StageSnapshotMotion.CornerTrack) {
        val frameProgress=motionPlan?.geometryProgress(progress) ?: progress
        val pose=flight.pose(frameProgress)
        if(!pose.cx.isFinite() || !pose.cy.isFinite() || !pose.scale.isFinite() || pose.scale<=0f)return
        val halfW=bitmap.width*pose.scale*.5f
        val halfH=bitmap.height*pose.scale*.5f
        destination.set(pose.cx-halfW,pose.cy-halfH,pose.cx+halfW,pose.cy+halfH)
        val radius=corners.visibleRadius(frameProgress,pose.scale)
        outline.reset()
        outline.addRoundRect(destination,radius,radius,Path.Direction.CW)
        val save=canvas.save()
        canvas.clipPath(outline)
        canvas.drawBitmap(bitmap,null,destination,bitmapPaint)
        canvas.restoreToCount(save)
    }

    private fun drawUniformMotionFrame(canvas: Canvas, bitmap: Bitmap, from: RectF, to: RectF,
                                       startRadius:Float,endRadius:Float) {
        val frame = StageExchangeMotion.frame(
            StageExchangeMotion.Box(from.left, from.top, from.right, from.bottom),
            StageExchangeMotion.Box(to.left, to.top, to.right, to.bottom), progress,
        ).box
        if (!frame.left.isFinite() || !frame.top.isFinite() ||
            !frame.right.isFinite() || !frame.bottom.isFinite() ||
            frame.right <= frame.left || frame.bottom <= frame.top) return
        val bounds = RectF(frame.left, frame.top, frame.right, frame.bottom)
        val fit = StagePreviewFillPolicy.frame(bitmap.width, bitmap.height,
            bounds.left, bounds.top, bounds.right, bounds.bottom).content
        destination.set(fit.left, fit.top, fit.right, fit.bottom)
        outline.reset()
        val radius=StageSnapshotMotion.frameRadius(startRadius,endRadius,progress,bounds.width(),bounds.height())
        outline.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(outline)
        drawSafeBacking(canvas)
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
        canvas.restoreToCount(save)
    }

    private fun drawFixedFrame(canvas: Canvas, bitmap: Bitmap, bounds: RectF,endpointRadius:Float) {
        val fit = StagePreviewFillPolicy.frame(bitmap.width, bitmap.height,
            bounds.left, bounds.top, bounds.right, bounds.bottom).content
        destination.set(fit.left, fit.top, fit.right, fit.bottom)
        outline.reset()
        val radius=StageSnapshotMotion.frameRadius(endpointRadius,endpointRadius,0f,bounds.width(),bounds.height())
        outline.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(outline)
        drawSafeBacking(canvas)
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
        drawSafeBacking(canvas)
        canvas.drawBitmap(bitmap, projection, bitmapPaint)
        canvas.restoreToCount(save)
    }

    fun hasGpuBackedFrame(): Boolean =
        source.config == Bitmap.Config.HARDWARE ||
            target?.config == Bitmap.Config.HARDWARE ||
            stationaryBackground?.config == Bitmap.Config.HARDWARE

    fun release() {
        if (released) return
        released = true
        source.recycle()
        if (target !== source) target?.recycle()
        stationaryBackground?.recycle()
    }
}

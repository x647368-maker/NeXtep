package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceGeometry

/** A temporary frozen composition conceals the native migration's intermediate desktop.
 * It never substitutes a completed group: only verified real tasks can reveal the result. */
internal class WorkgroupTransitionCover(
    private val context: Context,
    private val host: () -> FrameLayout?,
    private val blockInput: (Boolean) -> Unit,
) {
    data class Frame(val bitmap: Bitmap?, val corners: FloatArray, val alpha: Float = 1f)
    private val gate = WorkgroupCoverGate()
    private var overlay: FrozenView? = null
    private var token = 0

    fun start(geometry: WorkspaceGeometry, frames: List<Frame>, onCommitted: () -> Unit) {
        cancel()
        val parent = host()
        if (parent == null || !parent.isAttachedToWindow) {
            frames.mapNotNull { it.bitmap }.distinct().forEach { if (!it.isRecycled) it.recycle() }
            error("工作区画面尚未就绪，请重试")
        }
        token = gate.begin()
        val current = token
        val view = FrozenView(context, geometry, frames)
        overlay = view
        try {
            parent.addView(view, FrameLayout.LayoutParams(-1, -1))
            blockInput(true)
        } catch (failure: Throwable) { cancel(); throw failure }
        fun startNative() {
            if (gate.claimStart(current)) onCommitted()
        }
        runCatching {
            view.viewTreeObserver.registerFrameCommitCallback { view.post { startNative() } }
        }
        // The posted callback is a compatibility fallback, guarded against double-start.
        view.postDelayed({ startNative() }, 160)
        view.postDelayed({ if (gate.isCurrent(current)) cancel() }, 35000)
        view.invalidate()
    }

    fun finish(success: Boolean, buffersReady: () -> Boolean) {
        val view = overlay ?: return
        if (!success) { cancel(); return }
        val current = token
        gate.verified(current, SystemClock.uptimeMillis())
        fun awaitBuffers() {
            if (!gate.isCurrent(current)) return
            when (gate.reveal(current, SystemClock.uptimeMillis(), runCatching(buffersReady).getOrDefault(false))) {
                WorkgroupCoverGate.Reveal.WAIT -> view.postDelayed({ awaitBuffers() }, 32)
                WorkgroupCoverGate.Reveal.READY -> {
                    view.animate().alpha(0f).setDuration(100).withEndAction {
                        if (gate.isCurrent(current)) cancel()
                    }.start()
                }
                WorkgroupCoverGate.Reveal.LATE -> {
                    cancel()
                    Toast.makeText(context, "任务已切换，部分画面仍在刷新", Toast.LENGTH_SHORT).show()
                }
            }
        }
        awaitBuffers()
    }

    fun cancel() {
        gate.cancel()
        val view = overlay
        overlay = null
        if (view != null) {
            view.animate().cancel()
            (view.parent as? FrameLayout)?.removeView(view)
            view.release()
        }
        blockInput(false)
    }

    private class FrozenView(context: Context, geometry: WorkspaceGeometry, private val frames: List<Frame>) : View(context) {
        private val area = RectF((if (geometry.isLandscape) geometry.contentLeft else geometry.availableLeft).toFloat(),
            (if (geometry.isLandscape) geometry.availableTop else geometry.sidebarTop).toFloat(),
            (if (geometry.isLandscape) geometry.contentRight else geometry.availableRight).toFloat(), geometry.availableBottom.toFloat())
        private val labelX = geometry.contentLeft.toFloat() + 18 * resources.displayMetrics.density
        private val labelY = geometry.contentTop.toFloat() + 35 * resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val projection = Matrix()
        private val outline = Path()
        private var released = false

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        override fun onTouchEvent(event: MotionEvent) = true

        override fun onDraw(canvas: Canvas) {
            if (released) return
            paint.alpha = 255
            paint.color = Color.rgb(17, 23, 32)
            canvas.drawRect(area, paint)
            frames.forEach { frame ->
                val q = frame.corners
                if (q.size != 8 || q.any { !it.isFinite() }) return@forEach
                outline.reset(); outline.moveTo(q[0], q[1])
                for (i in 1..3) outline.lineTo(q[i * 2], q[i * 2 + 1])
                outline.close()
                paint.color = Color.rgb(17, 23, 32)
                paint.alpha = 255
                canvas.drawPath(outline, paint)
                val bitmap = frame.bitmap ?: return@forEach
                val from = floatArrayOf(0f, 0f, bitmap.width.toFloat(), 0f,
                    bitmap.width.toFloat(), bitmap.height.toFloat(), 0f, bitmap.height.toFloat())
                if (projection.setPolyToPoly(from, 0, q, 0, 4)) {
                    paint.alpha = (frame.alpha.coerceIn(0f, 1f) * 255).toInt()
                    canvas.drawBitmap(bitmap, projection, paint)
                }
            }
            paint.alpha = 255
            paint.textSize = 14 * resources.displayMetrics.scaledDensity
            val label = "正在切换工作组…"
            val padding = 10 * resources.displayMetrics.density
            paint.color = Color.argb(235, 24, 32, 44)
            canvas.drawRoundRect(labelX - padding, labelY - paint.textSize - padding,
                labelX + paint.measureText(label) + padding, labelY + padding, padding, padding, paint)
            paint.color = Color.WHITE
            canvas.drawText(label, labelX, labelY, paint)
        }

        fun release() {
            if (released) return
            released = true
            frames.mapNotNull { it.bitmap }.distinct().forEach { if (!it.isRecycled) it.recycle() }
        }
    }
}

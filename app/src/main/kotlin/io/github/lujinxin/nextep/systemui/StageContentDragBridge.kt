package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import io.github.x647368maker.atomicworkbench.display.AppDragContract

/** Hover-raise for real Android GLOBAL drags. Native apps own ClipData and drop permissions.
 * We never synthesize a drop, read a URI or report a preview-only transfer as successful. */
internal class StageContentDragBridge(private val context: Context,
    private val candidate: (Float, Float) -> Pair<Int, Int>?,
    private val promote: (Int, Int) -> Boolean,
) : View.OnDragListener {
    private val handler = Handler(Looper.getMainLooper())
    private val policy = StageDragHoverPolicy()
    private var current: Pair<Int, Int>? = null
    private var accepted = false
    private var promoted = false
    private val dwell = Runnable {
        val target = current
        if (accepted && !promoted && target != null) {
            val slot = policy.update(target.first, target.second, SystemClock.uptimeMillis())
            if (slot != null && promote(slot, target.second)) promoted = true
        }
    }
    fun cancel() {
        handler.removeCallbacks(dwell); policy.reset(); current = null; accepted = false; promoted = false
    }
    override fun onDrag(view: View, event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                cancel()
                val description = event.clipDescription
                accepted = !AppDragContract.accepts(description) && description != null &&
                    (description.hasMimeType("text/*") || description.hasMimeType("image/*") || description.hasMimeType("application/*"))
                return accepted
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                if (!accepted) return false
                val point = screenPoint(view, event.x, event.y)
                val next = candidate(point.x, point.y)
                if (next != current) {
                    handler.removeCallbacks(dwell); current = next
                    policy.update(next?.first, next?.second, SystemClock.uptimeMillis())
                    if (next != null && !promoted) handler.postDelayed(dwell, 570)
                }
                return true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                handler.removeCallbacks(dwell); current = null; policy.reset(); promoted = false
                return accepted
            }
            DragEvent.ACTION_DROP -> {
                if (accepted) Toast.makeText(context, "移入主窗口后松手，由目标应用接收内容", Toast.LENGTH_SHORT).show()
                return false
            }
            DragEvent.ACTION_DRAG_ENDED -> { cancel(); return true }
            else -> return accepted
        }
    }
    private fun screenPoint(view: View, x: Float, y: Float): PointF {
        val values = floatArrayOf(x, y)
        var current: View = view
        while (true) {
            current.matrix.mapPoints(values)
            val parent = current.parent as? View ?: break
            values[0] += current.left - parent.scrollX; values[1] += current.top - parent.scrollY
            current = parent
        }
        val origin = IntArray(2); current.getLocationOnScreen(origin)
        return PointF(values[0] + origin[0], values[1] + origin[1])
    }
}

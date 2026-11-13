package io.github.x647368maker.atomicworkbench.systemui

import kotlin.math.min

/**
 * Physical display-pixel coordinates for two real compositor surfaces.
 * Cropped mirrors retain their own task/input identity: animation here moves
 * only what SurfaceFlinger composites, never the task display or touch bridge.
 */
internal object StageLiveSurfaceFlightGeometry {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val cx: Float get() = (left + right) * .5f
        val cy: Float get() = (top + bottom) * .5f
        fun valid(): Boolean = listOf(left, top, right, bottom).all(Float::isFinite) &&
            width >= 2f && height >= 2f
    }
    data class Placement(val scale: Float, val x: Float, val y: Float, val box: Box)

    fun flight(from: Box, to: Box, sourceWidth: Int, sourceHeight: Int,
               progress: Float): Placement? {
        if (!from.valid() || !to.valid() || sourceWidth < 2 || sourceHeight < 2 ||
            !progress.isFinite()) return null
        val t = progress.coerceIn(0f, 1f)
        // Interpolate the visible card frame, then *uniformly* fit the actual
        // compositor source. Never stretch video, faces or text to fill a box.
        val frame=Box(
            from.left+(to.left-from.left)*t,
            from.top+(to.top-from.top)*t,
            from.right+(to.right-from.right)*t,
            from.bottom+(to.bottom-from.bottom)*t,
        )
        val s=min(frame.width/sourceWidth,frame.height/sourceHeight)
        if (!s.isFinite() || s <= 0f || s > 10f) return null
        return Placement(s,frame.cx-sourceWidth*s*.5f,
            frame.cy-sourceHeight*s*.5f,frame)
    }

    /** Mirror crop is in the original source window's local coordinates. */
    fun localCrop(frame: Box, rootLeft: Int, rootTop: Int, rootWidth: Int,
                  rootHeight: Int): android.graphics.Rect? {
        if (!frame.valid() || rootWidth < 2 || rootHeight < 2) return null
        val l=(frame.left-rootLeft).toInt()
        val t=(frame.top-rootTop).toInt()
        val r=(frame.right-rootLeft).toInt()
        val b=(frame.bottom-rootTop).toInt()
        if (l < 0 || t < 0 || r > rootWidth || b > rootHeight || r-l < 2 || b-t < 2) return null
        return android.graphics.Rect(l,t,r,b)
    }
}

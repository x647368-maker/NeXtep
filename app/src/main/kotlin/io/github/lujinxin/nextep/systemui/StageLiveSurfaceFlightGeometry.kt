package io.github.x647368maker.atomicworkbench.systemui

import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI

/** Two live compositor cards retain independent aspect ratios and use opposite zero-endpoint arcs. */
internal object StageLiveSurfaceFlightGeometry {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val cx: Float get() = (left + right) * .5f
        val cy: Float get() = (top + bottom) * .5f
        fun valid(): Boolean = left.isFinite() && top.isFinite() &&
            right.isFinite() && bottom.isFinite() && width >= 2f && height >= 2f
    }
    data class Placement(val scale: Float, val x: Float, val y: Float, val box: Box)

    fun flight(from: Box, to: Box, sourceWidth: Int, sourceHeight: Int,
               progress: Float, arcOffsetPx: Float = 0f): Placement? {
        if (!from.valid() || !to.valid() || sourceWidth < 2 || sourceHeight < 2 ||
            !progress.isFinite() || !arcOffsetPx.isFinite()) return null
        val t = progress.coerceIn(0f, 1f)
        val fromScale = min(from.width / sourceWidth, from.height / sourceHeight)
        val toScale = min(to.width / sourceWidth, to.height / sourceHeight)
        val scale = fromScale + (toScale - fromScale) * t
        if (!scale.isFinite() || scale <= 0f || scale > 10f) return null
        val dx = to.cx - from.cx
        val dy = to.cy - from.cy
        val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val arc = sin(PI.toFloat() * t) * arcOffsetPx
        val cx = from.cx + dx * t - dy / length * arc
        val cy = from.cy + dy * t + dx / length * arc
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        val box = Box(cx - width*.5f, cy - height*.5f, cx + width*.5f, cy + height*.5f)
        return Placement(scale, box.left, box.top, box)
    }

    data class Crop(val left:Int,val top:Int,val right:Int,val bottom:Int) {
        fun toAndroidRect() = android.graphics.Rect(left,top,right,bottom)
    }
    fun localCrop(frame: Box, rootLeft: Int, rootTop: Int, rootWidth: Int,
                  rootHeight: Int): Crop? {
        if (!frame.valid() || rootWidth < 2 || rootHeight < 2) return null
        val l=(frame.left-rootLeft).toInt()
        val t=(frame.top-rootTop).toInt()
        val r=(frame.right-rootLeft).toInt()
        val b=(frame.bottom-rootTop).toInt()
        if (l < 0 || t < 0 || r > rootWidth || b > rootHeight || r-l < 2 || b-t < 2) return null
        return Crop(l,t,r,b)
    }
}

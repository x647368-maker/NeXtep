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

    /**
     * PR66: protect the remaining three slot cards. Avoid a straight diagonal
     * sweep that can cover every slot between the selected card and the main
     * task. The card remains small while crossing the rail boundary, and only
     * changes size inside the main task's own column/row.
     *
     * No extra frames or surfaces: one affine transform per original GPU card.
     */
    fun railCorridorFlight(
        from: Box, to: Box, sourceWidth: Int, sourceHeight: Int,
        progress: Float,
    ): Placement? {
        if (!from.valid() || !to.valid() || sourceWidth < 2 || sourceHeight < 2 ||
            !progress.isFinite()) return null
        val fromArea = from.width * from.height
        val toArea = to.width * to.height
        if (fromArea == toArea) return null
        val sourceSmall = fromArea < toArea
        val small = if (sourceSmall) from else to
        val main = if (sourceSmall) to else from
        // Real ColorOS cards may slightly OVERLAP in their outer bounding
        // rectangles because the main area extends under the side rail.
        // What matters is which region owns the small card's CENTER.
        // Reject only an actual ambiguous same-region layout, not a 1px
        // border/shadow intersection of two independently visible cards.
        val railLeftRight = small.cx < main.left || small.cx > main.right
        val railTopBottom = small.cy < main.top || small.cy > main.bottom
        if (!railLeftRight && !railTopBottom) return null
        val byHorizontal = when {
            railLeftRight && !railTopBottom -> true
            railTopBottom && !railLeftRight -> false
            else -> {
                // A diagonally separate card uses the axis with greater
                // normalized off-main distance, not raw rectangle overlap.
                val xDistance = if (small.cx < main.left) main.left-small.cx
                    else small.cx-main.right
                val yDistance = if (small.cy < main.top) main.top-small.cy
                    else small.cy-main.bottom
                xDistance / main.width >= yDistance / main.height
            }
        }
        val fromScale = min(from.width / sourceWidth, from.height / sourceHeight)
        val toScale = min(to.width / sourceWidth, to.height / sourceHeight)
        if (fromScale <= 0f || toScale <= 0f) return null
        val t = progress.coerceIn(0f,1f)
        // Smoothstep is stationary at the phase boundary, avoiding a sharp
        // 90-degree velocity kink while keeping the whole motion monotonic.
        fun smooth(x: Float): Float {
            val u=x.coerceIn(0f,1f)
            return u*u*(3f-2f*u)
        }
        fun blend(a:Float,b:Float,u:Float) = a+(b-a)*u
        val cx:Float
        val cy:Float
        val scale:Float
        if (sourceSmall) {
            val split=.34f
            if(t<split) {
                val u=smooth(t/split)
                cx=if(byHorizontal) blend(small.cx,main.cx,u) else small.cx
                cy=if(byHorizontal) small.cy else blend(small.cy,main.cy,u)
                scale=fromScale
            } else {
                val u=smooth((t-split)/(1f-split))
                cx=if(byHorizontal) main.cx else blend(small.cx,main.cx,u)
                cy=if(byHorizontal) blend(small.cy,main.cy,u) else main.cy
                scale=blend(fromScale,toScale,u)
            }
        } else {
            val split=.66f
            if(t<split) {
                val u=smooth(t/split)
                cx=if(byHorizontal) main.cx else blend(main.cx,small.cx,u)
                cy=if(byHorizontal) blend(main.cy,small.cy,u) else main.cy
                scale=blend(fromScale,toScale,u)
            } else {
                val u=smooth((t-split)/(1f-split))
                cx=if(byHorizontal) blend(main.cx,small.cx,u) else small.cx
                cy=if(byHorizontal) small.cy else blend(main.cy,small.cy,u)
                scale=toScale
            }
        }
        if (!scale.isFinite() || scale<=0f || scale>10f) return null
        val w=sourceWidth*scale
        val h=sourceHeight*scale
        val box=Box(cx-w*.5f,cy-h*.5f,cx+w*.5f,cy+h*.5f)
        return Placement(scale,box.left,box.top,box)
    }

    private fun overlaps(a:Box,b:Box):Boolean =
        a.left < b.right && a.right > b.left &&
        a.top < b.bottom && a.bottom > b.top

    /** Strict, real-content no-collision gate. Never trade another slot's pixels
     * or touch mapping for a prettier flight. Unknown geometry falls back to
     * the existing single real task swap without this GPU flight.
     */
    fun corridorAvoidsOtherSlots(
        small:Box, main:Box,
        smallCropWidth:Int,smallCropHeight:Int,
        otherSlots:List<Box>,
        sampleCount:Int=24,
    ):Boolean {
        if(!small.valid() || !main.valid() || sampleCount<4 || sampleCount>240 ||
            otherSlots.any { !it.valid() }) return false
        for(i in 0..sampleCount) {
            val progress=i.toFloat()/sampleCount
            val up=railCorridorFlight(small,main,smallCropWidth,smallCropHeight,progress)
                ?: return false
            val down=railCorridorFlight(main,small,main.width.toInt(),main.height.toInt(),progress)
                ?: return false
            if(otherSlots.any { overlaps(up.box,it) || overlaps(down.box,it) }) return false
        }
        return true
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

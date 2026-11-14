package io.github.x647368maker.atomicworkbench.systemui

import kotlin.math.*

/** Temporary snapshot trajectory, no writes to live task geometry.
 * One continuous center path, a bounded opposing arc, and independent
 * smooth size curves. Protect other cards by clipping at the parent.
 */
internal object StageSnapshotMotion {
    data class Box(val left:Float,val top:Float,val right:Float,val bottom:Float){
        val w get()=right-left
        val h get()=bottom-top
        val cx get()=(left+right)*.5f
        val cy get()=(top+bottom)*.5f
        fun valid()=w>=2f && h>=2f && listOf(left,top,right,bottom).all{it.isFinite()}
    }
    data class Pose(val cx:Float,val cy:Float,val scale:Float)
    data class Flight(val from:Box,val to:Box,val width:Int,val height:Int,
                      val arc:Float,val growing:Boolean){
        private val s0=min(from.w/width,from.h/height)
        private val s1=min(to.w/width,to.h/height)
        private val dx=to.cx-from.cx
        private val dy=to.cy-from.cy
        private val length=hypot(dx,dy).coerceAtLeast(1f)
        val start get()=pose(0f)
        val end get()=pose(1f)
        fun pose(progress:Float):Pose{
            val p=progress.coerceIn(0f,1f)
            // Sin^2 has zero slope at both endpoints; opposite travel directions
            // imply opposite lateral offsets. No stationary phase or 90deg bend.
            val bend=sin(PI.toFloat()*p).let{it*it}*arc
            // Small card grows slightly later; outgoing card shrinks earlier.
            // Both have strictly monotonic size and zero overshoot.
            val u=if(growing) p*p*(2f-p) else 1f-(1f-p)*(1f-p)*(1f+p)
            return Pose(from.cx+dx*p-dy/length*bend,
                from.cy+dy*p+dx/length*bend,s0+(s1-s0)*u)
        }
    }
    fun flight(from:Box,to:Box,width:Int,height:Int):Flight?{
        if(!from.valid() || !to.valid() || width<2 || height<2)return null
        val distance=hypot(to.cx-from.cx,to.cy-from.cy)
        if(distance<2f)return null
        val growing=to.w*to.h>from.w*from.h
        // Short, restrained curve even on a tablet, relative to selected card.
        val small=if(growing)from else to
        val large=if(growing)to else from
        val arc=minOf(min(small.w,small.h)*.40f,min(large.w,large.h)*.10f,distance*.12f)
        return Flight(from,to,width,height,arc,growing)
    }
    /** Hardware animator interpolates scalar start/end. For parallel axes
     * use a one-pixel terminal delta, then restore exact endpoint on commit.
     * This permits the same small arc even when center coordinates coincide. */
    fun hardwareEnd(start:Float,end:Float):Float=
        if(abs(end-start)<1f)start+1f else end
}

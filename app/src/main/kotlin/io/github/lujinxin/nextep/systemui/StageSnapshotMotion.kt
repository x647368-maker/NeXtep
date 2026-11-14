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
                      val arc:Float,val growing:Boolean,val sizeBias:Float=0f){
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
            val q=if(growing)p else 1f-p
            val gentle=q*q*(2f-q)
            val compact=q*q*q
            val fraction=gentle+(compact-gentle)*sizeBias
            val u=if(growing)fraction else 1f-fraction
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
    data class PairFlight(val up:Flight,val down:Flight)
    private fun rectangle(f:Flight,p:Float):Box{
        val c=f.pose(p)
        val hw=f.width*c.scale*.5f
        val hh=f.height*c.scale*.5f
        return Box(c.cx-hw,c.cy-hh,c.cx+hw,c.cy+hh)
    }
    private fun area(b:Box)=max(0f,b.w)*max(0f,b.h)
    private fun intersect(a:Box,b:Box)=Box(max(a.left,b.left),max(a.top,b.top),
        min(a.right,b.right),min(a.bottom,b.bottom))
    // Protected rail rectangles are disjoint in normal layouts; even if
    // transiently overlapping, the conservative estimate cannot overclaim pixels.
    private fun visible(box:Box,screen:Box,excluded:List<Box>):Float{
        val clipped=intersect(box,screen)
        return (area(clipped)-excluded.sumOf{area(intersect(clipped,it)).toDouble()}.toFloat()).coerceAtLeast(0f)
    }
    internal fun visibleFractions(pair:PairFlight,p:Float,screen:Box,excluded:List<Box>):Pair<Float,Float>{
        val a=rectangle(pair.up,p)
        val b=rectangle(pair.down,p)
        val up=visible(a,screen,excluded)/area(a).coerceAtLeast(1f)
        val down=(visible(b,screen,excluded)-visible(intersect(a,b),screen,excluded))
            .coerceAtLeast(0f)/area(b).coerceAtLeast(1f)
        return up.coerceIn(0f,1f) to down.coerceIn(0f,1f)
    }
    private fun score(pair:PairFlight,screen:Box,excluded:List<Box>,baseArc:Float):Float{
        var minUp=1f;var minDown=1f;var totalUp=0f;var totalDown=0f
        // 31 interior samples catch brief overlap/occlusion between key poses.
        for(i in 1..31){
            val (u,d)=visibleFractions(pair,i/32f,screen,excluded)
            minUp=min(minUp,u);minDown=min(minDown,d)
            totalUp+=u;totalDown+=d
        }
        return 2f*minUp+3f*minDown+(totalUp+totalDown)/31f-
            .08f*abs(pair.up.arc)/baseArc.coerceAtLeast(1f)-.12f*pair.up.sizeBias
    }
    /**
     * Pick a bounded corridor ONCE per exchange, not on each render frame.
     * Both cards retain uniform scale, exact endpoints and strictly advancing
     * center-line progress. The route is a property of the physical layout,
     * not the apps being swapped. Use the viewport aspect for both scoring
     * cards, so identical endpoint/protected geometry always chooses exactly
     * the same signed arc and easing bias. Real bitmaps retain their actual
     * dimensions and uniform endpoint scale; no bitmap is resized to score.
     */
    fun pairedFlights(small:Box,main:Box,iw:Int,ih:Int,ow:Int,oh:Int,
                      screen:Box,excluded:List<Box>):PairFlight?{
        val u=flight(small,main,iw,ih)?:return null
        val d=flight(main,small,ow,oh)?:return null
        val original=PairFlight(u,d)
        if(!screen.valid())return original
        // Score canonical screen-shaped cards only. Previously changing
        // captured source aspect could reverse the arc at an unchanged slot.
        // No extra candidates, bitmap capture, state cache or per-frame work.
        val canonicalWidth=screen.w.toInt().coerceAtLeast(2)
        val canonicalHeight=screen.h.toInt().coerceAtLeast(2)
        val routeUp=u.copy(width=canonicalWidth,height=canonicalHeight)
        val routeDown=d.copy(width=canonicalWidth,height=canonicalHeight)
        var best=PairFlight(routeUp,routeDown)
        var bestScore=score(best,screen,excluded,u.arc)
        val maxArc=minOf(min(screen.w,screen.h)*.22f,
            min(main.w,main.h)*.34f,hypot(main.cx-small.cx,main.cy-small.cy)*.32f)
        for(factor in floatArrayOf(1f,-1f,2f,-2f,3f,-3f)){
            val arc=(u.arc*factor).coerceIn(-maxArc,maxArc)
            for(bias in floatArrayOf(0f,.35f,.70f)){
                val candidate=PairFlight(routeUp.copy(arc=arc,sizeBias=bias),
                    routeDown.copy(arc=arc,sizeBias=bias))
                val rating=score(candidate,screen,excluded,u.arc)
                if(rating>bestScore+.0001f){
                    best=candidate;bestScore=rating
                }
            }
        }
        // Transfer only the geometric route, never canonical bitmap sizes.
        return PairFlight(u.copy(arc=best.up.arc,sizeBias=best.up.sizeBias),
            d.copy(arc=best.down.arc,sizeBias=best.down.sizeBias))
    }
    /** Hardware animator interpolates scalar start/end. For parallel axes
     * use a subpixel terminal delta, then restore exact endpoint on commit.
     * This permits the same small arc even when center coordinates coincide. */
    fun hardwareEnd(start:Float,end:Float,epsilon:Float):Float=
        if(abs(end-start)<epsilon)start+epsilon else end
}

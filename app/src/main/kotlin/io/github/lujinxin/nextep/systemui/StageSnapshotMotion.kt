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
                      val arc:Float,val growing:Boolean,val sizeBias:Float=0f,val crossingCompactness:Float=0f,
                      val sharedPlan:SmoothPairedRailCandidate.Plan?=null,val sharedIncoming:Boolean=false){
        private val s0=min(from.w/width,from.h/height)
        private val s1=min(to.w/width,to.h/height)
        private val dx=to.cx-from.cx
        private val dy=to.cy-from.cy
        private val length=hypot(dx,dy).coerceAtLeast(1f)
        val start get()=pose(0f)
        val end get()=pose(1f)
        fun pose(progress:Float):Pose{
            val p=progress.coerceIn(0f,1f)
            sharedPlan?.let { plan ->
                // Certified GPU route owns the easing. Full snapshot stays contained.
                if(p==0f)return Pose(from.cx,from.cy,s0)
                if(p==1f)return Pose(to.cx,to.cy,s1)
                val pair=plan.at(p)
                val box=if(sharedIncoming)pair.incoming else pair.outgoing
                return Pose(box.cx,box.cy,min(box.width/width,box.height/height))
            }
            // Sin^2 has zero slope at both endpoints; opposite travel directions
            // imply opposite lateral offsets. No stationary phase or 90deg bend.
            val bend=sin(PI.toFloat()*p).let{it*it}*arc
            // Small card grows slightly later; outgoing card shrinks earlier.
            // Both have strictly monotonic size and zero overshoot.
            val q=if(growing)p else 1f-p
            val gentle=q*q*(2f-q)
            val compact=q*q*q
            val fraction=gentle+(compact-gentle)*sizeBias
            // Only the in-flight size changes. Both endpoint scales and center route
            // remain exact. The bounded compactness curve is monotonic.
            val adjusted=fraction*(1f-crossingCompactness*4f*q*(1f-q))
            val u=if(growing)adjusted else 1f-adjusted
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
    // Roundness belongs to the physical visible endpoint, not to the
    // captured bitmap. Keep the live slot's preview-radius policy and the
    // main-window policy distinct, then interpolate in visible pixels.
    data class CornerTrack(val startRadius:Float,val endRadius:Float,val maxSourceRadius:Float){
        fun visibleRadius(progress:Float,scale:Float):Float {
            val p=progress.coerceIn(0f,1f)
            return (startRadius+(endRadius-startRadius)*p).coerceIn(0f,maxSourceRadius*scale)
        }
        fun sourceRadius(progress:Float,scale:Float):Float =
            if(scale.isFinite() && scale>0f)visibleRadius(progress,scale)/scale else 0f
    }
    fun endpointRadius(width:Float,height:Float,density:Float,preview:Boolean):Float =
        if(preview)io.github.x647368maker.atomicworkbench.framework.WindowCornerPolicy.previewRadius(width,height,density)
        else io.github.x647368maker.atomicworkbench.framework.WindowCornerPolicy.radius(width,height,density)
    // Unpaired/fixed previews clip the outer card (including contain bars),
    // not the fitted bitmap. No route planner or per-frame track allocation.
    fun frameRadius(start:Float,end:Float,progress:Float,width:Float,height:Float):Float =
        (start+(end-start)*progress.coerceIn(0f,1f)).coerceIn(0f,min(width,height).coerceAtLeast(0f)*.5f)
    fun cornerTrack(flight:Flight,smallAtStart:Boolean,density:Float):CornerTrack =
        CornerTrack(endpointRadius(flight.from.w,flight.from.h,density,smallAtStart),
            endpointRadius(flight.to.w,flight.to.h,density,!smallAtStart),
            min(flight.width,flight.height)*.5f)
    data class PairFlight(val up:Flight,val down:Flight){
        val usesSharedClock:Boolean get()=up.sharedPlan!=null && up.sharedPlan===down.sharedPlan
    }
    // Same admission and clock as GPU; full bitmap contain is not dynamic GPU crop.
    fun sharedFlights(small:Box,main:Box,iw:Int,ih:Int,ow:Int,oh:Int,
                      screen:Box,excluded:List<Box>,withinBudget:()->Boolean):PairFlight? {
        val u=flight(small,main,iw,ih)?:return null
        val d=flight(main,small,ow,oh)?:return null
        if(!withinBudget() || !screen.valid())return null
        fun convert(b:Box)=StageLiveSurfaceFlightGeometry.Box(b.left,b.top,b.right,b.bottom)
        val a=convert(small);val z=convert(main);val view=convert(screen)
        val obstacles=excluded.map(::convert)
        val ic=StageLiveSurfaceFlightGeometry.Crop(0,0,iw,ih)
        val oc=StageLiveSurfaceFlightGeometry.Crop(0,0,ow,oh)
        val covered=StageLiveSurfaceFlightGeometry.canUseCoveredFlight(a,z,ic,oc,obstacles)
        val direct = if (covered) StageAdaptiveCoveredRailRoute.straightCrossing(
            a, z, ic, oc, view, obstacles, withinBudget,
        ) else null
        val plan=direct ?: StageCertifiedRailRoute.choose(a,z,ic,oc,view,obstacles,covered,withinBudget)
            ?: (if(covered && withinBudget())
                StageAdaptiveCoveredRailRoute.choose(a,z,ic,oc,view,obstacles,withinBudget) else null)
            ?:return null
        // Reject any change to the existing snapshot endpoints.
        fun matches(box:StageLiveSurfaceFlightGeometry.Box,pose:Pose,w:Int,h:Int):Boolean =
            abs(box.cx-pose.cx)<=.02f && abs(box.cy-pose.cy)<=.02f &&
                abs(min(box.width/w,box.height/h)-pose.scale)*max(w,h)<=.02f
        val first=plan.at(0f);val last=plan.at(1f)
        if(!matches(first.incoming,u.start,iw,ih)||!matches(last.incoming,u.end,iw,ih)||
           !matches(first.outgoing,d.start,ow,oh)||!matches(last.outgoing,d.end,ow,oh)||!withinBudget())return null
        return PairFlight(u.copy(sharedPlan=plan,sharedIncoming=true),d.copy(sharedPlan=plan))
    }
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
    private fun score(pair:PairFlight,screen:Box,excluded:List<Box>,baseArc:Float,
                      withinBudget:()->Boolean):Float?{
        var minUp=1f;var minDown=1f;var totalUp=0f;var totalDown=0f
        // 31 interior samples catch brief overlap/occlusion between key poses.
        for(i in 1..31){
            if(!withinBudget())return null
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
                      screen:Box,excluded:List<Box>,withinBudget:()->Boolean={true}):PairFlight?{
        if(!withinBudget())return null
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
        var bestScore=score(best,screen,excluded,u.arc,withinBudget)?:return null
        val maxArc=minOf(min(screen.w,screen.h)*.22f,
            min(main.w,main.h)*.34f,hypot(main.cx-small.cx,main.cy-small.cy)*.32f)
        for(factor in floatArrayOf(1f,-1f,2f,-2f,3f,-3f)){
            val arc=(u.arc*factor).coerceIn(-maxArc,maxArc)
            for(bias in floatArrayOf(0f,.35f,.70f)){
                val candidate=PairFlight(routeUp.copy(arc=arc,sizeBias=bias),
                    routeDown.copy(arc=arc,sizeBias=bias))
                val rating=score(candidate,screen,excluded,u.arc,withinBudget)?:return null
                if(rating>bestScore+.0001f){
                    best=candidate;bestScore=rating
                }
            }
        }
        // Transfer only the geometric route, never canonical bitmap sizes.
        val chosen=PairFlight(u.copy(arc=best.up.arc,sizeBias=best.up.sizeBias),
            d.copy(arc=best.down.arc,sizeBias=best.down.sizeBias))
        // Keep the physical signed route fixed. Score only the crossing-size
        // adjustment against real source dimensions, once on the capture worker.
        val baseline=(1..31).map{
            if(!withinBudget())return null
            visibleFractions(chosen,it/32f,screen,excluded)
        }
        val minUp=baseline.minOf{it.first};val minDown=baseline.minOf{it.second}
        if(minUp>=.95f && minDown>=.95f)return chosen.takeIf{withinBudget()}
        var result=chosen;var bestGain=.05f
        for(compactness in floatArrayOf(.12f,.24f)){
            val candidate=PairFlight(chosen.up.copy(crossingCompactness=compactness),
                chosen.down.copy(crossingCompactness=compactness))
            val fractions=(1..31).map{
                if(!withinBudget())return null
                visibleFractions(candidate,it/32f,screen,excluded)
            }
            if(fractions.indices.any{fractions[it].first+.001f<baseline[it].first ||
                    fractions[it].second+.001f<baseline[it].second})continue
            val gain=(fractions.minOf{it.first}-minUp)+(fractions.minOf{it.second}-minDown)
            if(gain>bestGain){result=candidate;bestGain=gain}
        }
        return result.takeIf{withinBudget()}
    }
    /** Hardware animator interpolates scalar start/end. For parallel axes
     * use a subpixel terminal delta, then restore exact endpoint on commit.
     * This permits the same small arc even when center coordinates coincide. */
    fun hardwareEnd(start:Float,end:Float,epsilon:Float):Float=
        if(abs(end-start)<epsilon)start+epsilon else end
}

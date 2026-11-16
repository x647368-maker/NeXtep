package io.github.x647368maker.atomicworkbench.systemui

import kotlin.math.*
import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Box
import io.github.x647368maker.atomicworkbench.systemui.PairedRailCandidate.PairBox

/** A rounded path through the already admitted pair route, with one clock. */
internal object SmoothPairedRailCandidate {
    private const val LIMIT=.35
    data class Curve(val a:PairBox,val control:PairBox,val b:PairBox) {
        fun at(t:Float):PairBox = mix(mix(a,control,t),mix(control,b,t),t)
        fun split():Pair<Curve,Curve> {
            val l=mix(a,control,.5f);val r=mix(control,b,.5f);val m=mix(l,r,.5f)
            return Curve(a,l,m) to Curve(m,r,b)
        }
    }
    data class Segment(val start:Float,val end:Float,val curve:Curve)
    data class Plan(val segments:List<Segment>,val bound:Double,val trim:Float) {
        fun at(progress:Float):PairBox {
            val p=clock(progress)*4f
            val s=segments.firstOrNull{p<=it.end}?:segments.last()
            return s.curve.at(((p-s.start)/(s.end-s.start)).coerceIn(0f,1f))
        }
    }
    // Integrated smoothstep velocity ramp. Exactly one acceleration/deceleration;
    // constant nonzero path speed in the interior, zero endpoint velocity.
    fun clock(progress:Float):Float {
        val t=if(progress.isFinite())progress.coerceIn(0f,1f)else 0f
        val ramp=.12f
        fun eased(x:Float):Float {val u=x/ramp;return ramp*(u*u*u-.5f*u*u*u*u)/(1-ramp)}
        return when {t<ramp->eased(t);t>1-ramp->1-eased(1-t);else->(t-ramp*.5f)/(1-ramp)}
    }
    private fun mix(a:Box,b:Box,t:Float):Box {
        fun f(x:Float,y:Float)=x+(y-x)*t
        return Box(f(a.left,b.left),f(a.top,b.top),f(a.right,b.right),f(a.bottom,b.bottom))
    }
    private fun mix(a:PairBox,b:PairBox,t:Float)=PairBox(mix(a.incoming,b.incoming,t),mix(a.outgoing,b.outgoing,t))
    private fun area(a:Box)=a.width.toDouble()*a.height
    private fun hull(a:Box,b:Box,c:Box)=Box(minOf(a.left,b.left,c.left),minOf(a.top,b.top,c.top),maxOf(a.right,b.right,c.right),maxOf(a.bottom,b.bottom,c.bottom))
    private fun overlap(a:Box,b:Box)=max(0f,min(a.right,b.right)-max(a.left,b.left)).toDouble()*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
    // Convex-hull certificate for EVERY point of a quadratic, with bounded
    // subdivision. No sample-only admission and no search during drawing.
    private fun certify(q:Curve,others:List<Box>,initial:PairBox,depth:Int,
                        withinBudget:()->Boolean):Double? {
        if(!withinBudget())return null
        val a=q.a;val b=q.control;val c=q.b
        val ih=hull(a.incoming,b.incoming,c.incoming);val oh=hull(a.outgoing,b.outgoing,c.outgoing)
        val minArea=minOf(area(a.incoming),area(b.incoming),area(c.incoming),area(a.outgoing),area(b.outgoing),area(c.outgoing))
        val bound=overlap(ih,oh)/minArea
        if(bound<=LIMIT && others.all { overlap(ih,it)<=overlap(initial.incoming,it)+1.0 && overlap(oh,it)<=overlap(initial.outgoing,it)+1.0 })return bound
        if(depth==0)return null
        val (left,right)=q.split()
        val l=certify(left,others,initial,depth-1,withinBudget)?:return null
        val r=certify(right,others,initial,depth-1,withinBudget)?:return null
        return max(l,r)
    }
    fun smooth(route:PairedRailCandidate.Plan,others:List<Box>,
               withinBudget:()->Boolean={true}):Plan? {
        val k=route.keyframes
        if(k.size!=5 || !withinBudget())return null
        for(trim in floatArrayOf(.18f,.12f,.08f,.04f)) {
            if(!withinBudget())return null
            val segments=ArrayList<Segment>(7)
            var start=0f;var a=k.first()
            fun line(end:Float,b:PairBox) {segments.add(Segment(start,end,Curve(a,mix(a,b,.5f),b)));start=end;a=b}
            for(i in 1..3) {
                val before=mix(k[i-1],k[i],1-trim)
                val after=mix(k[i],k[i+1],trim)
                line(i-trim,before)
                segments.add(Segment(i-trim,i+trim,Curve(before,k[i],after)))
                start=i+trim;a=after
            }
            line(4f,k.last())
            // Curves are convex combinations of admitted keys, so screen
            // containment and each source aspect hold by construction.
            var bound=0.0;var valid=true
            for(s in segments) {
                val c=certify(s.curve,others,k.first(),5,withinBudget)
                if(c==null){valid=false;break};bound=max(bound,c)
            }
            if(valid)return if(withinBudget())Plan(segments,bound,trim) else null
        }
        return null
    }
}

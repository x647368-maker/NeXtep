package io.github.x647368maker.atomicworkbench.systemui

import kotlin.math.*
import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Box
import io.github.x647368maker.atomicworkbench.systemui.PairedRailCandidate.PairBox

/** A rounded path through the already admitted pair route, with one clock. */
internal object SmoothPairedRailCandidate {
    private const val LIMIT=.35
    data class Curve(val a:PairBox,val control:PairBox,val b:PairBox) {
        // Exactly the same de Casteljau scalar operations, but construct only
        // the final pair/boxes. Do not allocate two intermediate pairs per
        // display frame or change the certified curve's floating-point order.
        fun at(t:Float):PairBox = PairBox(
            quadratic(a.incoming,control.incoming,b.incoming,t),
            quadratic(a.outgoing,control.outgoing,b.outgoing,t))
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
    // The promoted card draws above the departing card throughout the
    // crossing. Their overlap is intentional, with no change of layer order.
    fun straightCrossing(small: Box, main: Box): Plan {
        val start = PairBox(small, main)
        val end = PairBox(main, small)
        val midpoint = mix(start, end, .5f)
        return Plan(listOf(Segment(0f, 4f, Curve(start, midpoint, end))), 1.0, 0f)
    }

    fun railCrossing(small: Box, hinge: Box, main: Box, obstacles: List<Box>,
                     withinBudget: () -> Boolean): Plan? {
        // Travel out of the rail at the original size, then grow inside the
        // main area. The returning card traces exactly the reverse path.
        val turn = 1.36f
        fun pose(time: Float): Box = if (time < turn) mix(small, hinge, time / turn)
            else mix(hinge, main, (time - turn) / (4f - turn))
        fun pair(time: Float) = PairBox(pose(time), pose(4f - time))
        val start = pair(0f)
        for (trim in floatArrayOf(.24f, .12f, .06f)) {
            if (!withinBudget()) return null
            val segments = ArrayList<Segment>(5)
            var time = 0f
            var at = start
            for (corner in floatArrayOf(turn, 4f - turn)) {
                val before = pair(corner - trim)
                val after = pair(corner + trim)
                segments.add(Segment(time, corner - trim, Curve(at, mix(at, before, .5f), before)))
                segments.add(Segment(corner - trim, corner + trim, Curve(before, pair(corner), after)))
                time = corner + trim
                at = after
            }
            val end = pair(4f)
            segments.add(Segment(time, 4f, Curve(at, mix(at, end, .5f), end)))
            if (segments.all { certify(it.curve, obstacles, start, 5, withinBudget, allowPairOverlap = true) != null }) {
                return Plan(segments, 1.0, trim).takeIf { withinBudget() }
            }
        }
        return null
    }
    // Integrated smoothstep velocity ramp. Exactly one acceleration/deceleration;
    // constant nonzero path speed in the interior, zero endpoint velocity.
    fun clock(progress:Float):Float {
        val t=if(progress.isFinite())progress.coerceIn(0f,1f)else 0f
        val ramp=.12f
        fun eased(x:Float):Float {val u=x/ramp;return ramp*(u*u*u-.5f*u*u*u*u)/(1-ramp)}
        return when {t<ramp->eased(t);t>1-ramp->1-eased(1-t);else->(t-ramp*.5f)/(1-ramp)}
    }
    /**
     * The old certified/adaptive fallback can choose a different route from
     * the Vivo-style crossing on adjacent taps. Never admit a fallback that
     * backtracks along the physical rail-to-main axis: a quadratic Bezier has
     * monotone axis motion when BOTH control-point deltas have that sign.
     * This is an analytical per-tap check, not a sampled per-frame heuristic.
     * The legacy task/mirror fallback remains available if this guard rejects.
     */
    fun preservesPrimaryDirection(plan:Plan, small:Box, main:Box):Boolean {
        if (!small.valid() || !main.valid() || plan.segments.isEmpty()) return false
        val leftRight = small.cx < main.left || small.cx > main.right
        val topBottom = small.cy < main.top || small.cy > main.bottom
        val horizontal = when {
            leftRight && !topBottom -> true
            topBottom && !leftRight -> false
            else -> abs(main.cx-small.cx)/main.width >= abs(main.cy-small.cy)/main.height
        }
        fun axis(b:Box):Float = if(horizontal)b.cx else b.cy
        val forward=axis(main)-axis(small)
        if(!forward.isFinite() || abs(forward)<1f)return false
        val sign=if(forward>0f)1f else -1f
        fun ordered(a:Box,control:Box,b:Box,direction:Float):Boolean {
            if(!a.valid() || !control.valid() || !b.valid())return false
            val x=axis(a)*direction
            val y=axis(control)*direction
            val z=axis(b)*direction
            return x.isFinite() && y.isFinite() && z.isFinite() &&
                y+.02f>=x && z+.02f>=y
        }
        // Center continuity alone cannot rule out an abrupt resize or
        // secondary-axis snap at a segment seam. Check all four edges.
        fun sameBox(a:Box,b:Box):Boolean =
            abs(a.left-b.left)<=.04f && abs(a.top-b.top)<=.04f &&
            abs(a.right-b.right)<=.04f && abs(a.bottom-b.bottom)<=.04f
        var prior:Segment?=null
        for(segment in plan.segments) {
            if(!segment.start.isFinite() || !segment.end.isFinite() ||
                segment.end<=segment.start)return false
            val before=prior
            if(before==null) {
                if(abs(segment.start)>.0002f)return false
            } else {
                if(abs(before.end-segment.start)>.0002f)return false
                // Reject an instantaneous center jump between two admitted curves.
                if(!sameBox(before.curve.b.incoming,segment.curve.a.incoming) ||
                    !sameBox(before.curve.b.outgoing,segment.curve.a.outgoing))return false
            }
            val c=segment.curve
            if(!ordered(c.a.incoming,c.control.incoming,c.b.incoming,sign) ||
                !ordered(c.a.outgoing,c.control.outgoing,c.b.outgoing,-sign))return false
            prior=segment
        }
        if(abs(plan.segments.last().end-4f)>.0002f)return false
        val first=plan.segments.first().curve.a
        val last=plan.segments.last().curve.b
        return abs(axis(first.incoming)-axis(small))<=.02f &&
            abs(axis(first.outgoing)-axis(main))<=.02f &&
            abs(axis(last.incoming)-axis(main))<=.02f &&
            abs(axis(last.outgoing)-axis(small))<=.02f
    }

    private fun quadratic(a:Box,c:Box,b:Box,t:Float):Box {
        fun edge(x:Float,y:Float,z:Float):Float {
            val left=x+(y-x)*t
            val right=y+(z-y)*t
            return left+(right-left)*t
        }
        return Box(edge(a.left,c.left,b.left),edge(a.top,c.top,b.top),
            edge(a.right,c.right,b.right),edge(a.bottom,c.bottom,b.bottom))
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
                        withinBudget:()->Boolean, allowPairOverlap:Boolean=false):Double? {
        if(!withinBudget())return null
        val a=q.a;val b=q.control;val c=q.b
        val ih=hull(a.incoming,b.incoming,c.incoming);val oh=hull(a.outgoing,b.outgoing,c.outgoing)
        val minArea=minOf(area(a.incoming),area(b.incoming),area(c.incoming),area(a.outgoing),area(b.outgoing),area(c.outgoing))
        val bound=if(allowPairOverlap) 1.0 else overlap(ih,oh)/minArea
        if((allowPairOverlap || bound<=LIMIT) && others.all { overlap(ih,it)<=overlap(initial.incoming,it)+1.0 && overlap(oh,it)<=overlap(initial.outgoing,it)+1.0 })return bound
        if(depth==0)return null
        val (left,right)=q.split()
        val l=certify(left,others,initial,depth-1,withinBudget,allowPairOverlap)?:return null
        val r=certify(right,others,initial,depth-1,withinBudget,allowPairOverlap)?:return null
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

package io.github.x647368maker.atomicworkbench.systemui

import kotlin.math.*
import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Box

/** Geometry-only candidate. Requires compositor/crop review before deployment. */
internal object PairedRailCandidate {
    data class PairBox(val incoming:Box,val outgoing:Box)
    data class Plan(val keyframes:List<PairBox>,val bound:Double,val lane:Float) {
        fun at(progress:Float):PairBox {
            val p=progress.coerceIn(0f,1f)*4f
            val i=p.toInt().coerceAtMost(3)
            val u=StageLiveSurfaceFlightGeometry.phaseEase(p-i)
            val a=keyframes[i];val b=keyframes[i+1]
            return PairBox(interpolate(a.incoming,b.incoming,u),interpolate(a.outgoing,b.outgoing,u))
        }
    }
    private fun interpolate(a:Box,b:Box,p:Float):Box {
        fun mix(x:Float,y:Float)=x+(y-x)*p
        return Box(mix(a.left,b.left),mix(a.top,b.top),mix(a.right,b.right),mix(a.bottom,b.bottom))
    }
    // The physical rail normal, not bitmap aspect or the selected backend,
    // owns travel direction. The incoming and outgoing centers must advance
    // in opposite directions on that axis. Perpendicular clearance is fine;
    // reversing past a previously traversed rail coordinate is not.
    internal fun followsRailAxis(keys:List<PairBox>,horizontal:Boolean):Boolean {
        if(keys.size<2)return false
        fun axis(b:Box)=if(horizontal)b.cx else b.cy
        val first=keys.first();val last=keys.last()
        val advance=axis(last.incoming)-axis(first.incoming)
        val retreat=axis(last.outgoing)-axis(first.outgoing)
        if(!advance.isFinite() || !retreat.isFinite() ||
            abs(advance)<2f || abs(retreat)<2f || advance*retreat>=0f)return false
        val sign=if(advance>0f)1f else -1f
        // Convex quadratic smoothing of monotonically ordered control
        // points preserves this projection at every continuous instant.
        // Subpixel tolerance covers only floating point center arithmetic.
        val epsilon=.002f
        return keys.zipWithNext().all { (a,b) ->
            val up=(axis(b.incoming)-axis(a.incoming))*sign
            val down=(axis(a.outgoing)-axis(b.outgoing))*sign
            up.isFinite() && down.isFinite() && up>=-epsilon && down>=-epsilon
        }
    }
    private fun area(a:Box)=a.width.toDouble()*a.height
    private fun fit(width:Int,height:Int,target:Box):Pair<Float,Float> {
        val s=min(target.width/width,target.height/height)
        return width*s to height*s
    }
    private fun at(x:Float,y:Float,size:Pair<Float,Float>)=Box(x-size.first/2,y-size.second/2,x+size.first/2,y+size.second/2)
    private fun inside(a:Box,screen:Box)=a.valid()&&a.left>=screen.left&&a.top>=screen.top&&a.right<=screen.right&&a.bottom<=screen.bottom
    /** Edges of BOTH boxes are affine in the SAME segment parameter.
     * Split where active min/max/zero edges can change, then check the
     * piecewise quadratic overlap extremum. No sample-only admission. */
    fun peak(a:Box,z:Box,b:Box,y:Box):Double {
        if(!listOf(a,z,b,y).all{it.valid()})return Double.POSITIVE_INFINITY
        if(max(a.right,z.right)<=min(b.left,y.left)||max(b.right,y.right)<=min(a.left,z.left)||
            max(a.bottom,z.bottom)<=min(b.top,y.top)||max(b.bottom,y.bottom)<=min(a.top,z.top))return 0.0
        val cuts=ArrayList<Double>(10);cuts.add(0.0);cuts.add(1.0)
        fun cross(p:Float,q:Float,r:Float,s:Float){
            val start=p.toDouble()-r;val end=q.toDouble()-s
            val d=end-start
            if(d!=0.0){val t=-start/d;if(t>0.0&&t<1.0)cuts.add(t)}
        }
        cross(a.left,z.left,b.left,y.left);cross(a.left,z.left,b.right,y.right)
        cross(a.right,z.right,b.left,y.left);cross(a.right,z.right,b.right,y.right)
        cross(a.top,z.top,b.top,y.top);cross(a.top,z.top,b.bottom,y.bottom)
        cross(a.bottom,z.bottom,b.top,y.top);cross(a.bottom,z.bottom,b.bottom,y.bottom)
        cuts.sort()
        fun mix(p:Float,q:Float,t:Double)=p.toDouble()+(q.toDouble()-p)*t
        fun width(t:Double)=max(0.0,min(mix(a.right,z.right,t),mix(b.right,y.right,t))-max(mix(a.left,z.left,t),mix(b.left,y.left,t)))
        fun height(t:Double)=max(0.0,min(mix(a.bottom,z.bottom,t),mix(b.bottom,y.bottom,t))-max(mix(a.top,z.top,t),mix(b.top,y.top,t)))
        var peak=0.0
        for(i in 0 until cuts.lastIndex){
            val l=cuts[i];val r=cuts[i+1];val w=width(l);val h=height(l);val ew=width(r);val eh=height(r)
            peak=maxOf(peak,w*h,ew*eh)
            val dw=ew-w;val dh=eh-h;val q=dw*dh
            if(q<0){val u=-(dw*h+dh*w)/(2*q);if(u>0&&u<1)peak=max(peak,(w+u*dw)*(h+u*dh))}
        }
        return peak
    }
    fun plan(small:Box,main:Box,iw:Int,ih:Int,ow:Int,oh:Int,screen:Box,others:List<Box>,
             withinBudget:()->Boolean={true}):Plan? = planWithViewports(small,main,iw,ih,ow,oh,screen,others,false,{true},withinBudget)

    fun planWithViewports(small:Box,main:Box,iw:Int,ih:Int,ow:Int,oh:Int,screen:Box,others:List<Box>,
             coverViewports:Boolean, acceptCandidate:(Plan)->Boolean, withinBudget:()->Boolean):Plan? {
        if(!withinBudget()||!listOf(small,main,screen).all{it.valid()}||listOf(iw,ih,ow,oh).any{it<2}||area(main)<=area(small)||others.any{!it.valid()})return null
        val ia=if(coverViewports)small.width to small.height else fit(iw,ih,small)
        val iz=if(coverViewports)main.width to main.height else fit(iw,ih,main)
        val oa=if(coverViewports)main.width to main.height else fit(ow,oh,main)
        val oz=if(coverViewports)small.width to small.height else fit(ow,oh,small)
        val hx=max(ia.first,oz.first)/2;val hy=max(ia.second,oz.second)/2
        if(main.width<hx*2||main.height<hy*2)return null
        val dx=maxOf(main.left-small.cx,small.cx-main.right,0f)/main.width
        val dy=maxOf(main.top-small.cy,small.cy-main.bottom,0f)/main.height
        if(dx==0f&&dy==0f)return null
        val leftRight=dx>=dy
        val nearX=if(leftRight){if(small.cx<main.cx)main.left+hx else main.right-hx}else small.cx.coerceIn(main.left+hx,main.right-hx)
        val nearY=if(!leftRight){if(small.cy<main.cy)main.top+hy else main.bottom-hy}else small.cy.coerceIn(main.top+hy,main.bottom-hy)
        for(depth in if(coverViewports) floatArrayOf(0f,.25f,.5f,.75f) else floatArrayOf(0f)) {
            val ex=if(leftRight)nearX+(main.cx-nearX)*depth else nearX
            val ey=if(!leftRight)nearY+(main.cy-nearY)*depth else nearY
            val vx=main.cx-ex;val vy=main.cy-ey;val length=hypot(vx,vy)
            if(length<2f)continue
            val mx=(main.cx+ex)/2;val my=(main.cy+ey)/2
            // Restrict candidates to a finite deterministic set. Smallest accepted
            // offset wins, with fixed sign as tie-break; no per-frame search.
            for(lead in if(coverViewports) floatArrayOf(.25f,.15f,.4f) else floatArrayOf(.25f))
            for(factor in if(coverViewports) floatArrayOf(.5f,.75f,1f,1.25f,1.5f,2f,2.5f,3f) else floatArrayOf(.5f,.75f,1f,1.25f,1.5f,2f)) for(sign in floatArrayOf(1f,-1f)) {
                if(!withinBudget())return null
                val lane=min(small.width,small.height)*factor*sign
                val normalX=-vy/length*lane;val normalY=vx/length*lane
                val correctAxis=if(leftRight)abs(normalX)>abs(vx)*.5f else abs(normalY)>abs(vy)*.5f
                val lx=if(!correctAxis)normalX else if(leftRight)0f else if(vy>0f)-lane else lane
                val ly=if(!correctAxis)normalY else if(!leftRight)0f else if(vx>0f)lane else -lane
                val keys=listOf(PairBox(at(small.cx,small.cy,ia),at(main.cx,main.cy,oa)),
                    PairBox(at(ex,ey,ia),at(main.cx+(mx-lx-main.cx)*lead,main.cy+(my-ly-main.cy)*lead,oz)),
                    PairBox(at(mx+lx,my+ly,ia),at(mx-lx,my-ly,oz)),
                    PairBox(at(main.cx+(mx+lx-main.cx)*lead,main.cy+(my+ly-main.cy)*lead,ia),at(ex,ey,oz)),
                    PairBox(at(main.cx,main.cy,iz),at(small.cx,small.cy,oz)))
                if(keys.any{!inside(it.incoming,screen)||!inside(it.outgoing,screen)})continue
                // Use the existing bounded candidate search to reject any
                // detour which would make either card reverse its rail travel.
                if(!followsRailAxis(keys,leftRight))continue
                var valid=true;var maximum=0.0
                for(i in 0 until keys.lastIndex){
                    if(!withinBudget())return null
                    val a=keys[i];val b=keys[i+1]
                    // Each edge moves monotonically within a segment. All boxes
                    // have positive affine dimensions, so log(area) is concave and
                    // its minimum is at an endpoint (including varied viewport aspects).
                    val minArea=minOf(area(a.incoming),area(b.incoming),area(a.outgoing),area(b.outgoing))
                    val bound=peak(a.incoming,b.incoming,a.outgoing,b.outgoing)/minArea
                    maximum=max(maximum,bound)
                    if(bound>.35){valid=false;break}
                    for(o in others){
                        if(!withinBudget())return null
                        if(peak(a.incoming,b.incoming,o,o)>peak(keys[0].incoming,keys[0].incoming,o,o)+1.0||
                            peak(a.outgoing,b.outgoing,o,o)>peak(keys[0].outgoing,keys[0].outgoing,o,o)+1.0){valid=false;break}
                    }
                    if(!valid)break
                }
                if(valid){val candidate=Plan(keys,maximum,lane);if(!withinBudget())return null
                    if(acceptCandidate(candidate)&&withinBudget())return candidate}
            }
        }
        return null
    }
}

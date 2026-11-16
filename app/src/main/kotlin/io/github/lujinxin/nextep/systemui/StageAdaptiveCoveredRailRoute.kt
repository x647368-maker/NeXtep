package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Box
import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Crop
import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.MirrorFrame

/** Plan actual viewport edges, independently of buffer aspect. Only transient
 * mirror pixels are cropped; original tasks, bounds and producers never change. */
internal object StageAdaptiveCoveredRailRoute {
    fun frame(viewport:Box, source:Crop):MirrorFrame? {
        if(!viewport.valid())return null
        val sw=source.right-source.left;val sh=source.bottom-source.top
        if(sw<2||sh<2)return null
        val scale=maxOf(viewport.width/sw,viewport.height/sh)
        if(!scale.isFinite()||scale<=0f||scale>10f)return null
        val cw=kotlin.math.floor((viewport.width/scale).toDouble()).toInt().coerceAtMost(sw)
        val ch=kotlin.math.floor((viewport.height/scale).toDouble()).toInt().coerceAtMost(sh)
        if(cw<2||ch<2)return null
        val l=source.left+(sw-cw)/2;val t=source.top+(sh-ch)/2
        val w=cw*scale;val h=ch*scale
        return MirrorFrame(scale,viewport.cx-(l+cw*.5f)*scale,
            viewport.cy-(t+ch*.5f)*scale,Crop(l,t,l+cw,t+ch),
            Box(viewport.cx-w*.5f,viewport.cy-h*.5f,viewport.cx+w*.5f,viewport.cy+h*.5f))
    }
    fun choose(small:Box,main:Box,incoming:Crop,outgoing:Crop,screen:Box,
               obstacles:List<Box>,withinBudget:()->Boolean={true}):SmoothPairedRailCandidate.Plan? {
        val g=StageLiveSurfaceFlightGeometry
        if(!withinBudget()||!g.canUseCoveredFlight(small,main,incoming,outgoing,obstacles))return null
        var accepted:SmoothPairedRailCandidate.Plan?=null
        PairedRailCandidate.planWithViewports(small,main,incoming.right-incoming.left,incoming.bottom-incoming.top,
            outgoing.right-outgoing.left,outgoing.bottom-outgoing.top,screen,obstacles,true,
            { keys ->
                accepted=certify(keys,small,main,incoming,outgoing,obstacles,withinBudget)
                accepted!=null
            },withinBudget) ?:return null
        return accepted.takeIf{withinBudget()}
    }
    private fun certify(plan:PairedRailCandidate.Plan,small:Box,main:Box,incoming:Crop,outgoing:Crop,
                        obstacles:List<Box>,withinBudget:()->Boolean):SmoothPairedRailCandidate.Plan? {
        val g=StageLiveSurfaceFlightGeometry
        // Convex-hull interpolation: all viewport widths/heights stay between key extrema.
        // Bound cover scale and inward crop size for all intermediate frames, not only samples.
        for((keys,crop) in listOf(plan.keyframes.map{it.incoming} to incoming,plan.keyframes.map{it.outgoing} to outgoing)) {
            val sw=crop.right-crop.left;val sh=crop.bottom-crop.top
            val maxScale=maxOf(keys.maxOf{it.width}/sw,keys.maxOf{it.height}/sh)
            if(!maxScale.isFinite()||maxScale<=0f||maxScale>10f||
                keys.minOf{it.width}/maxScale<2f||keys.minOf{it.height}/maxScale<2f)return null
        }
        val smooth=SmoothPairedRailCandidate.smooth(plan,obstacles,withinBudget)?:return null
        if(!withinBudget())return null
        // Preserve the original visible terminal crop and initial complete source.
        val first=smooth.at(0f);val last=smooth.at(1f)
        for((viewport,from,to,crop,p) in listOf(
            Endpoint(first.incoming,small,main,incoming,0f),Endpoint(first.outgoing,main,small,outgoing,0f),
            Endpoint(last.incoming,small,main,incoming,1f),Endpoint(last.outgoing,main,small,outgoing,1f))) {
            val old=g.mirrorFrame(from,to,crop,p,true)?:return null
            if(p==0f && g.endpointGapPx(viewport,old.box)>2f)return null
            val next=if(p==0f)old else frame(viewport,crop)?:return null
            if(g.endpointGapPx(old.box,next.box)>2f||old.crop!=next.crop)return null
        }
        return smooth.takeIf{withinBudget()}
    }
    private data class Endpoint(val viewport:Box,val from:Box,val to:Box,val crop:Crop,val p:Float)
}

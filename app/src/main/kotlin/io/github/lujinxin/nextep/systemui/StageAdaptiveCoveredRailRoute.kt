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
        return accepted?.takeIf{withinBudget() && SmoothPairedRailCandidate.preservesPrimaryDirection(it,small,main)}
    }
    private fun certify(plan:PairedRailCandidate.Plan,small:Box,main:Box,incoming:Crop,outgoing:Crop,
                        obstacles:List<Box>,withinBudget:()->Boolean):SmoothPairedRailCandidate.Plan? {
        if (!hasSafeCropRange(plan.keyframes, incoming, outgoing)) return null
        val smooth=SmoothPairedRailCandidate.smooth(plan,obstacles,withinBudget)?:return null
        return smooth.takeIf { withinBudget() && preservesEndpointCrops(it, small, main, incoming, outgoing) }
    }

    fun straightCrossing(
        small: Box, main: Box, incoming: Crop, outgoing: Crop, screen: Box,
        obstacles: List<Box>, withinBudget: () -> Boolean,
    ): SmoothPairedRailCandidate.Plan? {
        if (!withinBudget() || !screen.valid() || !small.valid() || !main.valid() ||
            obstacles.any { !it.valid() }) return null
        if (listOf(small, main).any {
            it.left < screen.left || it.top < screen.top ||
                it.right > screen.right || it.bottom > screen.bottom
        }) return null
        val geometry = StageLiveSurfaceFlightGeometry
        for (slot in obstacles) {
            val peak = geometry.peakSweptOverlap(small, main, slot)
            val incomingAllowance = geometry.peakSweptOverlap(small, small, slot) + 1.0
            val outgoingAllowance = geometry.peakSweptOverlap(main, main, slot) + 1.0
            if (peak > minOf(incomingAllowance, outgoingAllowance)) return null
        }
        val plan = SmoothPairedRailCandidate.straightCrossing(small, main)
        return plan.takeIf {
            withinBudget() &&
                hasSafeCropRange(listOf(it.at(0f), it.at(1f)), incoming, outgoing) &&
                preservesEndpointCrops(it, small, main, incoming, outgoing) &&
                SmoothPairedRailCandidate.preservesPrimaryDirection(it,small,main)
        }
    }

    fun railCrossing(
        small: Box, main: Box, incoming: Crop, outgoing: Crop, screen: Box,
        obstacles: List<Box>, withinBudget: () -> Boolean,
    ): SmoothPairedRailCandidate.Plan? {
        if (!withinBudget() || !screen.valid() || obstacles.any { !it.valid() }) return null
        val geometry = StageLiveSurfaceFlightGeometry
        val turn = geometry.railCorridorFlight(
            small, main, incoming.right - incoming.left, incoming.bottom - incoming.top, .34f,
        )?.box ?: return null
        val hinge = Box(
            turn.cx - small.width * .5f, turn.cy - small.height * .5f,
            turn.cx + small.width * .5f, turn.cy + small.height * .5f,
        )
        val viewports = listOf(small, hinge, main)
        if (viewports.any {
            !it.valid() || it.left < screen.left || it.top < screen.top ||
                it.right > screen.right || it.bottom > screen.bottom
        }) return null
        val keys = viewports.mapIndexed { index, box ->
            PairedRailCandidate.PairBox(box, viewports[viewports.lastIndex - index])
        }
        if (!hasSafeCropRange(keys, incoming, outgoing)) return null
        val plan = SmoothPairedRailCandidate.railCrossing(small, hinge, main, obstacles, withinBudget)
            ?: return null
        return plan.takeIf { withinBudget() && preservesEndpointCrops(it, small, main, incoming, outgoing) &&
            SmoothPairedRailCandidate.preservesPrimaryDirection(it,small,main) }
    }

    private fun hasSafeCropRange(
        keys: List<PairedRailCandidate.PairBox>, incoming: Crop, outgoing: Crop,
    ): Boolean {
        // Convex-hull interpolation: all viewport widths/heights stay between key extrema.
        // Bound cover scale and inward crop size for all intermediate frames, not only samples.
        for((viewports,crop) in listOf(keys.map{it.incoming} to incoming,keys.map{it.outgoing} to outgoing)) {
            val sw=crop.right-crop.left;val sh=crop.bottom-crop.top
            val maxScale=maxOf(viewports.maxOf{it.width}/sw,viewports.maxOf{it.height}/sh)
            if(!maxScale.isFinite()||maxScale<=0f||maxScale>10f||
                viewports.minOf{it.width}/maxScale<2f||viewports.minOf{it.height}/maxScale<2f)return false
        }
        return true
    }

    private fun preservesEndpointCrops(
        plan: SmoothPairedRailCandidate.Plan, small: Box, main: Box, incoming: Crop, outgoing: Crop,
    ): Boolean {
        val g=StageLiveSurfaceFlightGeometry
        // Preserve the original visible terminal crop and initial complete source.
        val first=plan.at(0f);val last=plan.at(1f)
        for((viewport,from,to,crop,p) in listOf(
            Endpoint(first.incoming,small,main,incoming,0f),Endpoint(first.outgoing,main,small,outgoing,0f),
            Endpoint(last.incoming,small,main,incoming,1f),Endpoint(last.outgoing,main,small,outgoing,1f))) {
            val old=g.mirrorFrame(from,to,crop,p,true)?:return false
            if(p==0f && g.endpointGapPx(viewport,old.box)>2f)return false
            // applyFrame() renders the existing complete-source mirror at p=0
            // but switches to frame(viewport,crop) as soon as p>0. Earlier
            // admission compared old against ITSELF at p=0, so a tiny source
            // aspect mismatch could create an immediate content-crop/scale pop.
            // Compare the two actual backends at both endpoints. The first
            // sample must agree subpixel-exactly; an uncertain path falls back
            // to the existing safe, source-owned compositor route.
            val next=frame(viewport,crop)?:return false
            val tolerance=if(p==0f).35f else 2f
            val sourceWidth=crop.right-crop.left
            val sourceHeight=crop.bottom-crop.top
            val scaleShift=kotlin.math.abs(old.scale-next.scale)*
                maxOf(sourceWidth,sourceHeight)
            if(old.crop!=next.crop ||
                g.endpointGapPx(old.box,next.box)>tolerance ||
                scaleShift>tolerance ||
                kotlin.math.abs(old.x-next.x)>tolerance ||
                kotlin.math.abs(old.y-next.y)>tolerance)return false
        }
        return true
    }
    private data class Endpoint(val viewport:Box,val from:Box,val to:Box,val crop:Crop,val p:Float)
}

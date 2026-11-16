package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Box
import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Crop

/** Admit the already-tested rounded paired renderer only when it improves
 * the existing crossing and retains the existing visible endpoints/crop mode.
 * All planning is per-tap and cooperatively bounded. No new runtime surfaces. */
internal object StageCertifiedRailRoute {
    private const val LIMIT=.35
    private fun area(b:Box)=b.width.toDouble()*b.height
    internal fun overlapFraction(a:Box,b:Box):Double {
        if(!a.valid() || !b.valid())return 0.0
        val w=(minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0f)
        val h=(minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)).coerceAtLeast(0f)
        return w.toDouble()*h/minOf(area(a),area(b))
    }
    private fun exactAspect(box:Box,crop:Crop):Boolean =
        box.width.toDouble()*(crop.bottom-crop.top)==box.height.toDouble()*(crop.right-crop.left)

    fun choose(small:Box,main:Box,incoming:Crop,outgoing:Crop,screen:Box,
               obstacles:List<Box>,oldCovered:Boolean,withinBudget:()->Boolean={true}):
        SmoothPairedRailCandidate.Plan? {
        if(!withinBudget())return null
        // The contain renderer must not replace a useful dynamic crop.
        // Only an exactly matching source and both endpoint aspects can
        // retain the full covered footprint along the complete flight.
        if(oldCovered && !(exactAspect(small,incoming) && exactAspect(main,incoming) &&
                exactAspect(small,outgoing) && exactAspect(main,outgoing)))return null
        val g=StageLiveSurfaceFlightGeometry
        val up=g.mirrorFrame(small,main,incoming,.5f,oldCovered)?:return null
        val down=g.mirrorFrame(main,small,outgoing,.5f,oldCovered)?:return null
        if(overlapFraction(up.box,down.box)<=LIMIT || !withinBudget())return null
        val keys=PairedRailCandidate.plan(small,main,incoming.right-incoming.left,
            incoming.bottom-incoming.top,outgoing.right-outgoing.left,outgoing.bottom-outgoing.top,
            screen,obstacles,withinBudget)?:return null
        val plan=SmoothPairedRailCandidate.smooth(keys,obstacles,withinBudget)?:return null
        if(!withinBudget() || plan.bound>LIMIT)return null
        // Explicit endpoint guard even if a future planner changes its keys.
        val first=plan.at(0f)
        val last=plan.at(1f)
        fun endpoint(box:Box,from:Box,to:Box,crop:Crop,p:Float):Boolean {
            val old=g.mirrorFrame(from,to,crop,p,oldCovered)?:return false
            val fit=g.mirrorFrame(from,to,crop,p,false)?:return false
            return g.endpointGapPx(box,old.box)<=2f && g.endpointGapPx(box,fit.box)<=.02f
        }
        return plan.takeIf {
            endpoint(first.incoming,small,main,incoming,0f) &&
            endpoint(first.outgoing,main,small,outgoing,0f) &&
            endpoint(last.incoming,small,main,incoming,1f) &&
            endpoint(last.outgoing,main,small,outgoing,1f) &&
            withinBudget()
        }
    }
}

package io.github.x647368maker.atomicworkbench.systemui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private typealias Crop = StageLiveSurfaceFlightGeometry.Crop
private typealias Box = StageLiveSurfaceFlightGeometry.Box
private typealias MirrorFrame = StageLiveSurfaceFlightGeometry.MirrorFrame

/**
 * A task-local affine map is distinct from the card's swept collision box.
 * It is not a claim that an application keeps its semantic content coordinates
 * after relayout, nor an observation of the actual latched buffer.
 */
internal object StageEndpointContentMapping {
    enum class DestinationRead { READY, PENDING, REJECTED }
    enum class DestinationHandoff { WAIT, READY, EXPIRED, REJECTED }

    // Reuse the original active handoff clock. Missing read evidence may
    // recover at the next already-scheduled check, not on a new timer.
    fun destinationHandoff(elapsedMs:Long,timeoutMs:Long,frameComplete:Boolean,
                           releasable:Boolean,liveReady:Boolean,read:DestinationRead):DestinationHandoff {
        if(read==DestinationRead.REJECTED)return DestinationHandoff.REJECTED
        return when(StageConcurrentNativeTrialPolicy.handoffAction(elapsedMs,timeoutMs,
                frameComplete,releasable,liveReady && read==DestinationRead.READY)) {
            StageConcurrentNativeTrialPolicy.HandoffAction.WAIT -> DestinationHandoff.WAIT
            StageConcurrentNativeTrialPolicy.HandoffAction.READY -> DestinationHandoff.READY
            StageConcurrentNativeTrialPolicy.HandoffAction.EXPIRED -> DestinationHandoff.EXPIRED
        }
    }

    data class ContentMap(val scale:Float,val x:Float,val y:Float) {
        fun valid()=scale.isFinite() && scale>0f && x.isFinite() && y.isFinite()
    }
    data class Anchor internal constructor(
        val sourceRoot:ContentMap,
        val sourceCrop:Crop,
        val commonLocal:Crop,
        val targetLocal:Crop,
        val target:ContentMap,
        val viewport:Box,
        val first:MirrorFrame,
        val terminal:MirrorFrame,
        val startRatio:Float,
        val endRatio:Float,
        val terminalAvailable:Crop,
        val cx:Double,val cy:Double,
        val startDx:Double,val startDy:Double,
        val endDx:Double,val endDy:Double,
    )

    /** Necessary geometry prerequisite, not proof of shared semantic content
     * or a latched producer. A task's local pixels cannot be identified with
     * those of a different-sized canvas. Never admit a partial domain merely
     * because its projected affine markers happen to match.
     */
    fun sameLocalCanvas(source:Crop,target:Crop):Boolean =
        source==target && source.left==0 && source.top==0 &&
            source.right>=2 && source.bottom>=2

    /** Independently measure actual requested content extent against the
     * destination viewport, not against another cropped fit. Reject gaps
     * beyond the existing rounding allowance even if affine error is zero.
     * This is a geometry gate, not presentation evidence.
     */
    fun fullEndpointCoverage(a:Anchor):Boolean =
        a.viewport.valid() && a.terminal.box.valid() &&
            StageLiveSurfaceFlightGeometry.endpointGapPx(a.viewport,a.terminal.box)<=2f

    fun rootMap(scale:Float,x:Float,y:Float,detached:Boolean)=
        if(detached)ContentMap(1f,0f,0f) else ContentMap(scale,x,y)

    fun compose(mirror:ContentMap,root:ContentMap)=ContentMap(
        mirror.scale*root.scale,mirror.x+mirror.scale*root.x,mirror.y+mirror.scale*root.y)

    fun inverseCompose(target:ContentMap,root:ContentMap):ContentMap? {
        if(!target.valid() || !root.valid())return null
        val z=target.scale/root.scale
        return ContentMap(z,target.x-z*root.x,target.y-z*root.y).takeIf { it.valid() && z<=10f }
    }

    fun mapError(a:ContentMap,b:ContentMap,local:Crop):Float {
        if(!a.valid() || !b.valid())return Float.POSITIVE_INFINITY
        fun dx(v:Int)=abs((a.scale.toDouble()-b.scale)*v+a.x.toDouble()-b.x)
        fun dy(v:Int)=abs((a.scale.toDouble()-b.scale)*v+a.y.toDouble()-b.y)
        return maxOf(dx(local.left),dx(local.right),dy(local.top),dy(local.bottom)).toFloat()
    }

    private fun inward(l:Double,t:Double,r:Double,b:Double):Crop? {
        val x1=ceil(l);val y1=ceil(t);val x2=floor(r);val y2=floor(b)
        if(!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite() ||
            x1<Int.MIN_VALUE || y1<Int.MIN_VALUE || x2>Int.MAX_VALUE || y2>Int.MAX_VALUE ||
            x2-x1<2.0 || y2-y1<2.0)return null
        return Crop(x1.toInt(),y1.toInt(),x2.toInt(),y2.toInt())
    }
    private fun overlap(a:Crop,b:Crop):Crop? =
        inward(maxOf(a.left,b.left).toDouble(),maxOf(a.top,b.top).toDouble(),
            minOf(a.right,b.right).toDouble(),minOf(a.bottom,b.bottom).toDouble())

    private fun projected(local:Crop,root:ContentMap):Crop? {
        if(!root.valid())return null
        val z=root.scale.toDouble()
        return inward(local.left*z+root.x,local.top*z+root.y,local.right*z+root.x,local.bottom*z+root.y)
    }

    fun slotMap(local:Crop,box:Box):ContentMap? {
        // VirtualDisplaySlot records a full display-local canvas and maps
        // it into the TextureView using CONTAIN. Never infer this from a View's outer box.
        if(local.left!=0 || local.top!=0 || local.right<2 || local.bottom<2 || !box.valid())return null
        val z=box.width/local.right
        if(!z.isFinite() || z<=0f || abs(local.bottom*z-box.height)>1f)return null
        return ContentMap(z,box.left,box.top).takeIf { it.valid() }
    }
    private fun fit(viewport:Box,available:Crop,map:ContentMap):MirrorFrame? {
        if(!viewport.valid() || !map.valid() || map.scale>10f)return null
        val z=map.scale.toDouble()
        val c=inward(maxOf(available.left.toDouble(),(viewport.left.toDouble()-map.x)/z),
            maxOf(available.top.toDouble(),(viewport.top.toDouble()-map.y)/z),
            minOf(available.right.toDouble(),(viewport.right.toDouble()-map.x)/z),
            minOf(available.bottom.toDouble(),(viewport.bottom.toDouble()-map.y)/z)) ?: return null
        val box=Box(c.left*map.scale+map.x,c.top*map.scale+map.y,c.right*map.scale+map.x,c.bottom*map.scale+map.y)
        return if(box.valid())MirrorFrame(map.scale,map.x,map.y,c,box) else null
    }
    private fun intersection(a:Box,b:Box)=Box(maxOf(a.left,b.left),maxOf(a.top,b.top),
        minOf(a.right,b.right),minOf(a.bottom,b.bottom))

    fun anchor(sourceRoot:ContentMap,sourceCrop:Crop,sourceLocal:Crop,targetLocal:Crop,
               target:ContentMap,viewport:Box,first:MirrorFrame,rawFirst:MirrorFrame,
               rawEnd:MirrorFrame):Anchor? {
        val common=overlap(sourceLocal,targetLocal) ?: return null
        val endMap=inverseCompose(target,sourceRoot) ?: return null
        if(!StageLiveSurfaceFlightGeometry.sameMirrorContent(first,rawFirst) ||
            !rawFirst.box.valid() || !rawEnd.box.valid() || rawFirst.scale<=0f || rawEnd.scale<=0f)return null
        // The certified route remains an upper bound on occupied pixels.
        // A shorter old buffer is allowed to leave a gap; never invent pixels.
        // The local domain known on both sides is the conservative handoff
        // domain. A TaskInfo/SurfaceFrame crop is not evidence that new pixels
        // outside that domain have latched. Do not expose a newly requested
        // extent on the held mirror merely because its metadata changed.
        val commonRoot=projected(common,sourceRoot) ?: return null
        val terminalAvailable=overlap(sourceCrop,commonRoot) ?: return null
        val terminal=fit(intersection(rawEnd.box,viewport),terminalAvailable,endMap) ?: return null
        val ideal=fit(viewport,terminalAvailable,endMap) ?: return null
        if(StageLiveSurfaceFlightGeometry.endpointGapPx(terminal.box,ideal.box)>2f)return null
        val cx=(sourceCrop.left.toDouble()+sourceCrop.right)*.5
        val cy=(sourceCrop.top.toDouble()+sourceCrop.bottom)*.5
        fun dx(map:ContentMap,raw:MirrorFrame)=(map.x+cx*map.scale-raw.x-cx*raw.scale)/raw.box.width
        fun dy(map:ContentMap,raw:MirrorFrame)=(map.y+cy*map.scale-raw.y-cy*raw.scale)/raw.box.height
        val start=ContentMap(first.scale,first.x,first.y)
        return Anchor(sourceRoot,sourceCrop,common,targetLocal,target,viewport,first,terminal,
            first.scale/rawFirst.scale,endMap.scale/rawEnd.scale,terminalAvailable,cx,cy,
            dx(start,rawFirst),dy(start,rawFirst),dx(endMap,rawEnd),dy(endMap,rawEnd))
    }

    fun flight(raw:MirrorFrame,a:Anchor,p:Float):MirrorFrame? {
        if(!p.isFinite() || !raw.box.valid() || !raw.scale.isFinite() || raw.scale<=0f)return null
        if(p<=0f)return a.first
        if(p>=1f)return a.terminal
        val u=StageLiveSurfaceFlightGeometry.phaseEase(p)
        val z=raw.scale*(a.startRatio+(a.endRatio-a.startRatio)*u)
        val x=(raw.x+a.cx*(raw.scale-z)+raw.box.width*(a.startDx+(a.endDx-a.startDx)*u)).toFloat()
        val y=(raw.y+a.cy*(raw.scale-z)+raw.box.height*(a.startDy+(a.endDy-a.startDy)*u)).toFloat()
        val source=a.sourceCrop
        val end=a.terminalAvailable
        // Smooth the supported local domain toward the common terminal crop.
        // This is internal crop evolution, not a change to route/clock.
        fun edge(a:Int,b:Int)=a.toDouble()+(b.toDouble()-a)*u
        val available=inward(edge(source.left,end.left),edge(source.top,end.top),
            edge(source.right,end.right),edge(source.bottom,end.bottom)) ?: return null
        return fit(raw.box,available,ContentMap(z,x,y))
    }

    fun held(a:Anchor,local:Crop,root:ContentMap):StageHeldEndpointPolicy.Fit? {
        // Match the new writer's local crop and root together, but do not
        // infer a newly latched buffer from its target crop. The common domain
        // remains visible until the existing real-destination reveal. App
        // reflow and the actual first real buffer remain device evidence gates.
        val available=projected(overlap(local,a.commonLocal) ?: return null,root) ?: return null
        val map=inverseCompose(a.target,root) ?: return null
        val f=fit(a.viewport,available,map) ?: return null
        return StageHeldEndpointPolicy.Fit(f.crop.left,f.crop.top,f.crop.right,f.crop.bottom,f.scale,f.x,f.y)
    }
}

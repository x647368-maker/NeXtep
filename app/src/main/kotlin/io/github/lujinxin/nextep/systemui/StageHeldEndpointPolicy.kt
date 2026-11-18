package io.github.x647368maker.atomicworkbench.systemui

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** Map a verified new producer crop into the already accepted endpoint card.
 * Detached roots use source-local coordinates; the retained-root path uses
 * the same transform convention as the preceding flight. No pixel readback. */
internal object StageHeldEndpointPolicy {
    data class Fit(val left:Int,val top:Int,val right:Int,val bottom:Int,
                   val scale:Float,val x:Float,val y:Float)

    data class ContentMap(val scale:Float,val x:Float,val y:Float) {
        fun valid()=scale.isFinite() && scale>0f && x.isFinite() && y.isFinite()
    }

    internal data class ContentAnchor(
        val sourceRoot:ContentMap,
        val sourceCrop:StageLiveSurfaceFlightGeometry.Crop,
        val target:ContentMap,
        val viewport:StageLiveSurfaceFlightGeometry.Box,
        val rawTerminal:StageLiveSurfaceFlightGeometry.MirrorFrame,
    )

    fun rootMap(producerScale:Float,producerX:Float,producerY:Float,detachedRoot:Boolean):ContentMap =
        if(detachedRoot) ContentMap(1f,0f,0f) else ContentMap(producerScale,producerX,producerY)

    // target = mirror * producerRoot, solved for the absolute mirror transform.
    fun mirrorMap(target:ContentMap,root:ContentMap):ContentMap? {
        if(!target.valid() || !root.valid())return null
        val scale=target.scale/root.scale
        return ContentMap(scale,target.x-root.x*scale,target.y-root.y*scale)
            .takeIf { it.valid() && it.scale<=10f }
    }

    private fun inwardCrop(l:Double,t:Double,r:Double,b:Double):StageLiveSurfaceFlightGeometry.Crop? {
        val x1=ceil(l);val y1=ceil(t);val x2=floor(r);val y2=floor(b)
        if(!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite() ||
            x1<Int.MIN_VALUE || y1<Int.MIN_VALUE || x2>Int.MAX_VALUE || y2>Int.MAX_VALUE ||
            x2-x1<2.0 || y2-y1<2.0)return null
        return StageLiveSurfaceFlightGeometry.Crop(x1.toInt(),y1.toInt(),x2.toInt(),y2.toInt())
    }

    /** Crop only; never stretch a buffer or change an already chosen content map. */
    private fun fitMapped(viewport:StageLiveSurfaceFlightGeometry.Box,
                          available:StageLiveSurfaceFlightGeometry.Crop,map:ContentMap):Fit? {
        if(!viewport.valid() || !map.valid() || map.scale>10f)return null
        val z=map.scale.toDouble()
        val crop=inwardCrop(maxOf(available.left.toDouble(),(viewport.left-map.x)/z),
            maxOf(available.top.toDouble(),(viewport.top-map.y)/z),
            minOf(available.right.toDouble(),(viewport.right-map.x)/z),
            minOf(available.bottom.toDouble(),(viewport.bottom-map.y)/z)) ?: return null
        return Fit(crop.left,crop.top,crop.right,crop.bottom,map.scale,map.x,map.y)
    }

    fun anchoredFit(viewport:StageLiveSurfaceFlightGeometry.Box,
                    local:StageLiveSurfaceFlightGeometry.Crop,
                    root:ContentMap,target:ContentMap):Fit? {
        val map=mirrorMap(target,root) ?: return null
        val z=root.scale.toDouble()
        val available=inwardCrop(local.left*z+root.x,local.top*z+root.y,
            local.right*z+root.x,local.bottom*z+root.y) ?: return null
        return fitMapped(viewport,available,map)
    }

    private fun Fit.asFrame():StageLiveSurfaceFlightGeometry.MirrorFrame {
        val crop=StageLiveSurfaceFlightGeometry.Crop(left,top,right,bottom)
        return StageLiveSurfaceFlightGeometry.MirrorFrame(scale,x,y,crop,
            StageLiveSurfaceFlightGeometry.Box(left*scale+x,top*scale+y,right*scale+x,bottom*scale+y))
    }

    /**
     * Keep the certified route's viewport as an absolute collision bound.
     * Ease only the content scale and anchor within that viewport; a larger
     * or smaller producer must not enlarge the swept card. At the endpoint
     * the same map is used by the held writer, not a fresh COVER/CONTAIN.
     */
    fun anchoredFlight(raw:StageLiveSurfaceFlightGeometry.MirrorFrame,
                       anchor:ContentAnchor,progress:Float):StageLiveSurfaceFlightGeometry.MirrorFrame? {
        if(!progress.isFinite() || !raw.box.valid() || !anchor.rawTerminal.box.valid() ||
            !raw.scale.isFinite() || raw.scale<=0f || !anchor.rawTerminal.scale.isFinite() ||
            anchor.rawTerminal.scale<=0f)return null
        if(progress<=0f)return raw
        val end=mirrorMap(anchor.target,anchor.sourceRoot) ?: return null
        val u=StageLiveSurfaceFlightGeometry.phaseEase(progress)
        val map=if(progress>=1f) end else {
            val terminal=anchor.rawTerminal
            val cx=(anchor.sourceCrop.left.toDouble()+anchor.sourceCrop.right)*.5
            val cy=(anchor.sourceCrop.top.toDouble()+anchor.sourceCrop.bottom)*.5
            val dx=(end.x+cx*end.scale-terminal.x-cx*terminal.scale)/terminal.box.width
            val dy=(end.y+cy*end.scale-terminal.y-cy*terminal.scale)/terminal.box.height
            val scale=raw.scale*(1f+(end.scale/terminal.scale-1f)*u)
            ContentMap(scale,(raw.x+cx*(raw.scale-scale)+dx*raw.box.width*u).toFloat(),
                (raw.y+cy*(raw.scale-scale)+dy*raw.box.height*u).toFloat())
        }
        // Intersect with the full certified viewport and the original
        // available crop. Rounded edges can only shrink the footprint.
        val bounds=if(progress<1f)raw.box else StageLiveSurfaceFlightGeometry.Box(
            maxOf(raw.box.left,anchor.viewport.left),maxOf(raw.box.top,anchor.viewport.top),
            minOf(raw.box.right,anchor.viewport.right),minOf(raw.box.bottom,anchor.viewport.bottom))
        return fitMapped(bounds,anchor.sourceCrop,map)?.asFrame()
    }

    fun terminalFits(anchor:ContentAnchor):Boolean {
        val projected=anchoredFlight(anchor.rawTerminal,anchor,1f) ?: return false
        val map=mirrorMap(anchor.target,anchor.sourceRoot) ?: return false
        val ideal=fitMapped(anchor.viewport,anchor.sourceCrop,map)?.asFrame() ?: return false
        // A route with a smaller/shifted certified envelope must not silently
        // cut away valid target pixels. Choose the existing safer route.
        return StageLiveSurfaceFlightGeometry.endpointGapPx(projected.box,ideal.box)<=2f
    }

    /**
     * Match the *covered* compositor flight's endpoint when the producer is
     * rebound after a real display move. The legacy fit() intentionally uses
     * CONTAIN, which can change the visible card's scale/aspect as soon as
     * the verified new Shell frame arrives. In a covered rail that is a
     * separate, visible zoom/pop during the terminal hold.
     *
     * Return null rather than expose more than 2 physical pixels of endpoint
     * overflow due to integer source-crop rounding. The caller can retain
     * the previous safe contain fit on an unsupported OEM geometry.
     * This only computes a transient mirror crop/transform; never the task.
     */
    fun coveredFit(
        targetLeft:Float,targetTop:Float,targetRight:Float,targetBottom:Float,
        cropLeft:Int,cropTop:Int,cropRight:Int,cropBottom:Int,
        producerScale:Float,producerX:Float,producerY:Float,detachedRoot:Boolean,
    ):Fit? {
        val base=fit(targetLeft,targetTop,targetRight,targetBottom,
            cropLeft,cropTop,cropRight,cropBottom,
            producerScale,producerX,producerY,detachedRoot) ?: return null
        val tw=(targetRight-targetLeft).toDouble()
        val th=(targetBottom-targetTop).toDouble()
        val sw=base.right.toLong()-base.left.toLong()
        val sh=base.bottom.toLong()-base.top.toLong()
        if(tw<2.0 || th<2.0 || sw<2L || sh<2L ||
            sw>Int.MAX_VALUE || sh>Int.MAX_VALUE)return null

        // An exact fit must keep its original crop, scale and position
        // byte-for-byte to avoid disturbing a settled task.
        if(kotlin.math.abs(sw.toDouble()*th-sh.toDouble()*tw) <=
            0.00001*sw.toDouble()*th)return base
        val cropWidth=sw.toDouble()*th > sh.toDouble()*tw
        val ideal=if(cropWidth)sh.toDouble()*tw/th else sw.toDouble()*th/tw
        if(!ideal.isFinite() || ideal<2.0)return null
        val maximum=if(cropWidth)sw else sh
        var best:Fit?=null
        var leastOverflow=Double.POSITIVE_INFINITY
        // Pick whichever adjacent integer crop produces the least screen
        // overflow. Round only the short dimension; never warp the pixels.
        for(dimension in listOf(kotlin.math.floor(ideal).toLong(),
                                kotlin.math.ceil(ideal).toLong()).distinct()) {
            if(dimension<2L || dimension>maximum)continue
            val cw=if(cropWidth)dimension else sw
            val ch=if(cropWidth)sh else dimension
            val scale=maxOf(tw/cw.toDouble(),th/ch.toDouble())
            if(!scale.isFinite() || scale<=0.0 || scale>10.0)continue
            val overflow=(cw*scale-tw).coerceAtLeast(0.0)+
                (ch*scale-th).coerceAtLeast(0.0)
            if(overflow>=leastOverflow || overflow>2.0)continue
            val l=base.left.toLong()+(sw-cw)/2
            val t=base.top.toLong()+(sh-ch)/2
            val r=l+cw
            val b=t+ch
            if(l<Int.MIN_VALUE || t<Int.MIN_VALUE ||
                r>Int.MAX_VALUE || b>Int.MAX_VALUE)continue
            val x=targetLeft.toDouble()+(tw-cw*scale)*0.5-l*scale
            val y=targetTop.toDouble()+(th-ch*scale)*0.5-t*scale
            if(!x.isFinite() || !y.isFinite() ||
                kotlin.math.abs(x)>Float.MAX_VALUE ||
                kotlin.math.abs(y)>Float.MAX_VALUE)continue
            val candidate=Fit(l.toInt(),t.toInt(),r.toInt(),b.toInt(),
                scale.toFloat(),x.toFloat(),y.toFloat())
            best=candidate
            leastOverflow=overflow
        }
        return best
    }

    fun fit(targetLeft:Float,targetTop:Float,targetRight:Float,targetBottom:Float,
            cropLeft:Int,cropTop:Int,cropRight:Int,cropBottom:Int,
            producerScale:Float,producerX:Float,producerY:Float,detachedRoot:Boolean):Fit? {
        if (!listOf(targetLeft,targetTop,targetRight,targetBottom,
                producerScale,producerX,producerY).all { it.isFinite() }) return null
        val targetWidth=targetRight-targetLeft
        val targetHeight=targetBottom-targetTop
        if (targetWidth<2f || targetHeight<2f || producerScale<=0f ||
            cropRight.toLong()-cropLeft<2L || cropBottom.toLong()-cropTop<2L) return null
        val p=if(detachedRoot) 1f else producerScale
        val x=if(detachedRoot) 0f else producerX
        val y=if(detachedRoot) 0f else producerY
        val l=ceil(cropLeft.toDouble()*p+x)
        val t=ceil(cropTop.toDouble()*p+y)
        val r=floor(cropRight.toDouble()*p+x)
        val b=floor(cropBottom.toDouble()*p+y)
        if (listOf(l,t,r,b).any { !it.isFinite() || it<Int.MIN_VALUE || it>Int.MAX_VALUE } ||
            r-l<2.0 || b-t<2.0) return null
        val width=(r-l).toFloat()
        val height=(b-t).toFloat()
        val scale=min(targetWidth/width,targetHeight/height)
        if (!scale.isFinite() || scale<=0f || scale>10f) return null
        val left=targetLeft+(targetWidth-width*scale)*.5f
        val top=targetTop+(targetHeight-height*scale)*.5f
        val offsetX=left-l.toFloat()*scale
        val offsetY=top-t.toFloat()*scale
        if (!offsetX.isFinite() || !offsetY.isFinite()) return null
        return Fit(l.toInt(),t.toInt(),r.toInt(),b.toInt(),scale,offsetX,offsetY)
    }
}

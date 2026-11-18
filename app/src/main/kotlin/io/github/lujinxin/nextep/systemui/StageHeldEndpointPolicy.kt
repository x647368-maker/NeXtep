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

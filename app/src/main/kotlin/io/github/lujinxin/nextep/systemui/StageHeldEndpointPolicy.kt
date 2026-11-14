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

package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Box
import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Crop
import org.junit.Assert.*
import org.junit.Test

class StageCertifiedRailRouteTest {
    private val small=Box(80f,450f,260f,690f)
    private val main=Box(400f,1050f,940f,1770f)
    private val other=Box(820f,450f,1000f,690f)
    private val screen=Box(0f,0f,1272f,2772f)
    private val incoming=Crop(0,0,180,240)
    private val outgoing=Crop(0,0,270,360)
    private val g=StageLiveSurfaceFlightGeometry

    @Test fun actualApi37FixtureCrossingNoLongerFullyOverlapsWhenCertified() {
        for(covered in listOf(false,true)){
            val up=g.mirrorFrame(small,main,incoming,.5f,covered)!!
            val down=g.mirrorFrame(main,small,outgoing,.5f,covered)!!
            assertTrue(StageCertifiedRailRoute.overlapFraction(up.box,down.box)>.98)
            val route=StageCertifiedRailRoute.choose(small,main,incoming,outgoing,screen,listOf(other),covered)
            assertNotNull(route)
            val plan=route!!
            assertTrue(plan.bound<=.35)
            for(i in 0..2000){
                val p=plan.at(i/2000f)
                assertTrue(StageCertifiedRailRoute.overlapFraction(p.incoming,p.outgoing)<=.3501)
                assertEquals(.75f,p.incoming.width/p.incoming.height,.0001f)
                assertEquals(.75f,p.outgoing.width/p.outgoing.height,.0001f)
                assertTrue(g.peakSweptOverlap(p.incoming,p.incoming,other)<=1.0)
                assertTrue(g.peakSweptOverlap(p.outgoing,p.outgoing,other)<=1.0)
            }
            val first=plan.at(0f);val last=plan.at(1f)
            assertTrue(g.endpointGapPx(small,first.incoming)<.02f)
            assertTrue(g.endpointGapPx(main,first.outgoing)<.02f)
            assertTrue(g.endpointGapPx(main,last.incoming)<.02f)
            assertTrue(g.endpointGapPx(small,last.outgoing)<.02f)
            // No zero-speed pause at either legacy phase split.
            for(t in listOf(.34f,.66f)){
                val a=plan.at(t-.001f);val b=plan.at(t+.001f)
                assertTrue(g.endpointGapPx(a.incoming,b.incoming)>.05f)
                assertTrue(g.endpointGapPx(a.outgoing,b.outgoing)>.05f)
            }
        }
    }

    @Test fun railNormalDirectionIsIndependentOfLateralDetours() {
        fun k(x:Float,y:Float,ox:Float,oy:Float)=PairedRailCandidate.PairBox(
            Box(x-5f,y-5f,x+5f,y+5f),Box(ox-5f,oy-5f,ox+5f,oy+5f))
        val left=listOf(k(20f,100f,200f,100f),k(100f,140f,150f,60f),k(200f,100f,20f,100f))
        assertTrue(PairedRailCandidate.followsRailAxis(left,true))
        assertTrue(PairedRailCandidate.followsRailAxis(left.reversed(),true))
        assertFalse(PairedRailCandidate.followsRailAxis(left.toMutableList().apply {
            add(2,k(90f,140f,155f,60f))
        },true))
        val top=listOf(k(100f,20f,100f,200f),k(140f,100f,60f,150f),k(100f,200f,100f,20f))
        assertTrue(PairedRailCandidate.followsRailAxis(top,false))
        assertFalse(PairedRailCandidate.followsRailAxis(top.toMutableList().apply {
            add(2,k(140f,90f,60f,155f))
        },false))
        assertFalse(PairedRailCandidate.followsRailAxis(listOf(left[0],k(200f,100f,300f,100f)),true))
    }

    @Test fun curveEvaluationMatchesOriginalNestedInterpolationExactly() {
        fun box(x:Float,y:Float,w:Float,h:Float)=Box(x,y,x+w,y+h)
        fun mix(a:Box,b:Box,t:Float):Box {
            fun edge(x:Float,y:Float)=x+(y-x)*t
            return Box(edge(a.left,b.left),edge(a.top,b.top),
                edge(a.right,b.right),edge(a.bottom,b.bottom))
        }
        fun checkBox(a:Box,b:Box) {
            assertEquals(a.left.toRawBits(),b.left.toRawBits())
            assertEquals(a.top.toRawBits(),b.top.toRawBits())
            assertEquals(a.right.toRawBits(),b.right.toRawBits())
            assertEquals(a.bottom.toRawBits(),b.bottom.toRawBits())
        }
        for((width,height) in listOf(1272f to 2772f,2772f to 1272f,2248f to 2480f,
            2480f to 2248f,2560f to 1600f,1600f to 2560f)) {
            val a=PairedRailCandidate.PairBox(box(12f,height*.25f,width*.17f,height*.28f),
                box(width*.32f,19f,width*.61f,height*.78f))
            val c=PairedRailCandidate.PairBox(box(width*.41f,height*.10f,width*.20f,height*.36f),
                box(width*.22f,height*.60f,width*.29f,height*.19f))
            val b=PairedRailCandidate.PairBox(a.outgoing,a.incoming)
            val curve=SmoothPairedRailCandidate.Curve(a,c,b)
            for(i in 0..4096) {
                val t=i/4096f
                val actual=curve.at(t)
                checkBox(mix(mix(a.incoming,c.incoming,t),mix(c.incoming,b.incoming,t),t),actual.incoming)
                checkBox(mix(mix(a.outgoing,c.outgoing,t),mix(c.outgoing,b.outgoing,t),t),actual.outgoing)
            }
        }
    }

    @Test fun usefulCoveredCropIsNotReplacedByContain() {
        assertNull(StageCertifiedRailRoute.choose(small,main,incoming,Crop(0,0,290,360),
            screen,listOf(other),true))
    }

    @Test fun abortedOrUncertifiedPlanningKeepsTheExistingRoute() {
        assertNull(StageCertifiedRailRoute.choose(small,main,incoming,outgoing,screen,listOf(other),
            false){false})
        for(limit in listOf(1,2,4,8,16)){
            var calls=0
            assertNull(StageCertifiedRailRoute.choose(small,main,incoming,outgoing,screen,listOf(other),
                false){++calls<=limit})
        }
        assertNull(StageCertifiedRailRoute.choose(small,main,incoming,outgoing,screen,
            listOf(Box(0f,0f,1272f,2772f),Box(190f,0f,410f,2772f)),false))
    }

    /** Representative geometry matrix, not physical device certification.
     * Rejected optional routes MUST leave the previously admitted path intact. */
    @Test fun certifiedRoutesAcrossThreeRailsAndSixFormFactors() {
        val canvases=listOf(1272 to 2772,2772 to 1272,2248 to 2480,2480 to 2248,
            2560 to 1600,1600 to 2560)
        var attempts=0
        var admitted=0
        for((width,height) in canvases) for(rail in listOf("left","right","top"))
            for(n in 1..4) for(selected in 0 until n) for(mode in 0..1) {
                val w=width.toFloat();val h=height.toFloat()
                val inset=16f;val gap=12f
                val sw=if(rail=="top")
                    kotlin.math.floor((w-2*inset-(n-1)*gap)/n).coerceAtMost(kotlin.math.floor(w*.25f))
                    else kotlin.math.floor(w*.20f)
                val sh=if(rail=="top")kotlin.math.floor(h*.21f)
                    else kotlin.math.floor((h-2*inset-(n-1)*gap)/n).coerceAtMost(kotlin.math.floor(h*.245f))
                val total=if(rail=="top")n*sw+(n-1)*gap else n*sh+(n-1)*gap
                val slots=(0 until n).map { i ->
                    if(rail=="top") {
                        val x=kotlin.math.floor((w-total)/2)+i*(sw+gap)
                        Box(x,inset,x+sw,inset+sh)
                    } else {
                        val y=kotlin.math.floor((h-total)/2)+i*(sh+gap)
                        val x=if(rail=="left")inset else w-inset-sw
                        Box(x,y,x+sw,y+sh)
                    }
                }
                val large=when(rail) {
                    "top"->Box(inset,inset+sh+gap,w-inset,h-inset)
                    "left"->Box(inset+sw+gap,inset,w-inset,h-inset)
                    else->Box(inset,inset,w-inset-sw-gap,h-inset)
                }
                val selectedBox=slots[selected]
                val obstacles=slots.filterIndexed { i,_ -> i!=selected }
                val a=Crop(0,0,selectedBox.width.toInt(),selectedBox.height.toInt())
                val b=if(mode==0)Crop(0,0,large.width.toInt(),large.height.toInt())
                    else Crop(0,0,width,height)
                attempts++
                if(!g.corridorAvoidsOtherSlots(selectedBox,large,a.right,a.bottom,obstacles,
                        mainCropWidth=b.right,mainCropHeight=b.bottom))continue
                val covered=g.canUseCoveredFlight(selectedBox,large,a,b,obstacles)
                val plan=StageCertifiedRailRoute.choose(selectedBox,large,a,b,Box(0f,0f,w,h),
                    obstacles,covered)?:continue
                admitted++
                assertTrue(plan.bound<=.35)
                val first=plan.at(0f);val last=plan.at(1f)
                assertTrue(g.endpointGapPx(first.incoming,g.mirrorFrame(selectedBox,large,a,0f,covered)!!.box)<=2f)
                assertTrue(g.endpointGapPx(first.outgoing,g.mirrorFrame(large,selectedBox,b,0f,covered)!!.box)<=2f)
                assertTrue(g.endpointGapPx(last.incoming,g.mirrorFrame(selectedBox,large,a,1f,covered)!!.box)<=2f)
                assertTrue(g.endpointGapPx(last.outgoing,g.mirrorFrame(large,selectedBox,b,1f,covered)!!.box)<=2f)
                var prior=plan.at(0f)
                val horizontal=rail!="top"
                val sign=if(rail=="right")-1f else 1f
                fun axis(b:Box)=if(horizontal)b.cx else b.cy
                for(i in 0..1000) {
                    val k=plan.at(i/1000f)
                    assertTrue("incoming rail reversal $rail $width/$height $selected $i",
                        (axis(k.incoming)-axis(prior.incoming))*sign>=-.005f)
                    assertTrue("outgoing rail reversal $rail $width/$height $selected $i",
                        (axis(prior.outgoing)-axis(k.outgoing))*sign>=-.005f)
                    prior=k
                    assertTrue(StageCertifiedRailRoute.overlapFraction(k.incoming,k.outgoing)<=.3501)
                    for((card,root,ratio) in listOf(
                        Triple(k.incoming,first.incoming,a.right.toFloat()/a.bottom),
                        Triple(k.outgoing,first.outgoing,b.right.toFloat()/b.bottom))) {
                        assertTrue(card.left>=-.01f && card.top>=-.01f && card.right<=w+.01f && card.bottom<=h+.01f)
                        assertEquals(ratio,card.width/card.height,.0005f)
                        for(o in obstacles)assertTrue(g.peakSweptOverlap(card,card,o)<=g.peakSweptOverlap(root,root,o)+1.05)
                    }
                }
            }
        assertEquals(360,attempts)
        // Prevent a future always-reject implementation from making the
        // safety tests vacuously green; don't require unsafe admission.
        assertTrue(admitted>=150)
    }
}

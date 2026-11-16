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
}

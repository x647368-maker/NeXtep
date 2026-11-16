package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Box
import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Crop
import org.junit.Assert.*
import org.junit.Test

class StageAdaptiveCoveredRailRouteTest {
    // Report gives screen, main transform and crop; rail positions are reconstructed
    // representative values from the portrait screenshot, not exact task metadata.
    private val screen=Box(0f,0f,1272f,2772f)
    private val main=Box(318f,460f,1272f,2539f)
    private val slots=(0..2).map{val y=539f+it*584f;Box(18f,y,300f,y+558f)}
    private val incoming=Crop(0,0,1272,2517)
    private val outgoing=Crop(0,0,954,2079)
    private val g=StageLiveSurfaceFlightGeometry
    @Test fun mixedAspectPortraitCanUseCertifiedCoveredRoute() {
        for(small in slots) {
            val obstacles=slots.filter{it!=small}
            assertTrue(g.canUseCoveredFlight(small,main,incoming,outgoing,obstacles))
            assertNull(StageCertifiedRailRoute.choose(small,main,incoming,outgoing,screen,obstacles,true))
            val plan=checkNotNull(StageAdaptiveCoveredRailRoute.choose(small,main,incoming,outgoing,screen,obstacles))
            assertTrue(plan.bound<=.35)
            for(i in 1..2000) {
                val k=plan.at(i/2000f)
                assertTrue(StageCertifiedRailRoute.overlapFraction(k.incoming,k.outgoing)<=.3501)
                for((v,c) in listOf(k.incoming to incoming,k.outgoing to outgoing)) {
                    val f=checkNotNull(StageAdaptiveCoveredRailRoute.frame(v,c))
                    assertTrue(f.crop.left>=c.left&&f.crop.top>=c.top&&f.crop.right<=c.right&&f.crop.bottom<=c.bottom)
                    assertEquals((f.crop.right-f.crop.left)*f.scale,f.box.width,.001f)
                    assertEquals((f.crop.bottom-f.crop.top)*f.scale,f.box.height,.001f)
                    assertTrue(f.box.left>=v.left-.001f&&f.box.top>=v.top-.001f&&f.box.right<=v.right+.001f&&f.box.bottom<=v.bottom+.001f)
                    assertTrue(f.box.left>=-.001f&&f.box.top>=-.001f&&f.box.right<=screen.right+.001f&&f.box.bottom<=screen.bottom+.001f)
                    for(o in obstacles)assertTrue(g.peakSweptOverlap(f.box,f.box,o)<=1.01)
                }
            }
            val end=plan.at(1f)
            val up=checkNotNull(StageAdaptiveCoveredRailRoute.frame(end.incoming,incoming))
            val down=checkNotNull(StageAdaptiveCoveredRailRoute.frame(end.outgoing,outgoing))
            assertTrue(up.crop==g.mirrorFrame(small,main,incoming,1f,true)!!.crop)
            assertTrue(down.crop==g.mirrorFrame(main,small,outgoing,1f,true)!!.crop)
            for(t in listOf(.34f,.66f)) {
                val a=plan.at(t-.001f);val b=plan.at(t+.001f)
                assertTrue(g.endpointGapPx(a.incoming,b.incoming)>.05f)
                assertTrue(g.endpointGapPx(a.outgoing,b.outgoing)>.05f)
            }
        }
    }
    @Test fun invalidSourceAndBudgetAndObstacleRejectRatherThanStretch() {
        assertNull(StageAdaptiveCoveredRailRoute.frame(main,Crop(0,0,0,0)))
        assertNull(StageAdaptiveCoveredRailRoute.choose(slots[1],main,incoming,outgoing,screen,emptyList()){false})
        assertNull(StageAdaptiveCoveredRailRoute.choose(slots[1],main,incoming,outgoing,screen,listOf(Box(302f,0f,317f,2772f))))
        for(limit in listOf(1,3,10,30)) {
            var calls=0
            assertNull(StageAdaptiveCoveredRailRoute.choose(slots[0],main,incoming,outgoing,screen,slots.drop(1)){++calls<=limit})
        }
    }
    @Test fun nonzeroCropOriginsArePreserved() {
        val c=Crop(23,47,1295,2564)
        val f=checkNotNull(StageAdaptiveCoveredRailRoute.frame(main,c))
        assertTrue(f.crop.left>=c.left&&f.crop.top>=c.top&&f.crop.right<=c.right&&f.crop.bottom<=c.bottom)
        assertEquals(f.box.left,f.x+f.crop.left*f.scale,.001f)
        assertEquals(f.box.top,f.y+f.crop.top*f.scale,.001f)
    }
}

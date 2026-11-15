package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageMenuPlacementTest {
    private fun b(l:Int,t:Int,r:Int,bt:Int)=StageMenuPlacement.Box(l,t,r,bt)
    private val safe=b(0,0,400,800)
    private val anchor=b(8,8,56,56)
    private fun choose(avoid:List<StageMenuPlacement.Box>,bounds:StageMenuPlacement.Box=safe)=
        StageMenuPlacement.compact(bounds,anchor,208,252,160,60,avoid,8)
    private fun inside(bounds:StageMenuPlacement.Box,actual:StageMenuPlacement.Box){
        assertTrue(actual.l>=bounds.l && actual.t>=bounds.t && actual.r<=bounds.r && actual.b<=bounds.b)
        assertTrue(actual.w>=160 && actual.h>=60)
    }
    @Test fun fullSizeWithoutObstacles(){
        val p=choose(emptyList())
        assertEquals(208,p.w);assertEquals(252,p.h);inside(safe,p)
    }
    @Test fun topRailUsesAvailableShortGapInsteadOfCoveringCards(){
        val avoid=listOf(b(0,150,400,300),b(0,300,400,800))
        val p=choose(avoid);inside(safe,p)
        assertTrue(p.h<252);assertTrue(avoid.all{p.overlap(it)==0L})
        assertEquals(0L,avoid.sumOf{p.overlap(it)})
    }
    @Test fun leftAndRightRailsUseEmptyBottomStrip(){
        for(left in listOf(true,false)){
            val rail=if(left)b(0,0,105,700) else b(295,0,400,700)
            val main=if(left)b(110,0,400,700) else b(0,0,290,700)
            val avoid=listOf(main,rail)
            val p=choose(avoid);inside(safe,p)
            assertTrue(p.t>=700);assertTrue(avoid.all{p.overlap(it)==0L})
        }
    }
    @Test fun narrowFreeRegionCanReduceWidthWithoutCoveringTasks(){
        val avoid=listOf(b(0,0,220,800))
        val p=choose(avoid);inside(safe,p)
        assertTrue(p.w in 160..180);assertEquals(252,p.h)
        assertEquals(0L,p.overlap(avoid.first()))
    }
    @Test fun foldAndTabletRespectScreenOffsets(){
        for(bounds in listOf(b(20,32,700,850),b(24,16,1000,640))){
            val avoid=listOf(b(bounds.l,bounds.t+110,bounds.r,bounds.b))
            val p=choose(avoid,bounds);inside(bounds,p)
            assertTrue(avoid.all{p.overlap(it)==0L})
        }
    }
    @Test fun impossibleFullViewportKeepsAReachableCompactMenu(){
        val avoid=listOf(b(100,0,400,800),b(0,0,100,800))
        val p=choose(avoid);inside(safe,p)
        assertEquals(60,p.h);assertEquals(0L,p.overlap(avoid[1]))
        assertTrue(p.overlap(avoid[0])>0)
    }
    @Test fun exactTouchingEdgesRemainValidWithoutArtificialOverlap(){
        val avoid=listOf(b(0,60,400,800))
        val p=choose(avoid);inside(safe,p)
        assertEquals(60,p.h);assertEquals(0L,p.overlap(avoid[0]))
    }
    @Test fun overlappingProtectedBoundsAndOffscreenBoundsAreClamped(){
        val avoid=listOf(b(-30,0,220,600),b(0,500,220,900))
        val p=choose(avoid);inside(safe,p)
        assertTrue(avoid.all{p.overlap(it)==0L})
    }
    @Test fun fullMenuDoesNotCollapseWhenOnlyOneRowFitsOutsideTasks(){
        val avoid=listOf(b(0,60,400,800))
        val p=StageMenuPlacement.place(safe,anchor,208,252,avoid,8)
        assertEquals(208,p.w)
        assertEquals(252,p.h)
        inside(safe,p)
    }
    @Test fun fullMenuPrefersUncoveredSpaceWhenAvailable(){
        val avoid=listOf(b(0,320,400,800))
        val p=StageMenuPlacement.place(safe,anchor,208,252,avoid,8)
        assertEquals(252,p.h)
        assertEquals(0L,p.overlap(avoid.first()))
        inside(safe,p)
    }
}

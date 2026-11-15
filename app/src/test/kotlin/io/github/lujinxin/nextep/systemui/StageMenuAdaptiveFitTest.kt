package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageMenuAdaptiveFitTest {
    private fun b(l:Int,t:Int,r:Int,b:Int)=StageMenuPlacement.Box(l,t,r,b)
    private val safe=b(0,0,360,760)
    private val anchor=b(8,4,56,52)
    private fun fit(bounds:StageMenuPlacement.Box=safe,avoid:List<StageMenuPlacement.Box>,w:Int=208,h:Int=252)=
        StageMenuPlacement.fit(bounds,anchor,w,h,avoid,8,156,60,26,48)

    @Test fun topRailLeavesOnlyToolbarStrip() {
        val protected=listOf(b(0,92,360,210),b(0,222,360,760))
        val result=fit(avoid=protected)
        assertTrue(protected.all{result.overlap(it)==0L})
        assertTrue(result.h>=60)
        assertTrue(result.h<252)
    }
    @Test fun sideRailAndMainRemainUncovered() {
        for(right in listOf(false,true)) {
            val protected=if(right)listOf(b(0,100,278,760),b(278,100,360,760))
                else listOf(b(0,100,82,760),b(82,100,360,760))
            val r=fit(avoid=protected)
            assertTrue(protected.all{r.overlap(it)==0L})
            assertTrue(r.h>=60)
        }
    }
    @Test fun fullFreeMenuKeepsOriginalPlacementAndSize() {
        val protected=listOf(b(235,0,360,760))
        assertEquals(StageMenuPlacement.place(safe,anchor,208,252,protected,8),fit(avoid=protected))
    }
    @Test fun largeScreenRetainsFullMenuWhenSpaceAllows() {
        val screen=b(0,0,720,850)
        val protected=listOf(b(250,100,720,850))
        val r=fit(screen,protected)
        assertEquals(208,r.w);assertEquals(252,r.h)
        assertEquals(0L,r.overlap(protected.first()))
    }
    @Test fun noFreeSpaceNeverCreatesInaccessibleOrOffscreenPopup() {
        val r=fit(avoid=listOf(safe))
        assertTrue(r.w>=156);assertTrue(r.h>=60)
        assertTrue(r.l>=safe.l && r.r<=safe.r && r.t>=safe.t && r.b<=safe.b)
        assertTrue(r.overlap(safe)>0L) // explicit limitation, not a no-overlap claim
    }
    @Test fun smallFreeGapCanShrinkWidthWithoutHidingActions() {
        val protected=listOf(b(0,0,183,760),b(183,350,360,760))
        val r=fit(avoid=protected)
        assertTrue(protected.all{r.overlap(it)==0L})
        assertTrue(r.w in 156..177)
        assertTrue(r.h>=60)
    }
    @Test fun emptyAvoidKeepsOriginalPopupGeometry() {
        assertEquals(StageMenuPlacement.place(safe,anchor,208,252,emptyList(),8),fit(avoid=emptyList()))
    }
}

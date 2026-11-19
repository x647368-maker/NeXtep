package workbench2.layout

import org.junit.Assert.*
import org.junit.Test
import workbench2.layout.WorkspaceLayout.Viewport
import workbench2.layout.WorkspaceLayout.Insets
import workbench2.layout.WorkspaceLayout.Rail
import workbench2.motion.Canvas
import workbench2.motion.Pose
import workbench2.unified.LaunchRequest

class WorkspaceLayoutTest {
    private val phone=Viewport(1264,2780,512,Insets(bottom=100),205,316)
    @Test fun compactTopMatchesFrozenCardAndControlGeometry() {
        val l=WorkspaceLayout.calculate(phone,Rail.TOP,listOf(0,1,2,3),false,4)
        assertEquals(4,l.cards.size)
        val d=3.2f
        val width=((1264-5*8*d)/4).toInt()
        val body=(width*14.0/9).toInt()
        val actualWidth=(body*9.0/14).toInt()
        assertEquals(actualWidth.toDouble(),l.cards[0].box.w,0.0)
        assertEquals(body.toDouble(),l.cards[0].box.h,0.0)
        assertEquals(25.0,l.cards[0].box.x,0.0)
        assertEquals(230.0,l.cards[0].box.y,0.0)
        assertEquals(4L,l.displayEpoch)
        assertTrue(l.main.y>=l.rail.y+l.rail.h)
        assertTrue(l.topActions!!.add.y>=l.cards.last().box.y+l.cards.last().box.h)
        for(count in 0..4) {
            val p=WorkspaceLayout.calculate(phone,Rail.TOP,(0 until count).toList(),true,4)
            assertEquals(count,p.cards.size)
            assertTrue(p.main.y+p.main.h<=2680)
            assertNotNull(p.topActions)
        }
    }
    @Test fun uprightSidesRespectInsetsAndFocusRefitsWithoutTaskChanges() {
        for(v in listOf(phone,Viewport(2248,2480,320,Insets(20,30,40,50),128,500),
            Viewport(2560,1600,320,Insets(12,30,18,40),128,495),
            Viewport(1600,2560,320,Insets(20,30,40,50),128,385))) {
            for(rail in listOf(Rail.LEFT,Rail.RIGHT)) for(count in 0..4) {
                val p=WorkspaceLayout.calculate(v,rail,(0 until count).toList(),false,9)
                assertEquals(count,p.cards.size)
                assertTrue(p.main.x>=v.insets.left && p.main.y>=v.insets.top)
                assertTrue(p.main.x+p.main.w<=v.width-v.insets.right)
                assertTrue(p.main.y+p.main.h<=v.height-v.insets.bottom)
                assertTrue(WorkspaceLayout.focusedMain(v,rail).w>=p.main.w)
                p.cards.zipWithNext().forEach { (a,b)->assertTrue(a.box.y+a.box.h<=b.box.y) }
            }
        }
    }
    @Test fun sparseSlotMappingUsesExistingCorePlanAndRealCanvasFit() {
        val p=WorkspaceLayout.calculate(phone,Rail.TOP,listOf(3,0),true,11)
        val main=LaunchRequest(1,0,"a/.Main")
        val a=LaunchRequest(2,999,"a/.Main")
        val b=LaunchRequest(3,0,"b/.Main")
        val plan=p.startPlan(main,mapOf(3 to b,0 to a))
        assertEquals(listOf(main,a,b),plan.requests)
        assertEquals(listOf(p.main,p.cards[0].box,p.cards[1].box),plan.places)
        assertEquals(11L,plan.displayEpoch)
        // Canvas is actual caller input, independent of the historical card ratio.
        val real=Canvas(1272,2772,512,8)
        val fit=Pose.fit(plan.places[1],real,24.0)
        assertEquals(real,fit.canvas)
        assertTrue(fit.content.h<=plan.places[1].h+1e-8)
        assertTrue(fit.content.w<=plan.places[1].w+1e-8)
        rejected { p.startPlan(main,mapOf(0 to a)) }
    }
    @Test fun invalidAndUnverifiedLayoutsFailWithoutChangingPolicy() {
        rejected { WorkspaceLayout.calculate(phone,Rail.TOP,listOf(0,0),false,1) }
        rejected { WorkspaceLayout.calculate(phone,Rail.TOP,listOf(4),false,1) }
        rejected { WorkspaceLayout.calculate(phone,Rail.TOP,listOf(0),false,-1) }
        rejected { WorkspaceLayout.calculate(Viewport(2780,1264,512,Insets(),205,316),Rail.LEFT,listOf(0),false,1) }
        rejected { WorkspaceLayout.calculate(Viewport(100,200,512,Insets(),20,25),Rail.TOP,listOf(0,1,2,3),false,1) }
    }
    @Test fun topRailUsesFrozenFoldAndTabletDepthCaps() {
        val fold=Viewport(2248,2480,320,Insets(20,30,40,50),128,500)
        val tablet=Viewport(2560,1600,320,Insets(12,30,18,40),128,495)
        for((v,cap) in listOf(fold to .24,tablet to .32)) for(count in 0..4) {
            val l=WorkspaceLayout.calculate(v,Rail.TOP,(0 until count).toList(),false,12)
            assertTrue(l.rail.h<=v.usableHeight*cap+0.51)
            assertEquals(count,l.cards.size)
            assertTrue(l.main.y>=l.rail.y+l.rail.h)
            assertTrue(l.main.y+l.main.h<=v.height-v.insets.bottom)
        }
        val rotated=Viewport(2780,1264,512,Insets(right=100),205,316)
        val l=WorkspaceLayout.calculate(rotated,Rail.TOP,listOf(0,1,2,3),false,13)
        assertEquals(4,l.cards.size)
        assertTrue(l.main.y+l.main.h<=1264)
    }
    private fun rejected(block: ()->Unit) {
        try { block(); fail("Invalid layout was accepted") } catch (_: IllegalArgumentException) {}
    }
}

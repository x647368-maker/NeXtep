package workbench2.layout

import org.junit.Assert.*
import org.junit.Test
import workbench2.layout.WorkspaceLayout.Viewport
import workbench2.layout.WorkspaceLayout.Insets
import workbench2.layout.WorkspaceLayout.Rail
import workbench2.motion.Canvas
import workbench2.motion.Pose
import workbench2.unified.LaunchRequest
import kotlin.math.roundToInt

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

    @Test fun reverseLandscapeProjectsSelectedRailAndHitBoxesWithoutChangingSlots() {
        val right=Viewport(2354,1080,480,Insets(left=132),192,270,rotation=3)
        val left=Viewport(2354,1080,480,Insets(right=132),192,270,rotation=1)
        for(rail in listOf(Rail.LEFT,Rail.RIGHT)) for(count in 0..4) {
            val a=WorkspaceLayout.calculate(right,rail,(0 until count).toList(),false,15)
            val b=WorkspaceLayout.calculate(left,rail,(0 until count).toList(),false,16)
            assertEquals(90,a.panelRotationDegrees)
            assertEquals(-90,b.panelRotationDegrees)
            assertEquals(2354-a.control.x-a.control.w,b.control.x,0.0)
            assertEquals(2354-a.rail.x-a.rail.w,b.rail.x,0.0)
            assertEquals(1080-a.rail.y-a.rail.h,b.rail.y,0.0)
            assertEquals(rail==Rail.LEFT,a.rail.y==0.0)
            assertEquals(a.main.w,b.main.w,0.0)
            assertEquals(a.main.h,b.main.h,0.0)
            assertEquals(2354-a.main.x-a.main.w,b.main.x,1.0)
            assertEquals(1080-a.main.y-a.main.h,b.main.y,1.0)
            for((c,d) in a.cards.zip(b.cards)) {
                assertEquals(c.slot,d.slot)
                assertEquals(2354-c.box.x-c.box.w,d.box.x,0.0)
                assertEquals(1080-c.box.y-c.box.h,d.box.y,0.0)
                assertEquals(c.slot,a.hitAt(c.box.cx,c.box.cy)!!.slot)
                assertEquals(d.slot,b.hitAt(d.box.cx,d.box.cy)!!.slot)
            }
        }
    }

    @Test fun explicitPanelRotationAndTopControlsRemainSafeAcrossFourRotations() {
        for(rotation in 0..3) {
            val v=if(rotation%2==0) Viewport(1264,2780,512,Insets(12,40,20,96),205,316,rotation=rotation)
                else Viewport(2780,1264,512,Insets(96,12,40,20),205,316,rotation=rotation)
            for(rail in Rail.entries) for(count in 0..4) {
                val l=WorkspaceLayout.calculate(v,rail,(0 until count).toList(),true,rotation.toLong())
                assertEquals(count,l.cards.size)
                assertEquals(if(rail==Rail.TOP || rotation%2==0)0 else if(rotation==1)-90 else 90,l.panelRotationDegrees)
                for(b in listOf(l.control,l.rail,l.main)+l.cards.map{it.box}) {
                    assertTrue(b.x>=v.insets.left && b.y>=v.insets.top)
                    assertTrue(b.x+b.w<=v.width-v.insets.right && b.y+b.h<=v.height-v.insets.bottom)
                }
                l.topActions?.let { actions ->
                    assertEquals(WorkspaceLayout.HitKind.COLLAPSE,l.hitAt(actions.collapse.cx,actions.collapse.cy)!!.kind)
                    assertEquals(WorkspaceLayout.HitKind.MAIN_ACTION,l.hitAt(actions.main.cx,actions.main.cy)!!.kind)
                    assertEquals(WorkspaceLayout.HitKind.ADD,l.hitAt(actions.add.cx,actions.add.cy)!!.kind)
                    assertNull(l.hitAt(actions.add.x+actions.add.w,actions.add.cy))
                }
                assertNull(l.hitAt(l.main.cx,l.main.cy))
            }
        }
    }

    @Test fun realAndroidInsetsAndHistoricalTabletSafetyStayInDestinationSpace() {
        val bars=android.view.WindowInsets.Type.systemBars()
        val insets=android.view.WindowInsets.Builder()
            .setInsets(bars,android.graphics.Insets.NONE)
            .setInsetsIgnoringVisibility(bars,android.graphics.Insets.of(12,30,18,40))
            .build()
        val tablet=WorkspaceLayout.fromWindowInsets(2560,1600,320,1,insets,Rail.LEFT,3,true)
        assertEquals(Insets(12,96,18,40),tablet.insets)
        assertEquals(112,tablet.controlHeight)
        assertEquals(24,tablet.mainInset)
        val layout=WorkspaceLayout.calculate(tablet,Rail.LEFT,listOf(0,1,2),true,17)
        assertEquals(0,layout.panelRotationDegrees)
        assertTrue(layout.main.y>=96+112+24)
        assertTrue(layout.main.x>=12+tablet.sideWidth+24)
        val phone=WorkspaceLayout.fromWindowInsets(1264,2780,512,0,insets,Rail.RIGHT,1,false)
        assertEquals(Insets(12,30,18,40),phone.insets)
        assertEquals((phone.usableWidth*.215f).roundToInt(),phone.sideWidth)
        val portrait=WorkspaceLayout.fromWindowInsets(1600,2560,320,0,insets,Rail.LEFT,2,false)
        assertEquals((portrait.usableWidth*.18f).roundToInt(),portrait.sideWidth)
    }

    @Test fun mainPriorityShrinksControlWithoutOverlappingFooterOrMutatingCanvas() {
        val v=Viewport(1264,2780,512,Insets(bottom=96),307,316,38,0)
        for(rail in Rail.entries) for(count in 0..4) {
            val active=(0 until count).toList()
            val normal=WorkspaceLayout.calculate(v,rail,active,false,18)
            val main=WorkspaceLayout.calculate(v,rail,active,true,18)
            assertEquals(179.0,main.control.h,0.0)
            assertTrue(main.main.w>=normal.main.w && main.main.h>=normal.main.h)
            assertEquals(normal.cards.map{it.slot},main.cards.map{it.slot})
            assertEquals(307,v.controlHeight)
            if(rail==Rail.TOP)assertTrue(main.main.y>=main.rail.y+main.rail.h)
            val c=Canvas(1272,2772,512,19)
            assertEquals(c,Pose.fit(main.main,c,24.0).canvas)
        }
    }

    @Test fun sparseVisualOrderAndFoldFocusPreserveIdentityAndReservedEdge() {
        val v=Viewport(2248,2480,416,Insets(top=72,bottom=44),166,562,rotation=0)
        val l=WorkspaceLayout.calculate(v,Rail.LEFT,listOf(0,3),true,20,listOf(3,1,0,2))
        assertEquals(listOf(3,0),l.cards.map{it.slot})
        assertEquals(3,l.hitAt(l.cards.first().box.cx,l.cards.first().box.cy)!!.slot)
        assertTrue(WorkspaceLayout.focusedMain(v,Rail.LEFT,true).x>=114)
        assertTrue(WorkspaceLayout.focusedMain(v,Rail.LEFT,true).w>l.main.w)
        val a=LaunchRequest(1,0,"a/.Main")
        val b=LaunchRequest(2,999,"b/.Main")
        val c=LaunchRequest(3,0,"c/.Main")
        assertEquals(listOf(a,c,b),l.startPlan(a,mapOf(0 to b,3 to c)).requests)
        rejected { WorkspaceLayout.calculate(v,Rail.LEFT,listOf(0,3),true,20,listOf(0,0,2,3)) }
    }
    private fun rejected(block: ()->Unit) {
        try { block(); fail("Invalid layout was accepted") } catch (_: IllegalArgumentException) {}
    }
}

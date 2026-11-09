package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test
import io.github.x647368maker.atomicworkbench.display.SlotGeometry
import io.github.x647368maker.atomicworkbench.framework.WindowCornerPolicy

class LiveCardRegressionTest {
    private fun profile(w: Int = 1264, h: Int = 2780, dpi: Int = 512, top: Boolean = false, count: Int = 4): WorkspaceLayoutProfile {
        val p = WorkspaceLayoutProfile.create(w, h, dpi, WorkspaceNavigationInsets(0, 0, 0, 96))
        return p.copy(geometry = p.geometry.copy(railAtTop = top), activeCount = count)
    }
    @Test fun compactFrozenCanvasesHaveNoInternalLetterboxAndExactSideGaps() {
        val p = profile()
        val sources = listOf(SlotGeometry(720,1120,320), p.canvas, SlotGeometry(720,1120,320), p.canvas)
        val boxes = StageLiveCardLayout.bounds(p, sources, List(4) { true }, listOf(0,1,2,3))
        boxes.forEachIndexed { i, b ->
            assertTrue(kotlin.math.abs(b.width.toFloat()/b.height - sources[i].width.toFloat()/sources[i].height) < .005f)
            assertTrue(b.width <= p.geometry.sidebarWidth - 2 * 26)
            assertTrue(b.top >= 25)
            if (i > 0) assertEquals(26, b.top - boxes[i-1].top - boxes[i-1].height)
        }
        assertTrue(boxes.last().top + boxes.last().height < p.geometry.sidebarLogicalHeight - 150)
    }
    @Test fun zeroThroughFourNoEmptyCapacityAndBothSidesFit() {
        for ((w,h,dpi) in listOf(Triple(1264,2780,512),Triple(2780,1264,512),Triple(2248,2480,420),Triple(1140,2616,480))) {
            for (n in 0..4) for (side in SidebarSide.entries) {
                val base = profile(w,h,dpi,count=n)
                val p=base.copy(geometry=base.geometry.copy(sidebarSide=side))
                val occupied=List(4) { it < n }
                val boxes=StageLiveCardLayout.bounds(p,List(4) { SlotGeometry(720,1120,320) },occupied,listOf(3,2,1,0))
                val shown=(3 downTo 0).filter { occupied[it] }.map { boxes[it] }
                shown.forEach { b -> assertTrue(b.width>0 && b.height>0 && b.top>=0); assertTrue(b.top+b.height<=p.geometry.sidebarLogicalHeight) }
                shown.zipWithNext().forEach { (a,b) -> assertTrue(b.top>=a.top+a.height) }
            }
        }
    }
    @Test fun topRetainsWidthLedFourCardsAndUniformGap() {
        val base=profile(top=true)
        val p=base.copy(geometry=base.geometry.copy(sidebarWidth=620))
        val boxes=StageLiveCardLayout.bounds(p,List(4) { p.canvas },List(4) { true },listOf(0,1,2,3))
        boxes.forEach { assertEquals(p.slots[0].width,it.width); assertTrue(kotlin.math.abs(p.slots[0].height-it.height)<=1) }
        boxes.zipWithNext().forEach { (a,b) -> assertEquals(26,b.left-a.left-a.width) }
    }
    @Test fun previewRadiusIsVisibleAndBoundedEvenForSmallLiveBuffers() {
        assertEquals(51.2f,WindowCornerPolicy.previewRadius(240f,374f,3.2f),.01f)
        assertTrue(WindowCornerPolicy.previewRadius(30f,20f,3.2f)<=10)
    }
    @Test fun geometryRecordRevisionRejectsOldButPreservesCurrent() {
        val p=profile()
        assertEquals(p, WorkspaceLayoutProfile.decode(p.encode()))
        assertNull(WorkspaceLayoutProfile.decode(p.encode().replaceFirst("18,","17,")))
    }
    @Test fun measuredRegressionTableUsesActualKotlinPolicies() {
        val dpi=512;val density=3.2f
        for(top in listOf(false,true)) {
            val base=profile(top=top)
            val toolbar=base.geometry.copy(topHeight=(96*density).toInt())
            val g=StageAdaptiveLayout.rail(if(top) toolbar.copy(sidebarWidth=StageTopLayoutMetrics.depth(toolbar,density,4)) else toolbar,4,density)
            val p=StageMainWindowMetrics.enlarge(StageAdaptiveLayout.mainPriority(base.copy(geometry=g),4),true)
            val sources=listOf(SlotGeometry(720,1120,320),SlotGeometry(1264,2684,512),SlotGeometry(720,1120,320),SlotGeometry(1264,2684,512))
            val old=p.slots
            val oldPoses=if(top) io.github.x647368maker.atomicworkbench.systemui.StageTopRowPolicy.positions(List(4){true},old.map{it.width},old.map{it.height},old.map{it.left},old.map{it.top},p.geometry.sidebarPhysicalWidth,p.geometry.sidebarPhysicalHeight,density)
                else io.github.x647368maker.atomicworkbench.systemui.StageActiveWindowPolicy.positions(List(4){true},old.map{it.height},old.map{it.top},p.geometry.sidebarLogicalHeight,listOf(0,1,2,3),density,false)
            val fixed=StageLiveCardLayout.bounds(p,sources,List(4){true},listOf(0,1,2,3))
            for(i in 0..3) {
                val box=old[i];val pose=oldPoses.getValue(i)
                val fit=minOf(box.width.toFloat()/sources[i].width,box.height.toFloat()/sources[i].height)*pose.scale
                val y=box.top+pose.y+(box.height-box.height*pose.scale)/2+(box.height*pose.scale-sources[i].height*fit)/2
                println("REGRESSION mode=${if(top) "top" else "side"} slot=$i source=${sources[i].width}x${sources[i].height} oldView=${box.width}x${box.height} oldLive=${sources[i].width*fit}x${sources[i].height*fit} oldLiveY=$y new=${fixed[i].width}x${fixed[i].height} newY=${fixed[i].top} main=${p.geometry.contentWidth}x${p.geometry.contentHeight}")
            }
        }
    }

}

package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.systemui.StageTopRowPolicy
import org.junit.Assert.*
import org.junit.Test

class ReadableTopCardsTest {
    private fun profile(count: Int, priority: Boolean, dpi: Int = 512,
        insets: WorkspaceNavigationInsets = WorkspaceNavigationInsets(bottom = 96)): WorkspaceLayoutProfile {
        val base = WorkspaceLayoutProfile.create(1264, 2780, dpi, insets)
        val d = dpi / 160f
        val g = base.geometry.copy(railAtTop = true, topHeight = (96 * d).toInt())
        val ordinary = base.copy(geometry = StageAdaptiveLayout.rail(g, count, d), activeCount = count)
        return if (priority) StageAdaptiveLayout.mainPriority(ordinary, count) else ordinary
    }
    @Test fun aceFourCardsFillSafeWidthAndActualBuffersAreReadableInBothPolicies() {
        for (priority in listOf(false,true)) {
            val p = profile(4,priority); val g = p.geometry; val b = p.slots
            val poses = StageTopRowPolicy.positions(List(4){true}, b.map{it.width}, b.map{it.height},
                b.map{it.left},b.map{it.top},g.sidebarPhysicalWidth,g.sidebarPhysicalHeight,3.2f)
            val first = b[0].left + poses.getValue(0).x
            val last = b[3].left + poses.getValue(3).x + b[3].width
            assertTrue((last-first)/g.availableWidth in .90f.. .96f)
            b.forEach { card ->
                assertTrue(card.width.toFloat()/g.availableWidth in .20f.. .23f)
                assertTrue(card.width >= 280 && card.height >= 435)
                val t = p.previewTransform(card.width,card.height)
                assertEquals(card.width.toFloat(),card.width*t.scaleX,1.5f)
                assertEquals(card.height.toFloat(),card.height*t.scaleY,2f)
                assertEquals(1120f/720f,card.height.toFloat()/card.width,.01f)
            }
            // Baseline v0.9.2's 144dp rail left only ~256px body/164px width.
            assertTrue(b[0].width >= 164 * 1.65f)
            assertTrue(b[0].width * b[0].height >= 164*256*2.8f)
            assertTrue(g.sidebarPhysicalHeight > 144*3.2f)
            assertTrue(g.contentWidth >= 800 && g.contentHeight >= 1700)
            assertTrue(g.contentTop >= g.sidebarTop+g.sidebarPhysicalHeight)
            assertTrue(g.contentBottom <= g.availableBottom)
            assertEquals(g.availableWidth*g.contentScale,g.contentWidth.toFloat(),.51f)
            assertEquals(g.availableHeight*g.contentScale,g.contentHeight.toFloat(),.51f)
            println("ACE top4 priority=$priority card=${b[0].width}x${b[0].height} group=${last-first}/${g.availableWidth} main=${g.contentWidth}x${g.contentHeight}")
        }
    }
    @Test fun actualCountsDensitiesInsetsAndManualPoliciesFitWithoutEmptyColumns() {
        for(dpi in listOf(480,512,560)) for(navigation in listOf(WorkspaceNavigationInsets(),
            WorkspaceNavigationInsets(top=120,bottom=96),WorkspaceNavigationInsets(left=24,right=24,bottom=150)))
        for(count in 0..4) for(priority in listOf(false,true)) {
            val p=profile(count,priority,dpi,navigation); val g=p.geometry; val b=p.slots;val d=dpi/160f
            val occupied=List(4){it<count}
            val poses=StageTopRowPolicy.positions(occupied,b.map{it.width},b.map{it.height},b.map{it.left},b.map{it.top},g.sidebarPhysicalWidth,g.sidebarPhysicalHeight,d)
            assertEquals(count,poses.size)
            val controls=StageTopLayoutMetrics.actions(g.sidebarPhysicalWidth,g.sidebarPhysicalHeight,d)
            poses.forEach { (i,pose) ->
                val left=b[i].left+pose.x;val top=b[i].top+pose.y
                assertEquals(1f,pose.scale,0f)
                assertTrue(left>=0 && left+b[i].width<=g.sidebarPhysicalWidth+1)
                assertTrue(top>=0 && top+b[i].height<=controls.add.top+1)
                if(i>0){val previous=b[i-1].left+poses.getValue(i-1).x+b[i-1].width
                    assertEquals(8*d,left-previous,.1f)}
            }
            if(count==0) assertEquals((56*d).toInt().toFloat(),g.sidebarWidth.toFloat(),1f)
            assertTrue(g.contentLeft>=g.availableLeft && g.contentRight<=g.availableRight)
            assertTrue(g.contentBottom<=g.availableBottom)
            assertEquals(p,WorkspaceLayoutProfile.decode(p.encode()))
        }
    }
    @Test fun priorityCompactsPaddingInsteadOfShrinkingCards() {
        for(count in 1..4) {
            val complete=profile(count,false); val priority=profile(count,true)
            assertEquals(complete.slots.map{it.width to it.height},priority.slots.map{it.width to it.height})
            assertTrue(priority.geometry.contentHeight>=complete.geometry.contentHeight)
            assertEquals(complete.canvas,priority.canvas);assertEquals(complete.source,priority.source)
        }
    }
    @Test fun removingMiddleTaskReflowsActualSurvivorsWithoutChangingSlotIdentity() {
        val p=profile(3,true);val b=p.slots;val g=p.geometry
        val poses=StageTopRowPolicy.positions(listOf(true,false,true,true),b.map{it.width},b.map{it.height},b.map{it.left},b.map{it.top},g.sidebarPhysicalWidth,g.sidebarPhysicalHeight,3.2f)
        assertEquals(setOf(0,2,3),poses.keys)
        val lefts=listOf(0,2,3).map { b[it].left+poses.getValue(it).x }
        for(i in 1..2) assertEquals(25.6f,lefts[i]-lefts[i-1]-b[0].width,.1f)
        val before=profile(4,true)
        assertTrue(p.slots[0].width>before.slots[0].width)
    }
    @Test fun oldTinyGeometryCacheIsInvalidatedAndNewRecordRoundTrips() {
        val p=profile(4,true)
        assertNull(WorkspaceLayoutProfile.decode(p.encode().replaceFirst("15,","14,")))
        assertEquals(p,WorkspaceLayoutProfile.decode(p.encode()))
    }
}

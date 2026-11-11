package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression proof for physical fold classes. Runtime taps still require real devices. */
class StageN5ResponsiveLayoutTest {
    private fun n5(side: SidebarSide = SidebarSide.LEFT) =
        WorkspaceGeometry.forDisplay(2248,2480,side,density=2.6f,
            navigationInsets=WorkspaceNavigationInsets(top=72,bottom=44))
    private fun onePlus() =
        WorkspaceGeometry.forDisplay(1140,2616,SidebarSide.LEFT,density=3f)
    private fun y700() =
        WorkspaceGeometry.forDisplay(2560,1600,SidebarSide.LEFT,density=2.4f)

    @Test fun deviceClassNeverAppliesUnfoldedFoldRulesToOnePlusOrLenovo() {
        assertTrue(StageDisplayPolicy.unfoldedNearSquare(2248,2480))
        assertTrue(StageDisplayPolicy.unfoldedNearSquare(2480,2248))
        assertFalse(StageDisplayPolicy.unfoldedNearSquare(1140,2616))
        assertFalse(StageDisplayPolicy.unfoldedNearSquare(2560,1600))
        assertFalse(StageDisplayPolicy.unfoldedNearSquare(1600,2560))
        assertEquals(5, StageDisplayPolicy.quickBarCapacity(1140,2616))
        assertEquals(10, StageDisplayPolicy.quickBarCapacity(2248,2480))
        assertEquals(12, StageDisplayPolicy.quickBarCapacity(2560,1600))
        assertEquals(12, StageDisplayPolicy.quickBarCapacity(1600,2560))
    }

    @Test fun topRowIsCompactAndMainTaskIsLargerThanEveryPreviewOnN5() {
        val g = n5()
        val d = 2.6f
        val depth = StageTopLayoutMetrics.depth(g,d,4)
        assertTrue(depth <= g.availableHeight*.241f)
        val top = g.copy(railAtTop=true,sidebarWidth=depth)
        val profile = WorkspaceLayoutProfile(top,416,2,activeCount=4)
        assertTrue(top.contentHeight > top.sidebarPhysicalHeight)
        assertTrue(top.contentWidth > profile.slots.first().width)
        assertTrue(top.contentHeight > profile.slots.first().height)
        assertTrue(top.contentLeft >= top.availableLeft)
        assertTrue(top.contentRight <= top.availableRight)
        assertTrue(top.contentTop >= top.sidebarTop + top.sidebarPhysicalHeight)
        assertTrue(top.contentBottom <= top.availableBottom)
        assertTrue(profile.slots.all { it.top + it.height <= top.sidebarPhysicalHeight })
        // Wide tablet cap stays independent; compact OnePlus is unchanged.
        assertTrue(StageTopLayoutMetrics.depth(y700(),2.4f,4) <= y700().availableHeight*.33f)
        assertTrue(StageTopLayoutMetrics.depth(onePlus(),3f,4) > 0)
    }

    @Test fun autoHiddenSideRailReclaimsMainAreaWhileEdgeHandleRemainsTouchable() {
        for (side in SidebarSide.entries) {
            val base = StageAdaptiveLayout.rail(n5(side),4,2.6f)
            val hidden = StageAutoHideFoldPolicy.reclaim(base,2.6f)
            assertTrue(hidden.sidebarWidth < base.sidebarWidth)
            assertTrue(hidden.contentWidth > base.contentWidth)
            assertTrue(hidden.contentHeight >= base.contentHeight)
            assertEquals(base.screenWidth,hidden.screenWidth)
            assertEquals(base.screenHeight,hidden.screenHeight)
            assertEquals(base.contentScale < hidden.contentScale,true)
            val x = StageAutoHideFoldPolicy.edgeHandleX(hidden,2.6f)
            assertTrue(x >= hidden.availableLeft)
            assertTrue(x + (24*2.6f).toInt() <= hidden.availableRight)
            if (side == SidebarSide.LEFT) assertTrue(x + (24*2.6f).toInt() <= hidden.contentLeft)
            else assertTrue(x >= hidden.contentRight)
            assertEquals(base, StageAutoHideFoldPolicy.reclaim(base.copy(railAtTop=true,
                sidebarWidth=StageTopLayoutMetrics.depth(base,2.6f)),2.6f).copy(railAtTop=false,sidebarWidth=base.sidebarWidth))
        }
    }

    @Test fun compactOnePlusAndWideY700KeepPriorMainRailGeometry() {
        for (g in listOf(onePlus(),y700())) {
            assertEquals(g,StageAutoHideFoldPolicy.reclaim(g,3f))
            assertEquals(g.contentWidth,StageAutoHideFoldPolicy.reclaim(g,3f).contentWidth)
            assertEquals(g.sidebarPhysicalWidth,StageAutoHideFoldPolicy.reclaim(g,3f).sidebarPhysicalWidth)
        }
    }
}

package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test

class StageCollapsedRailPolicyTest {
    @Test fun collapsedButtonsStayVisibleAndTouchSizedForBothLayoutsSidesAndRotations() {
        for (rotation in 0..3) for (side in SidebarSide.entries) for (top in listOf(false,true)) {
            val base = WorkspaceGeometry.forDisplay(1264,2780,side,3.2f,WorkspaceNavigationInsets(bottom=100)).rotatedTo(rotation).copy(railAtTop=top)
            val current = StageCollapsedRailPolicy.geometry(base,3.2f)
            val button = StageCollapsedRailPolicy.handle(base,current,3.2f)
            assertTrue(button.width >= 140 && button.height >= 140)
            assertTrue(button.left >= current.availableLeft && button.right <= current.availableRight)
            assertTrue(button.top >= current.availableTop && button.bottom <= current.availableBottom)
            assertTrue(button.top >= if(top) current.availableTop + base.topHeight else current.availableTop)
        }
    }
    @Test fun collapsedControlsHaveTheirOwnSpaceInsteadOfCoveringTheMainApp() {
        for (rotation in 0..3) for (side in SidebarSide.entries) for (top in listOf(false,true)) {
            val base = WorkspaceGeometry.forDisplay(1264,2780,side,3.2f).rotatedTo(rotation).copy(railAtTop=top)
            val current = StageCollapsedRailPolicy.geometry(base,3.2f)
            val button = StageCollapsedRailPolicy.handle(base,current,3.2f)
            val main = StageTopLayoutMetrics.Box(current.contentLeft,current.contentTop,current.contentWidth,current.contentHeight)
            assertFalse(button.overlaps(main))
        }
    }
    @Test fun collapsedTopButtonNeverUsesNegativeFooterCoordinatesAndIsApartFromMainMenu() {
        val base=WorkspaceGeometry.forDisplay(1264,2780,SidebarSide.LEFT,3.2f).copy(railAtTop=true)
        val current=StageCollapsedRailPolicy.geometry(base,3.2f)
        val button=StageCollapsedRailPolicy.handle(base,current,3.2f)
        val menu=StageTopLayoutMetrics.Box(current.contentLeft+(current.contentWidth-204)/2,current.contentTop-76,204,76)
        assertTrue(button.top >= base.topHeight)
        assertFalse(button.overlaps(menu))
    }
}

package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test

class StageSparseRailReflowPolicyTest {
    private val ace = WorkspaceGeometry.forDisplay(
        1140, 2616, SidebarSide.LEFT, density = 3f,
        navigationInsets = WorkspaceNavigationInsets(top = 80, bottom = 40))
    private val tablet = WorkspaceGeometry.forDisplay(
        1600, 2560, SidebarSide.RIGHT, density = 2f)

    @Test fun leftRightCountChangesAreAllowedWithoutTouchingMainGeometry() {
        for (g in listOf(ace, ace.copy(sidebarSide=SidebarSide.RIGHT), tablet)) {
            for ((old,now) in listOf(1 to 2,2 to 3,3 to 4,4 to 3,3 to 2)) {
                val content = listOf(g.contentLeft,g.contentTop,g.contentWidth,g.contentHeight)
                assertTrue(StageSparseRailReflowPolicy.supported(g,g,old,now))
                assertEquals(content,listOf(g.contentLeft,g.contentTop,g.contentWidth,g.contentHeight))
            }
        }
        assertFalse(StageSparseRailReflowPolicy.supported(ace,ace,2,2))
        assertFalse(StageSparseRailReflowPolicy.supported(ace,ace,0,1))
    }

    @Test fun xAndYAlwaysUseSameScaleAndMaintainPreviousVisibleCentre() {
        val before=StageSparseRailReflowPolicy.Bounds(30f,250f,150f,300f)
        val after=StageSparseRailReflowPolicy.Bounds(50f,320f,180f,360f)
        val start=StageSparseRailReflowPolicy.start(before,after)!!
        assertEquals(150f/180f,start.uniformScale,.001f)
        assertEquals(before.cx,after.cx+start.offsetX,.001f)
        assertEquals(before.cy,after.cy+start.offsetY,.001f)
        assertEquals(1f,start.alpha,0f)
        assertEquals(190L,StageSparseRailReflowPolicy.DURATION_MS)
    }

    @Test fun newCardAppearsAtFinalPlaceWithoutFlyingAcrossMenuOrAdd() {
        val target=StageSparseRailReflowPolicy.Bounds(25f,700f,160f,330f)
        val start=StageSparseRailReflowPolicy.start(null,target)!!
        assertEquals(0f,start.offsetX,0f)
        assertEquals(0f,start.offsetY,0f)
        assertEquals(.94f,start.uniformScale,.001f)
        assertTrue(start.alpha in 0f..1f)
    }

    @Test fun deviceOrientationFoldTopOrUnrelatedGeometryNeverAnimates() {
        val landscape=WorkspaceGeometry.forDisplay(
            2616,1140,SidebarSide.LEFT,density=3f,displayRotation=1)
        val n5=WorkspaceGeometry.forDisplay(2248,2480,density=2.6f)
        val top=ace.copy(railAtTop=true,sidebarWidth=200)
        assertFalse(StageSparseRailReflowPolicy.supported(null,ace,2,3))
        assertFalse(StageSparseRailReflowPolicy.supported(ace,landscape,2,3))
        assertFalse(StageSparseRailReflowPolicy.supported(n5,n5,2,3))
        assertFalse(StageSparseRailReflowPolicy.supported(top,top,2,3))
        assertFalse(StageSparseRailReflowPolicy.supported(
            ace,ace.copy(sidebarSide=SidebarSide.RIGHT),2,3))
        assertFalse(StageSparseRailReflowPolicy.supported(
            ace,ace.copy(displayRotation=1),2,3))
    }

    @Test fun invalidOrStretchingFramesSkipTransitionInsteadOfDistorting() {
        val target=StageSparseRailReflowPolicy.Bounds(20f,30f,200f,400f)
        assertNull(StageSparseRailReflowPolicy.start(
            StageSparseRailReflowPolicy.Bounds(40f,50f,210f,160f),target))
        assertNull(StageSparseRailReflowPolicy.start(
            StageSparseRailReflowPolicy.Bounds(40f,50f,0f,100f),target))
        assertNull(StageSparseRailReflowPolicy.start(null,
            StageSparseRailReflowPolicy.Bounds(0f,0f,0f,200f)))
        assertNull(StageSparseRailReflowPolicy.start(
            StageSparseRailReflowPolicy.Bounds(0f,0f,700f,1400f),target))
    }
}

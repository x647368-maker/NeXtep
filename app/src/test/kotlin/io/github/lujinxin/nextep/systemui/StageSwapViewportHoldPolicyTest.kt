package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.workspace.SidebarSide
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceGeometry
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceNavigationInsets
import org.junit.Assert.*
import org.junit.Test

class StageSwapViewportHoldPolicyTest {
    @Test fun transientOccupiedStateReflowMustNotCancelRealSwapMotionOnAnyDisplay() {
        val canvases=listOf(
            1140 to 2616, 2616 to 1140, // compact phone
            2248 to 2480, 2480 to 2248, // unfolded fold
            1600 to 2560, 2560 to 1600, // Y700 tablet
        )
        for ((w,h) in canvases) for (top in listOf(true,false)) {
            val g=WorkspaceGeometry.forDisplay(w,h,density=2f).copy(railAtTop=top)
            assertTrue(StageSwapViewportHoldPolicy.retain(true,g,g))
            // Temporary counted-slot changes may resize available main area,
            // but must not kill the real task exchange's visible card flight.
            val resized=g.copy(sidebarWidth=g.sidebarWidth+8)
            assertTrue(StageSwapViewportHoldPolicy.retain(true,g,resized))
            assertFalse(StageSwapViewportHoldPolicy.retain(false,g,g))
            assertFalse(StageSwapViewportHoldPolicy.retain(true,null,g))
        }
    }

    @Test fun foldRotateInsetOrRailModeChangeStillAbortsUnsafeVisualHold() {
        val g=WorkspaceGeometry.forDisplay(1140,2616,density=3f)
        val changed=listOf(
            g.copy(screenWidth=2248),
            g.copy(screenHeight=2480),
            g.copy(displayRotation=(g.displayRotation+1)%4),
            g.copy(sidebarSide=SidebarSide.RIGHT),
            g.copy(railAtTop=!g.railAtTop),
            g.copy(navigationInsets=WorkspaceNavigationInsets(top=75,bottom=35)),
        )
        for (next in changed) assertFalse(StageSwapViewportHoldPolicy.retain(true,g,next))
    }
}

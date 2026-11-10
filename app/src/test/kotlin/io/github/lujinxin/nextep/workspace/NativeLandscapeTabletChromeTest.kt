package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLandscapeTabletChromeTest {
    @Test fun nativeTabletToolbarFitsItsPhysicalControlBandAndLeavesSeparateHitTargets() {
        val g = WorkspaceGeometry.forDisplay(2560, 1600, SidebarSide.LEFT, density = 2f,
            navigationInsets = WorkspaceNavigationInsets(top = 48, bottom = 40), displayRotation = 0)
        assertTrue(g.isNativeLandscapeRail)
        val controlDp = g.controlHeight / 2f
        val top = NativeLandscapeTabletChrome.toolbarTopDp(true)
        assertTrue(top + 52 <= controlDp)
        assertEquals(64, NativeLandscapeTabletChrome.toolbarLeftDp(true))
        assertEquals(124, NativeLandscapeTabletChrome.toolbarRightDp(true))
        // Main 48dp handle occupies [8, 56], header begins at x=64.
        assertTrue(8 + 48 <= NativeLandscapeTabletChrome.toolbarLeftDp(true))
        // The right toolbar end is left of both quick-bar and side-rail handles.
        assertTrue(NativeLandscapeTabletChrome.toolbarRightDp(true) >= 64 + 48)
        assertEquals(0, NativeLandscapeTabletChrome.localStatusExclusionDp(true))
        assertTrue(g.controlTop >= g.navigationInsets.top)
    }

    @Test fun existingPhoneAndFoldControlOffsetsRemainByteForByteEquivalentInPolicy() {
        val phones = listOf(
            WorkspaceGeometry.forDisplay(1140, 2616, density = 3f, displayRotation = 0),
            WorkspaceGeometry.forDisplay(1140, 2616, density = 3f, displayRotation = 0).rotatedTo(1),
            WorkspaceGeometry.forDisplay(2248, 2480, density = 420f / 160, displayRotation = 0),
            WorkspaceGeometry.forDisplay(2248, 2480, density = 420f / 160, displayRotation = 0).rotatedTo(1),
        )
        phones.forEach { assertFalse(it.isNativeLandscapeRail) }
        assertEquals(40, NativeLandscapeTabletChrome.toolbarTopDp(false))
        assertEquals(8, NativeLandscapeTabletChrome.toolbarLeftDp(false))
        assertEquals(8, NativeLandscapeTabletChrome.toolbarRightDp(false))
        assertEquals(WorkspaceControlMetrics.STATUS_HEIGHT_DP,
            NativeLandscapeTabletChrome.localStatusExclusionDp(false))
    }
}

package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y700 TB324ZC (2560x1600) uses a ColorOS 16 phone-ROM port.
 * Verify physical geometry, never spoofable vendor names or rotation parity.
 * LEFT/RIGHT are genuine vertical side rails; TOP is an independent choice.
 */
class NativeLandscapeTabletRegressionTest {
    private fun tablet(side: SidebarSide = SidebarSide.LEFT, rotation: Int = 0): WorkspaceGeometry =
        WorkspaceGeometry.forDisplay(
            screenWidth = 2560, screenHeight = 1600, sidebarSide = side, density = 2f,
            navigationInsets = WorkspaceNavigationInsets(top = 48, bottom = 40),
            displayRotation = rotation,
        )

    @Test fun wideTabletLeftAndRightRemainPhysicalVerticalRails() {
        for (side in SidebarSide.entries) {
            val g = tablet(side)
            assertTrue(g.isNativeLandscapeRail)
            assertEquals(0f, g.panelRotationDegrees, 0f)
            assertFalse(g.isSidebarAtTop)
            assertEquals(g.availableWidth, g.controlWidth)
            assertEquals(g.topHeight, g.controlHeight)
            assertEquals(g.availableLeft, g.controlLeft)
            assertEquals(g.availableTop, g.controlTop)
            assertEquals(g.sidebarWidth, g.sidebarPhysicalWidth)
            assertEquals(g.availableHeight - g.topHeight, g.sidebarPhysicalHeight)
            assertEquals(g.sidebarPhysicalHeight, g.sidebarLogicalHeight)
            assertEquals(g.availableTop + g.topHeight, g.sidebarTop)
            assertEquals(if (side == SidebarSide.LEFT) g.availableLeft
                else g.availableRight - g.sidebarWidth, g.sidebarLeft)
            assertTrue(g.contentTop >= g.controlTop + g.controlHeight)
            assertTrue(g.contentBottom <= g.availableBottom)
            if (side == SidebarSide.LEFT) {
                assertTrue(g.contentLeft >= g.sidebarLeft + g.sidebarPhysicalWidth)
            } else {
                assertTrue(g.contentRight <= g.sidebarLeft)
            }
            assertEquals(2560f / 1600f, g.contentWidth.toFloat() / g.contentHeight, .003f)
        }
    }

    @Test fun verticalCardStackStaysInsideLeftAndRightRailsForOneToFourApps() {
        for (side in SidebarSide.entries) for (count in 1..4) {
            val g = tablet(side)
            val profile = WorkspaceLayoutProfile(g, densityDpi = 320, gapPixels = 2, activeCount = count)
            val occupied = List(4) { it < count }
            val bounds = StageLiveCardLayout.bounds(profile,
                List(4) { profile.canvas }, occupied, listOf(0, 1, 2, 3))
            val cards = bounds.take(count)
            assertEquals(count, cards.size)
            for (card in cards) {
                assertTrue(card.width > 0 && card.height > 0)
                assertTrue(card.top >= 0)
                assertTrue(card.width <= g.sidebarPhysicalWidth)
                assertTrue(card.top + card.height <= g.sidebarPhysicalHeight)
                assertEquals(profile.canvas.width.toFloat() / profile.canvas.height,
                    card.width.toFloat() / card.height, .01f)
            }
            assertTrue(cards.zipWithNext().all { (a, b) -> a.top + a.height <= b.top })
        }
    }

    @Test fun independentTopChoiceUsesHorizontalCardsAndPreservesMainHeight() {
        val plain = tablet()
        val depth = StageTopLayoutMetrics.depth(plain, 2f, 4)
        assertTrue(depth < plain.availableHeight * .33f)
        val top = plain.copy(railAtTop = true, sidebarWidth = depth)
        assertFalse(top.isNativeLandscapeRail)
        assertEquals(0f, top.panelRotationDegrees, 0f)
        assertEquals(top.availableWidth, top.sidebarPhysicalWidth)
        assertEquals(top.availableTop + top.topHeight, top.sidebarTop)
        assertTrue(top.contentTop >= top.sidebarTop + top.sidebarPhysicalHeight)
        assertTrue(top.contentHeight >= (plain.availableHeight * .5f).toInt())
        val profile = WorkspaceLayoutProfile(top, densityDpi = 320, gapPixels = 2, activeCount = 4)
        val slots = StageLiveCardLayout.bounds(profile, List(4) { profile.canvas },
            List(4) { true }, listOf(0, 1, 2, 3))
        assertTrue(slots.zipWithNext().all { (a, b) -> a.left + a.width <= b.left })
        assertTrue(slots.all {
            it.left >= 0 && it.left + it.width <= top.sidebarPhysicalWidth &&
                it.top >= 0 && it.top + it.height <= top.sidebarPhysicalHeight
        })
    }

    @Test fun nativeSideRailIsIndependentOfPortedRomRotationNumber() {
        for (rotation in 0..3) for (side in SidebarSide.entries) {
            val g = tablet(side, rotation)
            assertTrue("rotation=$rotation", g.isNativeLandscapeRail)
            assertFalse(g.isSidebarAtTop)
            assertEquals(0f, g.panelRotationDegrees, 0f)
            assertEquals(g.availableWidth, g.controlWidth)
            assertEquals(g.sidebarWidth, g.sidebarPhysicalWidth)
            assertEquals(if (side == SidebarSide.LEFT) g.availableLeft else g.availableRight - g.sidebarWidth,
                g.sidebarLeft)
        }
    }

    @Test fun tabletPortraitKeepsOriginalLayoutAndCanAvoidSpoofedStatusInset() {
        val g = WorkspaceGeometry.forDisplay(1600, 2560, density = 2f, displayRotation = 1)
        assertFalse(g.isNativeLandscapeRail)
        assertEquals(0f, g.panelRotationDegrees, 0f)
        assertEquals(96, StageDisplayPolicy.safeTabletTopInset(1600, 2560, 2f, 0))
        assertEquals(118, StageDisplayPolicy.safeTabletTopInset(1600, 2560, 2f, 118))
        assertEquals(0, StageDisplayPolicy.safeTabletTopInset(1140, 2616, 3f, 0))
        assertEquals(0, StageDisplayPolicy.safeTabletTopInset(2248, 2480, 2.6f, 0))
    }

    @Test fun ace7ProAndFindN5KeepEstablishedPhoneAndFoldGeometry() {
        val acePortrait = WorkspaceGeometry.forDisplay(1140, 2616, density = 3f, displayRotation = 0)
        val aceLandscape = acePortrait.rotatedTo(1)
        val findExpanded = WorkspaceGeometry.forDisplay(2248, 2480,
            density = 420f / 160, displayRotation = 0)
        val findLandscape = findExpanded.rotatedTo(1)
        for (g in listOf(acePortrait, aceLandscape, findExpanded, findLandscape))
            assertFalse(g.isNativeLandscapeRail)
        assertEquals(0f, acePortrait.panelRotationDegrees, 0f)
        assertEquals(-90f, aceLandscape.panelRotationDegrees, 0f)
        assertEquals(0f, findExpanded.panelRotationDegrees, 0f)
        assertEquals(-90f, findLandscape.panelRotationDegrees, 0f)
        assertEquals(aceLandscape.topHeight, aceLandscape.controlWidth)
        assertEquals(findLandscape.topHeight, findLandscape.controlWidth)
    }
}

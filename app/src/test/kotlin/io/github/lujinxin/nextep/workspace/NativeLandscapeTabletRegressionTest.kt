package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout tests for naturally landscape tablets, without inspecting spoofable ROM/model names. */
class NativeLandscapeTabletRegressionTest {
    private fun tablet(side: SidebarSide = SidebarSide.LEFT): WorkspaceGeometry =
        WorkspaceGeometry.forDisplay(
            screenWidth = 2560, screenHeight = 1600, sidebarSide = side, density = 2f,
            navigationInsets = WorkspaceNavigationInsets(top = 48, bottom = 40),
            displayRotation = 0,
        )

    @Test fun y700LandscapeUsesUnrotatedHorizontalControlsAndNonOverlappingMain() {
        for (side in SidebarSide.entries) {
            val g = tablet(side)
            assertTrue(g.isNativeLandscapeRail)
            assertEquals(0f, g.panelRotationDegrees, 0f)
            assertEquals(g.availableWidth, g.controlWidth)
            assertEquals(g.availableWidth, g.sidebarPhysicalWidth)
            assertEquals(g.topHeight, g.controlHeight)
            assertEquals(g.availableLeft, g.controlLeft)
            assertEquals(g.availableTop, g.controlTop)
            assertTrue(g.controlTop >= g.navigationInsets.top)
            assertEquals(side == SidebarSide.LEFT, g.isSidebarAtTop)
            if (g.isSidebarAtTop) {
                assertEquals(g.controlTop + g.controlHeight, g.sidebarTop)
                assertTrue(g.contentTop >= g.sidebarTop + g.sidebarPhysicalHeight)
            } else {
                assertEquals(g.availableBottom, g.sidebarTop + g.sidebarPhysicalHeight)
                assertTrue(g.contentBottom <= g.sidebarTop)
            }
            assertTrue(g.contentLeft >= g.availableLeft)
            assertTrue(g.contentRight <= g.availableRight)
            assertTrue(g.contentTop >= g.controlTop + g.controlHeight)
            assertTrue(g.contentBottom <= g.availableBottom)
            assertEquals(2560f / 1600f, g.contentWidth.toFloat() / g.contentHeight, 0.003f)
        }
    }

    @Test fun liveCardsStayUprightAndInsideBothPhysicalRowsAtEveryOccupancy() {
        for (side in SidebarSide.entries) for (count in 1..4) {
            val g = tablet(side)
            val profile = WorkspaceLayoutProfile(g, densityDpi = 320, gapPixels = 2, activeCount = count)
            val occupied = List(4) { it < count }
            val bounds = StageLiveCardLayout.bounds(
                profile, List(4) { profile.canvas }, occupied, listOf(0, 1, 2, 3),
            )
            val cards = bounds.take(count)
            assertEquals(count, cards.size)
            for (card in cards) {
                assertTrue(card.width > 0 && card.height > 0)
                assertTrue(card.left >= 0 && card.top >= 0)
                assertTrue(card.left + card.width <= g.sidebarPhysicalWidth)
                assertTrue(card.top + card.height <= g.sidebarPhysicalHeight)
                assertEquals(profile.canvas.width.toFloat() / profile.canvas.height,
                    card.width.toFloat() / card.height, 0.01f)
            }
            assertTrue(cards.zipWithNext().all { (a, b) -> a.left + a.width < b.left })
        }
    }

    @Test fun nativeSmartRailReopenButtonStaysInsideTheTopOrBottomRail() {
        for (side in SidebarSide.entries) for (rotation in 0..3) {
            val g = WorkspaceGeometry.forDisplay(2560, 1600, side,
                density = 2.5f, navigationInsets = WorkspaceNavigationInsets(top = 64, bottom = 42),
                displayRotation = rotation)
            val hit = NativeLandscapeHandlePlacement.hidden(g, 2.5f)
            assertTrue(hit.left >= g.sidebarLeft)
            assertTrue(hit.right <= g.sidebarLeft + g.sidebarPhysicalWidth)
            assertTrue(hit.top >= g.sidebarTop)
            assertTrue(hit.bottom <= g.sidebarTop + g.sidebarPhysicalHeight)
            // No virtual status/navigation bar overlap, independent of ROM rotation.
            assertTrue(hit.top >= g.availableTop)
            assertTrue(hit.bottom <= g.availableBottom)
        }
    }

    @Test fun portedPhoneRomMayReportTabletLandscapeAsRotationOneOrThree() {
        for (rotation in 0..3) for (side in SidebarSide.entries) {
            val g = WorkspaceGeometry.forDisplay(
                2560, 1600, side, density = 3.25f,
                navigationInsets = WorkspaceNavigationInsets(top = 74, bottom = 48),
                displayRotation = rotation)
            assertTrue("rotation=$rotation side=$side", g.isNativeLandscapeRail)
            assertEquals(0f, g.panelRotationDegrees, 0f)
            assertEquals(side == SidebarSide.LEFT, g.isSidebarAtTop)
            assertEquals(g.availableWidth, g.controlWidth)
            assertEquals(g.availableWidth, g.sidebarPhysicalWidth)
            assertTrue(g.contentTop >= g.controlTop + g.controlHeight)
            if (side == SidebarSide.LEFT)
                assertTrue(g.contentTop >= g.sidebarTop + g.sidebarPhysicalHeight)
            else assertTrue(g.contentBottom <= g.sidebarTop)
            assertTrue(g.contentRight <= g.availableRight)
            assertTrue(g.contentBottom <= g.availableBottom)
        }
    }

    @Test fun portraitAndOppositeTabletRotationDoNotReuseWrongAxes() {
        val original = tablet()
        val reverse = original.rotatedTo(2)
        assertTrue(reverse.isNativeLandscapeRail)
        assertEquals(0f, reverse.panelRotationDegrees, 0f)
        assertEquals(original.topHeight, reverse.topHeight)
        assertEquals(original.sidebarWidth, reverse.sidebarWidth)
        val portrait = original.rotatedTo(1)
        assertFalse(portrait.isNativeLandscapeRail)
        assertEquals(0f, portrait.panelRotationDegrees, 0f)
        assertEquals(1600, portrait.screenWidth)
        assertEquals(2560, portrait.screenHeight)
        assertEquals(original, portrait.rotatedTo(0))
    }

    @Test fun ace7ProAndFindN5KeepTheirEstablishedGeometry() {
        val acePortrait = WorkspaceGeometry.forDisplay(1140, 2616,
            density = 3f, displayRotation = 0)
        val aceLandscape = acePortrait.rotatedTo(1)
        val findExpanded = WorkspaceGeometry.forDisplay(2248, 2480,
            density = 420f / 160, displayRotation = 0)
        val findLandscape = findExpanded.rotatedTo(1)
        for (g in listOf(acePortrait, aceLandscape, findExpanded, findLandscape)) {
            assertFalse(g.isNativeLandscapeRail)
        }
        assertEquals(0f, acePortrait.panelRotationDegrees, 0f)
        assertEquals(-90f, aceLandscape.panelRotationDegrees, 0f)
        assertEquals(0f, findExpanded.panelRotationDegrees, 0f)
        assertEquals(-90f, findLandscape.panelRotationDegrees, 0f)
        assertEquals(aceLandscape.topHeight, aceLandscape.controlWidth)
        assertEquals(findLandscape.topHeight, findLandscape.controlWidth)
    }
}

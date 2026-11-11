package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.display.SlotGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physical display cases, including a ported phone ROM reporting rotation 1/3. */
class StageY700ScreenFitWallpaperTest {
    private fun y700Land(side: SidebarSide = SidebarSide.LEFT, rotation: Int = 1) =
        WorkspaceGeometry.forDisplay(2560, 1600, side, density = 2f,
            navigationInsets = WorkspaceNavigationInsets(top = 48, bottom = 40),
            displayRotation = rotation)
    private fun y700Port(count: Int) =
        WorkspaceLayoutProfile(
            WorkspaceGeometry.forDisplay(1600, 2560, SidebarSide.LEFT, density = 2f,
                navigationInsets = WorkspaceNavigationInsets(top = 48, bottom = 40)),
            densityDpi = 320, gapPixels = 2, activeCount = count)

    @Test fun newY700SideProducerUsesPhysicalLandscapeAndRetainsOldLiveProducers() {
        for (rotation in 0..3) for (side in SidebarSide.entries) {
            val g = y700Land(side, rotation)
            val profile = WorkspaceLayoutProfile(g, 320, 2)
            assertTrue(g.isNativeLandscapeRail)
            assertEquals(SlotGeometry(2560, 1512, 320),
                StageTabletCanvasPolicy.proposed(profile, null))
            val retainedPortrait = SlotGeometry(1600, 2560, 320)
            assertEquals(retainedPortrait,
                StageTabletCanvasPolicy.proposed(profile, retainedPortrait))
            val retainedOtherDensity = SlotGeometry(720, 1120, 360)
            assertEquals(retainedOtherDensity,
                StageTabletCanvasPolicy.proposed(profile, retainedOtherDensity))
        }
    }

    @Test fun portraitTopAndAllPhoneFoldCanvasStrategiesDoNotChange() {
        val portrait = y700Port(2)
        assertEquals(portrait.canvas, StageTabletCanvasPolicy.proposed(portrait, null))
        val top = y700Land().copy(railAtTop = true, sidebarWidth = 320)
        val topProfile = WorkspaceLayoutProfile(top,320,2)
        assertEquals(topProfile.canvas, StageTabletCanvasPolicy.proposed(topProfile,null))
        for (g in listOf(
            WorkspaceGeometry.forDisplay(1140, 2616, density = 3f),
            WorkspaceGeometry.forDisplay(2248, 2480, density = 2.6f),
            WorkspaceGeometry.forDisplay(2616, 1140, density = 3f, displayRotation = 1)
        )) {
            val p = WorkspaceLayoutProfile(g, 416, 2)
            assertEquals(p.canvas, StageTabletCanvasPolicy.proposed(p, null))
        }
    }

    @Test fun leftRightNativeLandscapeSidePanelsUseRealAspectAndDoNotOverlap() {
        val landscape = SlotGeometry(2560, 1512, 320)
        val portrait = SlotGeometry(1600, 2560, 320)
        for (rotation in 0..3) for (side in SidebarSide.entries) for (count in 1..4) {
            val g = y700Land(side, rotation)
            val p = WorkspaceLayoutProfile(g, 320, 2, count)
            val canvases = listOf(landscape, portrait, landscape, portrait)
            val boxes = StageLiveCardLayout.bounds(
                p, canvases, List(4) { it < count }, listOf(0,1,2,3)).take(count)
            assertEquals(count, boxes.size)
            for (i in boxes.indices) {
                val box = boxes[i]
                val canvas = canvases[i]
                assertTrue(box.width in 1..g.sidebarPhysicalWidth)
                assertTrue(box.height in 1..g.sidebarPhysicalHeight)
                assertTrue(box.top >= 0 && box.top + box.height <= g.sidebarPhysicalHeight)
                assertEquals(canvas.width.toFloat() / canvas.height,
                    box.width.toFloat() / box.height, .015f)
            }
            assertTrue(boxes.zipWithNext().all { (a,b) -> a.top + a.height <= b.top })
        }
    }

    @Test fun portraitTabletTwoCardsUseLessSideWidthThanOldTabletLayout() {
        val g2 = y700Port(2).geometry
        val narrow = StageAdaptiveLayout.rail(g2, 2, 2f)
        val four = StageAdaptiveLayout.rail(y700Port(4).geometry, 4, 2f)
        val one = StageAdaptiveLayout.rail(y700Port(1).geometry, 1, 2f)
        assertEquals((g2.availableWidth * .18f).toInt(), narrow.sidebarWidth)
        assertEquals((g2.availableWidth * .22f).toInt(), four.sidebarWidth)
        assertTrue(one.sidebarWidth < narrow.sidebarWidth)
        assertTrue(narrow.sidebarWidth < four.sidebarWidth)
        assertTrue(narrow.contentWidth > four.contentWidth)
        val phone = WorkspaceGeometry.forDisplay(1140,2616,density=3f)
        val phoneTwo = StageAdaptiveLayout.rail(phone,2,3f)
        assertEquals((phone.availableWidth*.235f).toInt(),phoneTwo.sidebarWidth)
    }

    @Test fun wallpaperTopAndSideSampleSamePhysicalAxesOnY700() {
        for (side in SidebarSide.entries) for (rotation in 0..3) {
            val g = y700Land(side,rotation)
            val panel = StageTabletWallpaperPolicy.panel(g)
            assertEquals(g, panel)
            assertEquals(0f, panel.panelRotationDegrees, 0f)
            assertTrue(panel.controlLeft >= 0 && panel.controlTop >= 0)
            assertTrue(panel.sidebarLeft >= 0 && panel.sidebarTop >= panel.controlTop + panel.controlHeight)
            assertTrue(panel.sidebarLeft + panel.sidebarPhysicalWidth <= panel.screenWidth)
            assertTrue(panel.sidebarTop + panel.sidebarPhysicalHeight <= panel.screenHeight)
            assertTrue(StageTabletWallpaperPolicy.frameMatches(g,2560,1600,rotation))
            assertFalse(StageTabletWallpaperPolicy.frameMatches(g,1600,2560,rotation))
            assertFalse(StageTabletWallpaperPolicy.frameMatches(g,2560,1600,(rotation+1)%4))
        }
        val portrait = y700Port(2).geometry.copy(displayRotation=1)
        assertEquals(portrait,StageTabletWallpaperPolicy.panel(portrait))
        assertTrue(StageTabletWallpaperPolicy.frameMatches(portrait,1600,2560,1))
        assertFalse(StageTabletWallpaperPolicy.frameMatches(portrait,2560,1600,1))
        for (g in listOf(
            WorkspaceGeometry.forDisplay(1140,2616,density=3f),
            WorkspaceGeometry.forDisplay(2248,2480,density=2.6f)
        )) {
            assertEquals(g.rotatedTo(0),StageTabletWallpaperPolicy.panel(g))
            assertTrue(StageTabletWallpaperPolicy.frameMatches(g,1,1,3))
        }
    }
}

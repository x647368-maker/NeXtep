package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.display.SlotGeometry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class StageSparseSideCardPolicyTest {
    private fun phone(count: Int, side: SidebarSide = SidebarSide.LEFT): WorkspaceLayoutProfile {
        val native = WorkspaceGeometry.forDisplay(
            1140, 2616, side, density = 3f,
            navigationInsets = WorkspaceNavigationInsets(top = 80, bottom = 40),
        )
        return WorkspaceLayoutProfile(
            StageAdaptiveLayout.rail(native, count, 3f),
            densityDpi = 480, gapPixels = 3, activeCount = count,
        )
    }

    @Test fun oneTwoThreeSideCardsGrowButMainRectangleAndControlsStayFixed() {
        for (side in SidebarSide.entries) for (count in 1..3) {
            val profile = phone(count, side)
            val g = profile.geometry
            val occupied = List(4) { it < count }
            val initial = listOf(g.contentLeft, g.contentTop, g.contentWidth,
                g.contentHeight, g.sidebarWidth, g.topHeight, g.sidebarLeft, g.sidebarTop)
            val bounds = StageLiveCardLayout.bounds(
                profile, List(4) { profile.canvas }, occupied, (0..3).toList())
            val shown = bounds.take(count)
            assertTrue(StageSparseSideCardPolicy.enabled(g, count))
            val oldMaxWidth = g.sidebarWidth - 2 * (8 * profile.densityDpi / 160f).roundToInt()
            assertTrue("Sparse cards must use recovered rail space", shown.all { it.width > oldMaxWidth })
            val footer = (56 * profile.densityDpi / 160f).roundToInt()
            val gap = (8 * profile.densityDpi / 160f).roundToInt()
            assertTrue(shown.first().top >= gap)
            assertTrue(shown.last().top + shown.last().height <= g.sidebarLogicalHeight - footer)
            assertTrue(shown.zipWithNext().all { (a, b) -> a.top + a.height + gap <= b.top })
            assertTrue(shown.all { it.width <= g.sidebarPhysicalWidth && it.height > 0 })
            assertEquals(initial, listOf(g.contentLeft, g.contentTop, g.contentWidth,
                g.contentHeight, g.sidebarWidth, g.topHeight, g.sidebarLeft, g.sidebarTop))
            // The phone's '+' button and menu remain their existing controller
            // anchors: StageTabletRailVisualPolicy.addTop is not used here.
            assertNull(StageTabletRailVisualPolicy.addTop(g, bounds, occupied, 132, 24))
        }
    }

    @Test fun liveTaskAspectRatioNeverChangesAndInputBoxRemainsInRail() {
        val sources = listOf(
            SlotGeometry(720, 1280, 320),
            SlotGeometry(2560, 1440, 320),
            SlotGeometry(1080, 2400, 320),
            SlotGeometry(1600, 2560, 320),
        )
        for (count in 1..3) {
            val p = phone(count)
            val occupied = List(4) { it < count }
            val bounds = StageLiveCardLayout.bounds(p, sources, occupied, listOf(2, 0, 3, 1))
            val presented = listOf(2, 0, 3, 1).filter { occupied[it] }
            presented.forEach { index ->
                val card = bounds[index]
                val source = sources[index]
                assertEquals(
                    source.width.toFloat() / source.height,
                    card.width.toFloat() / card.height, 0.024f,
                )
                assertTrue(card.width in 1..p.geometry.sidebarPhysicalWidth)
                assertTrue(card.top >= 0 &&
                    card.top + card.height <= p.geometry.sidebarPhysicalHeight)
            }
            assertTrue(presented.zipWithNext().all { (a, b) ->
                bounds[a].top + bounds[a].height < bounds[b].top
            })
        }
    }

    @Test fun fourCardsTopRailLandscapeAndN5StayOnExistingRules() {
        val four = phone(4)
        assertFalse(StageSparseSideCardPolicy.enabled(four.geometry, 4))
        assertNull(StageSparseSideCardPolicy.fit(
            four, List(4) { four.canvas }, List(4) { true }, (0..3).toList()))
        val phoneTop = four.copy(geometry = four.geometry.copy(
            railAtTop = true, sidebarWidth = 180))
        val landscape = WorkspaceGeometry.forDisplay(2616, 1140,
            density = 3f, displayRotation = 1)
        val n5 = WorkspaceGeometry.forDisplay(2248, 2480, density = 2.6f)
        val y700 = WorkspaceGeometry.forDisplay(1600, 2560, density = 2f)
        for (g in listOf(phoneTop.geometry, landscape, n5, y700)) {
            assertFalse(StageSparseSideCardPolicy.enabled(g, 2))
        }
    }

    @Test fun tabletPortraitCardsMayGrowButFourCardAndAddButtonRemainSafe() {
        val native = WorkspaceGeometry.forDisplay(1600, 2560,
            SidebarSide.LEFT, density = 2f,
            navigationInsets = WorkspaceNavigationInsets(top = 96, bottom = 40))
        for (count in 1..4) {
            val g = StageAdaptiveLayout.rail(native, count, 2f)
            val p = WorkspaceLayoutProfile(g, 320, 2, activeCount = count)
            val occupied = List(4) { it < count }
            val bounds = StageLiveCardLayout.bounds(
                p, List(4) { p.canvas }, occupied, (0..3).toList())
            val shown = bounds.take(count)
            val horizontalInset = (if (count <= 2) 4 else if (count == 3) 5 else 8) * 2
            assertTrue(shown.all { it.width <= g.sidebarPhysicalWidth - 2 * horizontalInset })
            if (count < 4) {
                val addTop = StageTabletRailVisualPolicy.addTop(g, bounds, occupied, 88, 16)
                assertNotNull(addTop)
                assertTrue(addTop!! >= shown.last().top + shown.last().height)
                assertTrue(addTop + 88 <= g.sidebarPhysicalHeight)
            } else {
                assertNull(StageTabletRailVisualPolicy.addTop(g, bounds, occupied, 88, 16))
            }
            assertTrue(shown.zipWithNext().all { (a, b) -> a.top + a.height < b.top })
        }
    }
}

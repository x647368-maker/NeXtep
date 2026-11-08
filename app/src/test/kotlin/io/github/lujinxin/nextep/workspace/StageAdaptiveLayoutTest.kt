package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.systemui.StageActiveWindowPolicy
import io.github.x647368maker.atomicworkbench.systemui.StageTopRowPolicy
import org.junit.Assert.*
import org.junit.Test

class StageAdaptiveLayoutTest {
    private val base = WorkspaceLayoutProfile.create(1264, 2780, 480, WorkspaceNavigationInsets(top = 96, bottom = 72))
    @Test fun zeroToFourSideAndTopFitActualOccupiedCards() {
        for (count in 0..4) for (top in listOf(false, true)) for (side in SidebarSide.entries) {
            val g = StageAdaptiveLayout.rail(base.geometry.copy(railAtTop = top, sidebarSide = side), count, 3f)
            val profile = base.copy(geometry = g, activeCount = count)
            val occupied = List(4) { it < count }
            val b = profile.slots
            val poses = if (top) StageTopRowPolicy.positions(occupied, b.map { it.width }, b.map { it.height }, b.map { it.left }, b.map { it.top }, g.sidebarPhysicalWidth, g.sidebarPhysicalHeight, 3f)
                else StageActiveWindowPolicy.positions(occupied, b.map { it.height }, b.map { it.top }, g.sidebarLogicalHeight, listOf(0, 1, 2, 3), 3f, side == SidebarSide.RIGHT)
            assertEquals(count, poses.size)
            for ((index, pose) in poses) {
                val box = b[index]
                val y = box.top + pose.y + box.height * (1 - pose.scale) / 2
                assertTrue("$count/$top card $index at $y", y >= 0)
                assertTrue(y + box.height * pose.scale <= g.sidebarLogicalHeight + 1)
            }
            assertTrue(g.contentLeft >= g.availableLeft)
            assertTrue(g.contentBottom <= g.availableBottom)
        }
    }
    @Test fun sparseSideUsesLessSpaceThanFourAndKeepsCanvasStable() {
        val one = base.copy(geometry = StageAdaptiveLayout.rail(base.geometry, 1, 3f), activeCount = 1)
        val four = base.copy(geometry = StageAdaptiveLayout.rail(base.geometry, 4, 3f), activeCount = 4)
        assertTrue(one.geometry.sidebarWidth < four.geometry.sidebarWidth)
        assertTrue(one.geometry.contentWidth > four.geometry.contentWidth)
        assertEquals(one.canvas, four.canvas)
    }
    @Test fun closingMiddleCardRemovesGapAndKeepsSlotIdentity() {
        val b = base.copy(activeCount = 2).slots
        val poses = StageActiveWindowPolicy.positions(listOf(true, false, false, true), b.map { it.height }, b.map { it.top }, base.geometry.sidebarLogicalHeight, listOf(0, 1, 2, 3), 3f, false)
        assertEquals(setOf(0, 3), poses.keys)
        val a = b[0].top + poses.getValue(0).y + b[0].height * (1 - poses.getValue(0).scale) / 2
        val end = a + b[0].height * poses.getValue(0).scale
        val next = b[3].top + poses.getValue(3).y + b[3].height * (1 - poses.getValue(3).scale) / 2
        assertEquals(24f, next - end, 1f)
    }
    @Test fun adaptiveProfilesRoundTripEveryCountAndManualSide() {
        for (count in 0..4) for (top in listOf(false, true)) {
            val p = base.copy(geometry = StageAdaptiveLayout.rail(base.geometry.copy(railAtTop = top), count, 3f), activeCount = count)
            assertEquals(p, WorkspaceLayoutProfile.decode(p.encode()))
            assertEquals(p.slots, WorkspaceLayoutProfile.decode(p.encode())?.slots)
        }
    }
    @Test fun cinemaDepthFitsPortraitAndRetainsNavigationSafeLowerMain() {
        val before = base.geometry
        val media = before.copy(railAtTop = true, sidebarWidth = StageAdaptiveLayout.cinemaDepth(before, 3f))
        assertTrue(media.contentTop >= media.sidebarTop + media.sidebarPhysicalHeight)
        assertTrue(media.contentBottom <= media.availableBottom)
        assertTrue(media.contentWidth > 0)
        // Leaving uses the unmodified manual profile, never persists the temporary media mode.
        assertFalse(before.railAtTop)
        assertEquals(base.geometry, before)
    }
    @Test fun phoneRotationFallsBackWithoutAlteringManualSelection() {
        val landscape = base.geometry.rotatedTo(1)
        assertEquals(landscape, StageAdaptiveLayout.rail(landscape, 1, 3f))
    }
}

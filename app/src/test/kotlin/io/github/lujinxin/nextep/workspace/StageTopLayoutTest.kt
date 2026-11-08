package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test

class StageTopLayoutTest {
    private fun profile(rotation: Int): WorkspaceLayoutProfile {
        val base = WorkspaceLayoutProfile.create(1264, 2780, 512, WorkspaceNavigationInsets(bottom = 100)).rotatedTo(rotation)
        val depth = StageTopLayoutMetrics.depth(base.geometry, 512 / 160f)
        return base.copy(geometry = base.geometry.copy(railAtTop = true, sidebarWidth = depth))
    }
    @Test fun topRailAndMainStayInsideSafeDisplayInEveryRotation() {
        for (rotation in 0..3) {
            val p = profile(rotation); val g = p.geometry
            assertEquals(0f, g.panelRotationDegrees, 0f)
            assertEquals(g.availableWidth, g.sidebarPhysicalWidth)
            assertEquals(g.availableTop + g.topHeight, g.sidebarTop)
            assertTrue(g.contentTop >= g.sidebarTop + g.sidebarPhysicalHeight)
            assertTrue(g.contentBottom <= g.availableBottom)
            assertTrue(g.contentLeft >= g.availableLeft && g.contentRight <= g.availableRight)
            val focused = g.copy(sidebarWidth = 1)
            assertTrue(focused.contentScale > g.contentScale)
        }
    }
    @Test fun fourTopPreviewsFitWholeBuffersWithoutOverlappingOrCropping() {
        for (rotation in 0..3) {
            val p = profile(rotation)
            for (i in 0..3) {
                val slot = p.slots[i]; val bodyHeight = slot.height
                val fit = p.previewTransform(slot.width, bodyHeight)
                assertTrue(slot.left >= 0 && slot.left + slot.width <= p.geometry.sidebarPhysicalWidth)
                assertTrue(slot.top >= 0 && slot.top + slot.height <= p.geometry.sidebarPhysicalHeight)
                if (i > 0) assertTrue(slot.left >= p.slots[i - 1].left + p.slots[i - 1].width)
                assertEquals(slot.width * fit.scaleX / p.canvas.width, bodyHeight * fit.scaleY / p.canvas.height, .000001f)
            }
        }
    }
    @Test fun topModeProtocolRoundTripsAndRejectsOldLayoutRecords() {
        for (rotation in 0..3) {
            val p = profile(rotation)
            val restored = checkNotNull(WorkspaceLayoutProfile.decode(p.encode()))
            assertEquals(p, restored); assertEquals(p.slots, restored.slots)
            assertNull(WorkspaceLayoutProfile.decode(p.encode().replaceFirst("15,", "14,")))
            assertEquals(p.geometry, p.geometry.rotatedTo((rotation + 1) % 4).rotatedTo(rotation))
        }
    }
    @Test fun compactTopAppsHaveTheirOwnReadableViewportAndWideColumns() {
        val p = profile(0)
        assertEquals(720, p.canvas.width); assertEquals(1120, p.canvas.height)
        assertEquals(320, p.canvas.densityDpi)
        assertTrue(p.slots.all { it.width >= p.geometry.availableWidth / 5 })
        assertEquals(360f, p.canvas.width * 160f / p.canvas.densityDpi, 0f)
    }
    @Test fun zeroThroughFourWindowsLeaveThreeControlsSeparatedAndOutsideAppBodies() {
        for (rotation in 0..3) for (count in 0..4) {
            val p = profile(rotation); val g = p.geometry
            val railHeight = if (count == 0) (56 * 3.2f).toInt() else g.sidebarPhysicalHeight
            val actions = StageTopLayoutMetrics.actions(g.sidebarPhysicalWidth, railHeight, 3.2f)
            val boxes = listOf(actions.collapse, actions.main, actions.add)
            boxes.forEach { assertTrue(it.left >= 0 && it.top >= 0 && it.right <= g.sidebarPhysicalWidth && it.bottom <= railHeight) }
            for (i in boxes.indices) for (j in i+1 until boxes.size) assertFalse(boxes[i].overlaps(boxes[j]))
            if (count > 0) assertTrue(p.slots.all { slot -> boxes.all { it.top >= slot.top + slot.height } })
        }
    }
}

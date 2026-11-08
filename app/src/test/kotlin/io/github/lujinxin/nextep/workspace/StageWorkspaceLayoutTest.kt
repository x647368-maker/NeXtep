package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test

class StageWorkspaceLayoutTest {
    @Test fun focusExpansionKeepsRealMainInsideSafeBoundsAcrossRotations() {
        for (side in SidebarSide.entries) for (rotation in 0..3) {
            val base = WorkspaceGeometry.forDisplay(1264, 2780, side, 3.2f,
                WorkspaceNavigationInsets(bottom = 120)).rotatedTo(rotation)
            val focus = base.copy(sidebarWidth = 1)
            assertTrue(focus.contentScale > base.contentScale)
            for (g in listOf(base, focus)) {
                assertTrue(g.contentLeft >= g.availableLeft)
                assertTrue(g.contentTop >= g.availableTop)
                assertTrue(g.contentRight <= g.availableRight)
                assertTrue(g.contentBottom <= g.availableBottom)
                assertEquals(g.availableWidth * g.contentScale,
                    g.contentWidth.toFloat(), .51f)
                assertEquals(g.availableHeight * g.contentScale,
                    g.contentHeight.toFloat(), .51f)
                assertEquals(g.contentLeft.toFloat(), g.availableLeft * g.contentScale + g.contentTranslationX, .51f)
                assertEquals(g.contentTop.toFloat(), g.availableTop * g.contentScale + g.contentTranslationY, .51f)
            }
        }
    }

    @Test fun compactToolbarDoesNotRecomputeVirtualCanvasWhenFocusingMain() {
        val profile = WorkspaceLayoutProfile.create(1264, 2780, 512, WorkspaceNavigationInsets())
        assertEquals(205, profile.geometry.topHeight)
        assertEquals(4, profile.slots.size)
        assertTrue(profile.slots.all { it.width > 0 && it.height > 0 })
        assertTrue(profile.slots.first().top > 0)
        assertEquals(1264, profile.canvas.width)
        assertEquals(2780, profile.canvas.height)
        assertTrue(profile.geometry.copy(sidebarWidth = 1).contentScale > profile.geometry.contentScale)
        assertEquals(profile, WorkspaceLayoutProfile.decode(profile.encode()))
        assertNull(WorkspaceLayoutProfile.decode(profile.encode().replaceFirst("15,", "14,")))
    }
}

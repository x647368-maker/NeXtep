package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceControlMetricsTest {
    @Test
    fun stageReservesOnlyStatusSpaceWithoutAControlStrip() {
        for (insets in listOf(WorkspaceNavigationInsets(), WorkspaceNavigationInsets(bottom = 132))) {
            val profile = WorkspaceLayoutProfile.create(1080, 2354, 480, insets)
            assertEquals(64 * 3, profile.geometry.topHeight)
            assertTrue(profile.geometry.topHeight < (40 + 48) * 3)
            assertTrue(profile.geometry.topHeight < 2354 / 6)
        }
    }

    @Test
    fun largerControlAreaKeepsThePreferredMediaHeight() {
        assertEquals(68 * 3, WorkspaceControlMetrics.mediaHeightPixels(624, 3f))
        assertEquals(68 * 2, WorkspaceControlMetrics.mediaHeightPixels(642, 2f))
    }
}

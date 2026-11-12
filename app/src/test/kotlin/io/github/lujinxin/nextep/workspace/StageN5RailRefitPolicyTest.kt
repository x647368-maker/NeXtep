package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageN5RailRefitPolicyTest {
    private fun canvas(width: Int, height: Int) =
        WorkspaceGeometry.forDisplay(width, height, SidebarSide.LEFT, density = 2.6f)

    @Test fun n5ExpandedRailAndCollapsedRailBothRequireOneRefit() {
        val full = StageAdaptiveLayout.rail(canvas(2248, 2480), 3, 2.6f)
        val collapsed = StageCollapsedRailPolicy.geometry(full, 2.6f)
        assertTrue(StageN5RailRefitPolicy.shouldReassert(full, collapsed))
        assertTrue(StageN5RailRefitPolicy.shouldReassert(collapsed, full))
        assertFalse(StageN5RailRefitPolicy.shouldReassert(full, full))
    }

    @Test fun onePlusAndY700NeverRequestN5Refit() {
        for ((width, height) in listOf(1140 to 2616, 2560 to 1600, 1600 to 2560)) {
            val full = StageAdaptiveLayout.rail(canvas(width, height), 3, 2.6f)
            val collapsed = StageCollapsedRailPolicy.geometry(full, 2.6f)
            assertFalse(StageN5RailRefitPolicy.shouldReassert(full, collapsed))
        }
    }

    @Test fun screenRotationAndTopRailDoNotUseThisRecovery() {
        val full = StageAdaptiveLayout.rail(canvas(2248, 2480), 3, 2.6f)
        val collapsed = StageCollapsedRailPolicy.geometry(full, 2.6f)
        assertFalse(StageN5RailRefitPolicy.shouldReassert(full, collapsed.rotatedTo(1)))
        assertFalse(StageN5RailRefitPolicy.shouldReassert(
            full.copy(railAtTop = true, sidebarWidth = 400),
            collapsed.copy(railAtTop = true, sidebarWidth = 200),
        ))
    }
}

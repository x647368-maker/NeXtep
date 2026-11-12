package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageWorkbenchEntryMotionPolicyTest {
    @Test fun allShapesEnterWithUniformSafeScaleAndNoModelGate() {
        for (top in listOf(false, true)) for (right in listOf(false, true)) {
            for (title in listOf("NeXtepContentPanel", "NeXtepSidebar", "NeXtepTopBar",
                "AtomicSidebarHandle", "StageMainHandle")) {
                val start = StageWorkbenchEntryMotionPolicy.start(title, top, right)
                assertTrue(start.scale in .94f..1f)
                assertTrue(start.alpha in 0f..1f)
                assertTrue(kotlin.math.abs(start.offsetXdp) <= 18)
                assertTrue(kotlin.math.abs(start.offsetYdp) <= 14)
                if (title.contains("Handle")) {
                    assertEquals(1f, start.scale, 0f)
                    assertEquals(0, start.offsetXdp)
                    assertEquals(0, start.offsetYdp)
                }
            }
        }
    }

    @Test fun sidebarSlidesTowardItsFinalPositionFromTheMainCard() {
        val left = StageWorkbenchEntryMotionPolicy.start("NeXtepSidebar", false, false)
        val right = StageWorkbenchEntryMotionPolicy.start("NeXtepSidebar", false, true)
        assertEquals(-left.offsetXdp, right.offsetXdp)
        assertEquals(left.scale, right.scale, 0f)
        val top = StageWorkbenchEntryMotionPolicy.start("NeXtepSidebar", true, true)
        assertEquals(0, top.offsetXdp)
        assertTrue(top.offsetYdp < 0)
    }

    @Test fun saverAndReducedMotionNeverLaunchEntryAnimation() {
        assertTrue(StageWorkbenchEntryMotionPolicy.enabled(true, false))
        assertFalse(StageWorkbenchEntryMotionPolicy.enabled(false, false))
        assertFalse(StageWorkbenchEntryMotionPolicy.enabled(true, true))
        assertFalse(StageWorkbenchEntryMotionPolicy.enabled(false, true))
    }
}

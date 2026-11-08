package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageDragHoverPolicyTest {
    @Test fun transientCrossingDoesNotSwapAndStableHoverRaisesOnlyOnce() {
        val p = StageDragHoverPolicy()
        assertNull(p.update(2, 100, 1000))
        assertNull(p.update(2, 100, 1549))
        assertEquals(2, p.update(2, 100, 1550))
        assertNull(p.update(2, 100, 3000))
    }
    @Test fun changingTaskIdentityOrLeavingCardRestartsDwell() {
        val p = StageDragHoverPolicy()
        p.update(1, 100, 1000)
        assertNull(p.update(1, 101, 1600))
        assertNull(p.update(1, 101, 2000))
        assertEquals(1, p.update(1, 101, 2150))
        p.update(null, null, 2200)
        assertNull(p.update(1, 101, 2800))
    }
    @Test fun everyTopRowSubsetKeepsTaskSlotsAndUsesDistinctHorizontalBands() {
        for (mask in 0..15) {
            val occupied = List(4) { mask and (1 shl it) != 0 }
            val lefts = List(4) { it * 210 }
            val poses = StageTopRowPolicy.positions(occupied, List(4) { 200 }, List(4) { 400 }, lefts,
                List(4) { 10 }, 1100, 440, 2f)
            assertEquals(StageActiveWindowPolicy.visible(occupied), poses.keys.toList())
            var previousRight = 0f
            for ((slot, pose) in poses) {
                val left = lefts[slot] + pose.x
                assertTrue(left >= previousRight)
                assertTrue(left + 200 <= 1100)
                assertEquals(0f, pose.tilt, 0f)
                previousRight = left + 200
            }
        }
    }
}

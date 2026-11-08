package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageActiveWindowPolicyTest {
    private val heights = List(4) { 500 }
    private val tops = List(4) { it * 503 }
    private fun layout(mask: Int, right: Boolean = true) = StageActiveWindowPolicy.positions(
        List(4) { mask and (1 shl it) != 0 }, heights, tops, 2200, listOf(2, 0, 3, 1), 3f, right)

    @Test fun zeroTasksProducesNoWindowAndEverySubsetKeepsOnlyItsRealSlotIdentities() {
        for (mask in 0..15) {
            val expected = (0..3).filter { mask and (1 shl it) != 0 }
            assertEquals(expected, layout(mask).keys.toList())
        }
        assertTrue(layout(0).isEmpty())
    }
    @Test fun gapsCompactWithoutReassigningTasksAndBothSidesKeepTheSamePositions() {
        val right = layout(10); val left = layout(10, false)
        assertEquals(setOf(1, 3), right.keys)
        val first = tops[1] + right.getValue(1).y
        val second = tops[3] + right.getValue(3).y
        assertEquals(430f, second - first, .01f)
        for (i in right.keys) {
            assertEquals(right.getValue(i).y, left.getValue(i).y, 0f)
            assertEquals(-right.getValue(i).x, left.getValue(i).x, 0f)
            assertEquals(-right.getValue(i).tilt, left.getValue(i).tilt, 0f)
        }
    }
    @Test fun closingTheLastWindowLeavesNoPlaceholderAndSingleWindowIsCentered() {
        val pose = layout(4).getValue(2)
        assertEquals(1100f, tops[2] + pose.y + heights[2] / 2f, .01f)
        assertTrue(layout(0).isEmpty())
        assertTrue(layout(15).values.all { it.alpha > 0f })
    }
}

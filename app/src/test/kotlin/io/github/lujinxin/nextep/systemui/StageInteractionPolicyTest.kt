package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageInteractionPolicyTest {
    @Test fun recentDepthDoesNotReassignSlotIndexesAndMirrorsBothSides() {
        val order = StageStackPolicy.touch(listOf(0, 1, 2, 3), 2)
        assertEquals(listOf(2, 0, 1, 3), order)
        assertEquals(order, StageStackPolicy.touch(order, 2))
        val right = StageStackPolicy.poses(List(4) { 468 }, List(4) { it * 471 }, 3f, true, order)
        val left = StageStackPolicy.poses(List(4) { 468 }, List(4) { it * 471 }, 3f, false, order)
        assertEquals(2, right.indices.maxBy { right[it].z })
        right.indices.forEach { i ->
            assertEquals(right[i].scale, left[i].scale, 0f)
            assertEquals(right[i].y, left[i].y, 0f)
            assertEquals(-right[i].x, left[i].x, 0f)
            assertEquals(-right[i].tilt, left[i].tilt, 0f)
        }
    }

    @Test fun stackedCardsOverlapButEachRetainsAnExposedVerticalSelectionBand() {
        val tops = List(4) { it * 471 }
        val poses = StageStackPolicy.poses(List(4) { 468 }, tops, 3f, true, listOf(0, 1, 2, 3))
        for (i in 0..2) {
            val top = tops[i] + poses[i].y
            val nextTop = tops[i + 1] + poses[i + 1].y
            assertTrue(nextTop > top + 300f)
            assertTrue(nextTop < top + 468f * poses[i].scale)
        }
    }

    @Test fun exchangeEndpointsAreExactAndMiddleLiftsWhileUnfolding() {
        val side = StageExchangeMotion.Box(870f, 500f, 1080f, 950f)
        val main = StageExchangeMotion.Box(0f, 300f, 860f, 2200f)
        assertEquals(side, StageExchangeMotion.frame(side, main, 0f).box)
        assertEquals(main, StageExchangeMotion.frame(side, main, 1f).box)
        val middle = StageExchangeMotion.frame(side, main, .5f)
        assertTrue(middle.box.top < (side.top + main.top) / 2)
        assertTrue(middle.box.width > 0 && middle.box.height > 0)
        assertEquals(main, StageExchangeMotion.frame(side, main, 5f).box)
    }

    @Test fun groupVerificationRejectsAnotherProfileAndWrongLiveTask() {
        val saved = StageTaskIdentity(10, "example.app", 0)
        assertFalse(StageGroupVerification.matches(saved, StageTaskIdentity(10, "example.app", 999), false))
        assertFalse(StageGroupVerification.matches(saved, StageTaskIdentity(11, "example.app", 0), true))
        assertTrue(StageGroupVerification.matches(saved, StageTaskIdentity(11, "example.app", 0), false))
        assertTrue(StageGroupVerification.matches(saved, saved, true))
        assertFalse(StageGroupVerification.matches(saved, null, false))
    }
    @Test fun switchingSavesTheLeavingGroupButRestoringCurrentKeepsItsSavedSnapshot() {
        assertTrue(StageGroupSwitchPolicy.shouldSaveCurrent("group-a", "group-b"))
        assertFalse(StageGroupSwitchPolicy.shouldSaveCurrent("group-a", "group-a"))
        assertFalse(StageGroupSwitchPolicy.shouldSaveCurrent(null, "group-b"))
    }

    @Test fun partialRestoreOrMissingMainCannotCommitTheActiveGroup() {
        assertTrue(StageGroupSwitchPolicy.isComplete(listOf(true, true, true, true), true))
        assertFalse(StageGroupSwitchPolicy.isComplete(listOf(true, false, true, true), true))
        assertFalse(StageGroupSwitchPolicy.isComplete(listOf(true, true, true, true), false))
        assertFalse(StageGroupSwitchPolicy.isComplete(emptyList(), true))
    }
}

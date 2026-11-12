package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageTabletSwapOwnershipPolicyTest {
    private val owner = StageTabletSwapOwnershipPolicy

    @Test fun readyDisplayMustBeEmptyNotMerelyDeclaredReady() {
        val tasks = listOf(owner.Owner(19, 0), owner.Owner(23, 8))
        assertTrue(owner.settled(null, 7, tasks))
        assertFalse(owner.settled(null, 8, tasks))
        assertFalse(owner.settled(null, -1, tasks))
    }

    @Test fun occupiedDisplayRejectsOldAndDuplicateOwner() {
        assertTrue(owner.settled(23, 8, listOf(owner.Owner(23, 8), owner.Owner(19, 0))))
        assertFalse(owner.settled(23, 8, listOf(owner.Owner(23, 0), owner.Owner(19, 8))))
        assertFalse(owner.settled(23, 8, listOf(owner.Owner(23, 8), owner.Owner(19, 8))))
    }

    @Test fun mainDisplayMustHaveExactIncomingTaskWithoutOldOccupant() {
        val during = listOf(owner.Owner(19, 0), owner.Owner(23, 0))
        assertFalse(owner.settled(23, 0, during))
        val committed = listOf(owner.Owner(23, 0), owner.Owner(19, 8))
        assertTrue(owner.settled(23, 0, committed))
        assertTrue(owner.settled(19, 8, committed))
    }
}

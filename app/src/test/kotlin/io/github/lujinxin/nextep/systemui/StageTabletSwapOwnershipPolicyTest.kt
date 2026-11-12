package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageTabletSwapOwnershipPolicyTest {
    private val owner = StageTabletSwapOwnershipPolicy

    @Test fun readyDisplayMustBeEmptyNotMerelyDeclaredReady() {
        val tasks = listOf(StageTabletSwapOwnershipPolicy.Owner(19, 0), StageTabletSwapOwnershipPolicy.Owner(23, 8))
        assertTrue(owner.settled(null, 7, tasks))
        assertFalse(owner.settled(null, 8, tasks))
        assertFalse(owner.settled(null, -1, tasks))
    }

    @Test fun occupiedDisplayNeedsTargetButMayAlsoHavePausedTasks() {
        assertTrue(owner.settled(23, 8, listOf(StageTabletSwapOwnershipPolicy.Owner(23, 8), StageTabletSwapOwnershipPolicy.Owner(19, 0))))
        assertFalse(owner.settled(23, 8, listOf(StageTabletSwapOwnershipPolicy.Owner(23, 0), StageTabletSwapOwnershipPolicy.Owner(19, 8))))
        assertTrue(owner.settled(23, 8, listOf(StageTabletSwapOwnershipPolicy.Owner(23, 8), StageTabletSwapOwnershipPolicy.Owner(19, 8))))
    }

    @Test fun mainDisplayMustContainIncomingTaskButCanAlsoContainHome() {
        val stillOld = listOf(StageTabletSwapOwnershipPolicy.Owner(19, 0), StageTabletSwapOwnershipPolicy.Owner(44, 0))
        assertFalse(owner.settled(23, 0, stillOld))
        // Display 0 can legitimately have HOME or suspended tasks alongside
        // the selected main task. Foreground and surface readiness are already
        // independently checked by the existing swap coordinator.
        val withHome = listOf(StageTabletSwapOwnershipPolicy.Owner(23, 0), StageTabletSwapOwnershipPolicy.Owner(44, 0), StageTabletSwapOwnershipPolicy.Owner(19, 8))
        assertTrue(owner.settled(23, 0, withHome))
        assertTrue(owner.settled(19, 8, withHome))
    }
}

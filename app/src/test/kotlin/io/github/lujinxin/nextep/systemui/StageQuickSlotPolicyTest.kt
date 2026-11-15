package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageQuickSlotPolicyTest {
    private val full = listOf(11,12,13,14)
    @Test fun existingOwnershipRequiresTaskAndDisplay() {
        val slots = listOf(null,StageQuickSlotPolicy.Owner(11,9),null,null)
        assertEquals(1,StageQuickSlotPolicy.ownedSlot(11,9,slots))
        assertNull(StageQuickSlotPolicy.ownedSlot(11,8,slots))
        assertNull(StageQuickSlotPolicy.ownedSlot(12,9,slots))
    }
    @Test fun existingAppIsNotMovedOrUsedToEvictAnotherSlot() {
        for (source in full.indices) {
            assertEquals(StageQuickSlotPolicy.Action.ALREADY_PRESENT,
                StageQuickSlotPolicy.action(full,source,null,null))
            for (target in full.indices)
                assertEquals(StageQuickSlotPolicy.Action.ALREADY_PRESENT,
                    StageQuickSlotPolicy.action(full,source,target,full[target]))
        }
    }
    @Test fun explicitReplacementChecksCurrentOwner() {
        assertEquals(StageQuickSlotPolicy.Action.REPLACE,StageQuickSlotPolicy.action(full,null,1,12))
        assertEquals(StageQuickSlotPolicy.Action.STALE,StageQuickSlotPolicy.action(full,null,1,99))
        assertEquals(StageQuickSlotPolicy.Action.STALE,StageQuickSlotPolicy.action(full,0,1,99))
        assertEquals(StageQuickSlotPolicy.Action.STALE,StageQuickSlotPolicy.action(full,null,-1,11))
    }
    @Test fun onlyFreeCapacityAllowsAdd() {
        assertEquals(StageQuickSlotPolicy.Action.FULL,StageQuickSlotPolicy.action(full,null,null,null))
        assertEquals(StageQuickSlotPolicy.Action.ADD,StageQuickSlotPolicy.action(listOf(11,null,13,14),null,null,null))
        assertEquals(StageQuickSlotPolicy.Action.FULL,StageQuickSlotPolicy.action(emptyList(),null,null,null))
    }
}

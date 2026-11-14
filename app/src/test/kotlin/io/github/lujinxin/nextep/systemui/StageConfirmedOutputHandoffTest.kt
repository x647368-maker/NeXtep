package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageConfirmedOutputHandoffTest {
    @Test fun replacementProducerCannotSatisfyAnOldCheckpoint() {
        val p=StageConcurrentNativeTrialPolicy
        val texture=Any()
        val surface=Any()
        assertTrue(p.sameOutput(12,12,texture,texture,surface,surface))
        assertFalse(p.sameOutput(12,13,texture,texture,surface,surface))
        assertFalse(p.sameOutput(12,12,texture,Any(),surface,surface))
        assertFalse(p.sameOutput(12,12,texture,texture,surface,Any()))
        assertFalse(p.sameOutput(12,12,null,null,surface,surface))
        assertFalse(p.sameOutput(12,12,texture,texture,null,null))
    }
    @Test fun checkpointRequiresFreshBoundOutputAndRetainsStrictOrdering() {
        val p=StageConcurrentNativeTrialPolicy
        assertTrue(p.freshFrameOnConfirmedOutput(true,100L,101L))
        assertFalse(p.freshFrameOnConfirmedOutput(true,100L,100L))
        assertFalse(p.freshFrameOnConfirmedOutput(true,100L,99L))
        assertFalse(p.freshFrameOnConfirmedOutput(false,100L,101L))
        assertFalse(p.freshFrameOnConfirmedOutput(true,null,101L))
    }
}

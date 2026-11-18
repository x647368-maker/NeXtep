package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.framework.StageHeldReceiptGeometry
import io.github.x647368maker.atomicworkbench.framework.TaskPresentationCommitProof
import org.junit.Assert.*
import org.junit.Test

class StageHeldEndpointReceiptTest {
    private val token=Any()
    private val leash=Any()
    private val key=StageHeldReceiptGeometry(11,0,token,480,0,0,1272,2772,.75f,318f,460f)
    @Test fun noDuplicateForUnchangedGeometry() {
        val proof=TaskPresentationCommitProof<StageHeldReceiptGeometry>()
        val first=proof.arm(leash,100L,key)
        assertTrue(proof.claimListener(first))
        repeat(100) {
            val same=proof.arm(leash,100L,key.copy())
            assertSame(first,same)
            assertFalse(proof.claimListener(same))
        }
        assertTrue(proof.acknowledge(first,true))
        assertTrue(proof.confirmed(leash,100L,key))
    }
    @Test fun sourceConfigurationOrMapChangeInvalidatesEarlierReceipt() {
        for(changed in listOf(key.copy(cropBottom=2517),key.copy(densityDpi=400),
                key.copy(scale=1f),key.copy(y=0f),key.copy(displayId=8),
                key.copy(taskId=12),key.copy(token=Any()))) {
            val proof=TaskPresentationCommitProof<StageHeldReceiptGeometry>()
            val first=proof.arm(leash,100L,key)
            assertTrue(proof.claimListener(first))
            val second=proof.arm(leash,100L,changed)
            assertNotSame(first,second)
            assertFalse(proof.acknowledge(first,true))
            assertFalse(proof.confirmed(leash,100L,changed))
            assertTrue(proof.claimListener(second))
            assertTrue(proof.acknowledge(second,true))
            assertTrue(proof.confirmed(leash,100L,changed))
            assertFalse(proof.confirmed(leash,100L,key))
        }
    }
    @Test fun roundTripGeometryCannotReuseOldReceipt() {
        val proof=TaskPresentationCommitProof<StageHeldReceiptGeometry>()
        val first=proof.arm(leash,100L,key)
        assertTrue(proof.claimListener(first))
        proof.arm(leash,100L,key.copy(cropBottom=2517))
        val final=proof.arm(leash,100L,key)
        assertNotSame(first,final)
        assertFalse(proof.acknowledge(first,true))
        assertTrue(proof.claimListener(final))
        assertTrue(proof.acknowledge(final,true))
        assertTrue(proof.confirmed(leash,100L,key))
    }
    @Test fun replacementLeashMoveAndFailedRegistrationCannotConfirmWrongGeneration() {
        val proof=TaskPresentationCommitProof<StageHeldReceiptGeometry>()
        val first=proof.arm(leash,100L,key)
        assertTrue(proof.claimListener(first))
        proof.releaseFailedListener(first)
        assertTrue(proof.claimListener(first))
        val newLeash=Any()
        val next=proof.arm(newLeash,101L,key)
        assertFalse(proof.acknowledge(first,true))
        assertFalse(proof.acknowledge(next,false))
        assertTrue(proof.claimListener(next))
        assertTrue(proof.acknowledge(next,true))
        assertFalse(proof.confirmed(leash,100L,key))
        assertTrue(proof.confirmed(newLeash,101L,key))
    }
}

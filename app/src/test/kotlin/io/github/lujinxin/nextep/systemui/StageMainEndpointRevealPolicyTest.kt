package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageMainEndpointRevealPolicyTest {
    private val gate=StageMainEndpointRevealPolicy

    @Test fun unequalSlotAndMainAspectsRequireAShortRetirementBlend() {
        // From the real API37 v89 diagnostics:
        // slot image 271x535 versus main 973x2120. The contained image
        // cannot cover both physical dimensions without a sudden jump.
        assertTrue(gate.needsBlend(271,535,973f,2120f))
        assertTrue(gate.needsBlend(520,980,973f,2120f))
        assertFalse(gate.needsBlend(973,2120,973f,2120f))
        assertFalse(gate.needsBlend(200,400,1000f,2000f))
        assertFalse(gate.needsBlend(0,535,973f,2120f))
        assertFalse(gate.needsBlend(271,0,973f,2120f))
        assertFalse(gate.needsBlend(271,535,Float.NaN,2120f))
        assertFalse(gate.needsBlend(271,535,Float.POSITIVE_INFINITY,2120f))
        assertFalse(gate.needsBlend(271,535,973f,0f))
        // Main preview must never be stretched to a different aspect.
    }

    @Test fun cannotRevealMainBeforeRealMoveAndCompositorEndpointProof() {
        for(mask in 0 until 32) {
            fun bit(index:Int)=(mask and (1 shl index))!=0
            val acknowledged=bit(0)
            val terminalFrameConfirmed=bit(1)
            val missingReceipt=bit(2)
            val stableMain=bit(3)
            val alreadyBegun=bit(4)
            val expected=acknowledged && terminalFrameConfirmed &&
                !missingReceipt && stableMain && !alreadyBegun
            assertEquals("case=$mask",expected,
                gate.canBegin(acknowledged,terminalFrameConfirmed,
                    missingReceipt,stableMain,alreadyBegun))
        }
    }

    @Test fun readyMainIsHeldOnlyForBoundedActiveBlend() {
        assertEquals(48L,gate.BLEND_MS)
        assertEquals(72L,gate.MAX_BLEND_WAIT_MS)
        assertTrue(gate.MAX_BLEND_WAIT_MS>gate.BLEND_MS)
        assertFalse(gate.keepPreviewDuringReady(false,false,0L))
        assertFalse(gate.keepPreviewDuringReady(false,true,0L))
        assertTrue(gate.keepPreviewDuringReady(true,false,0L))
        assertTrue(gate.keepPreviewDuringReady(true,false,48L))
        assertTrue(gate.keepPreviewDuringReady(true,false,71L))
        assertFalse(gate.keepPreviewDuringReady(true,false,72L))
        assertFalse(gate.keepPreviewDuringReady(true,false,120L))
        assertFalse(gate.keepPreviewDuringReady(true,true,1L))
        assertFalse(gate.keepPreviewDuringReady(true,false,-1L))
    }
}

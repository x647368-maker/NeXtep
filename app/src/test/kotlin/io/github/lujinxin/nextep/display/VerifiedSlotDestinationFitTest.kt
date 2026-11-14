package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test

class VerifiedSlotDestinationFitTest {
    @Test fun steadyAndVerifiedExchangeHaveSeparateAuthorization() {
        val p=TaskContinuityPolicy
        assertTrue(p.mayFitSlotDuringExchange(false,null,17,null,null))
        assertFalse(p.mayFitSlotDuringExchange(true,17,17,100L,null))
        assertTrue(p.mayFitSlotDuringExchange(true,17,17,100L,100L))
        assertFalse(p.mayFitSlotDuringExchange(true,17,17,101L,100L))
        assertFalse(p.mayFitSlotDuringExchange(true,19,17,100L,100L))
        assertFalse(p.mayFitSlotDuringExchange(true,0,0,100L,100L))
        assertFalse(p.mayFitSlotDuringExchange(true,17,17,null,null))
    }
    @Test fun onlyExactFullscreenDestinationCanBeVerified() {
        val p=TaskContinuityPolicy
        assertTrue(p.verifiedSlotDestination(17,17,true,false,true,true))
        assertFalse(p.verifiedSlotDestination(17,0,true,false,true,true))
        assertFalse(p.verifiedSlotDestination(17,null,true,false,true,true))
        assertFalse(p.verifiedSlotDestination(17,17,false,false,true,true))
        assertFalse(p.verifiedSlotDestination(17,17,true,true,true,true))
        assertFalse(p.verifiedSlotDestination(17,17,true,false,false,true))
        assertFalse(p.verifiedSlotDestination(17,17,true,false,true,false))
        assertFalse(p.verifiedSlotDestination(0,0,true,false,true,true))
    }
}

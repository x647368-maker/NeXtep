package io.github.x647368maker.atomicworkbench.framework

import org.junit.Assert.*
import org.junit.Test

class StageHeldGeometryReceiptTest {
    private val owner=Any()
    private val token=Any()
    private val a=StageHeldReceiptGeometry(41,0,token,512,0,0,1272,2517,.75f,318f,460f)
    private val b=a.copy(bottom=2772)
    @Test fun olderReceiptCannotAuthorizeChangedGeometryOrAba() {
        val gate=StageHeldGeometryReceipt()
        val first=checkNotNull(gate.observe(owner,100L,a))
        assertTrue(gate.acknowledge(first,true))
        assertTrue(gate.confirmed(owner,100L,a))
        val second=checkNotNull(gate.observe(owner,100L,b))
        assertFalse(gate.confirmed(owner,100L,b))
        assertFalse(gate.acknowledge(first,true))
        val third=checkNotNull(gate.observe(owner,100L,a))
        assertFalse(gate.acknowledge(first,true))
        assertFalse(gate.acknowledge(second,true))
        assertFalse(gate.confirmed(owner,100L,a))
        assertTrue(gate.acknowledge(third,true))
        assertTrue(gate.confirmed(owner,100L,a))
    }
    @Test fun densityDisplayTaskAndReservationArePartOfTheProof() {
        val gate=StageHeldGeometryReceipt()
        val first=checkNotNull(gate.observe(owner,100L,a))
        assertTrue(gate.acknowledge(first,true))
        assertFalse(gate.confirmed(owner,101L,a))
        assertFalse(gate.confirmed(owner,null,a))
        assertFalse(gate.confirmed(Any(),100L,a))
        assertFalse(gate.confirmed(owner,100L,a.copy(densityDpi=320)))
        assertFalse(gate.confirmed(owner,100L,a.copy(displayId=12)))
        assertFalse(gate.confirmed(owner,100L,a.copy(taskId=42)))
        assertFalse(gate.confirmed(owner,100L,a.copy(token=Any())))
        assertFalse(gate.confirmed(owner,100L,a.copy(x=319f)))
        assertFalse(gate.confirmed(owner,100L,a.copy(scale=.8f)))
    }
    @Test fun registrationIsBoundedEvenWhenGeometryOscillates() {
        val gate=StageHeldGeometryReceipt()
        val tickets=(0..9).map {gate.observe(owner,100L,a.copy(bottom=2517+it))}
        assertEquals(StageHeldGeometryReceipt.MAX_REGISTRATIONS,tickets.count {it!=null})
        for(ticket in tickets.filterNotNull())assertFalse(gate.acknowledge(ticket,true))
        assertFalse(gate.confirmed(owner,100L,a.copy(bottom=2526)))
    }
    @Test fun identicalWritesShareReceiptAndFailureRetriesAreBounded() {
        val gate=StageHeldGeometryReceipt()
        val ticket=checkNotNull(gate.observe(owner,100L,a))
        repeat(500) {assertNull(gate.observe(owner,100L,a))}
        gate.failedRegistration(ticket)
        val retry=checkNotNull(gate.observe(owner,100L,a))
        assertSame(ticket,retry)
        assertTrue(gate.acknowledge(retry,true))
        repeat(500) {assertNull(gate.observe(owner,100L,a))}
        assertTrue(gate.confirmed(owner,100L,a))
    }
    @Test fun missingMetadataInvalidatesAndCancelledOwnerCannotAcknowledge() {
        val gate=StageHeldGeometryReceipt()
        val ticket=checkNotNull(gate.observe(owner,100L,a))
        assertFalse(gate.acknowledge(ticket,false))
        assertNull(gate.observe(owner,null,b))
        assertFalse(gate.acknowledge(ticket,true))
        val next=checkNotNull(gate.observe(owner,100L,b))
        gate.invalidate()
        assertFalse(gate.acknowledge(next,true))
        assertNull(gate.observe(owner,100L,b.copy(densityDpi=0)))
        assertNull(gate.observe(owner,100L,b.copy(scale=Float.NaN)))
    }
    @Test fun directionsDoNotShareAcknowledgments() {
        val up=StageHeldGeometryReceipt()
        val down=StageHeldGeometryReceipt()
        val ticket=checkNotNull(up.observe(owner,100L,a))
        checkNotNull(down.observe(owner,100L,a))
        assertTrue(up.acknowledge(ticket,true))
        assertFalse(down.acknowledge(ticket,true))
        assertFalse(down.confirmed(owner,100L,a))
    }
}

package io.github.x647368maker.atomicworkbench.logging

import org.junit.Assert.*
import org.junit.Test

class StageHandoffEvidenceBufferTest {
    @Test fun captureIsOptInBoundedAndExpiredWithoutTimer() {
        val b=StageHandoffEvidenceBuffer(2)
        assertNull(b.ticket(100))
        assertTrue(b.arm(100,100,50))
        val t=checkNotNull(b.ticket(101))
        b.record(t,101,1,"one")
        b.record(t,102,2,"one")
        b.record(t,103,3,"two")
        b.record(t,104,4,"three")
        assertNull(b.ticket(150))
        b.record(t,150,5,"late")
        val report=checkNotNull(b.finish(100))
        assertTrue(report.contains("events=2 dropped=1 repeated=1"))
        assertTrue(report.contains("3 two"))
        assertTrue(report.contains("4 three"))
        assertFalse(report.contains("late"))
        assertFalse(report.contains("1 one"))
        assertEquals(report,b.finish(100))
        assertFalse(b.arm(100,105,50))
    }

    @Test fun previousCaptureCannotWriteIntoSuccessor() {
        val b=StageHandoffEvidenceBuffer()
        assertFalse(b.arm(100,90,50))
        assertFalse(b.arm(100,150,50))
        assertTrue(b.arm(100,100,50))
        val old=checkNotNull(b.ticket(101))
        assertTrue(b.arm(110,110,50))
        b.record(old,111,3,"old")
        val t=checkNotNull(b.ticket(112))
        b.record(t,112,4,"new")
        assertNull(b.finish(100))
        val report=checkNotNull(b.finish(110))
        assertFalse(report.contains("old"))
        assertTrue(report.contains("new"))
        assertNull(b.ticket(113))
    }
}

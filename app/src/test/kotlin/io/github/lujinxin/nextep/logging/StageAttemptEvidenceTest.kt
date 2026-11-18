package io.github.x647368maker.atomicworkbench.logging

import org.junit.Assert.*
import org.junit.Test

class StageAttemptEvidenceTest {
    private fun sample(t:Long,s:Float)=StageAttemptEvidence.Sample(t,s,s,318f,460f,0,0,1272,2772)
    @Test fun tenThousandWritesCannotEraseAttemptOrSingleFrameJump() {
        val e=StageAttemptEvidence()
        e.begin(115,100,"tap_swap")
        e.stage(115,101,"root","mirror_root=unknown_legacy")
        var layerLookups=0
        repeat(10_000) { i ->
            val scale=if(i==5000)1f else .75f
            e.writer(115,"task_write",869,374,sample(200L+i,scale)) { layerLookups++;"374" }
        }
        e.stage(115,10_500,"move","move_tasks up=869 down=873")
        e.stage(115,10_600,"cover","cover_hide_submit:ready")
        e.end(115,10_601,"gpu_complete")
        repeat(10_000) { i -> e.writer(115,"task_write",869,374,sample(20_000L+i,1f)) {"374"} }
        val s=e.describe()
        assertEquals(1,layerLookups)
        assertTrue(s.contains("attempt=115 start_ns=100 end_ns=10601"))
        assertTrue(s.contains("mirror_root=unknown_legacy"))
        assertTrue(s.contains("count=10000 max_scale_step=0.25"))
        assertTrue(s.contains("jump_after 5200 s=1.0,1.0"))
        assertTrue(s.contains("cover_hide_submit:ready"))
        assertFalse(s.contains("count=20000"))
    }
    @Test fun lateEventsAndHistoryOverflowAreExplicit() {
        val e=StageAttemptEvidence()
        for(i in 1L..4L) {
            e.begin(i,i*100,"tap_swap")
            e.stage(i,i*100+1,"phase","event_$i")
            e.stage(i-1,i*100+2,"wrong","stale_phase")
            e.end(i-1,i*100+3,"stale_outcome")
            e.end(i,i*100+4,"done")
        }
        val s=e.describe()
        assertTrue(s.contains("attempt_history=3 evicted=1"))
        assertFalse(s.contains("attempt=1 "))
        assertFalse(s.contains("stale_phase"))
        assertFalse(s.contains("stale_outcome"))
        assertTrue(s.contains("attempt=4 start_ns=400 end_ns=404"))
    }
    @Test fun replacementCaptureRejectsOldAttempt() {
        val b=StageHandoffEvidenceBuffer(2)
        assertTrue(b.arm(100,100,50))
        val old=checkNotNull(b.ticket(101))
        b.beginAttempt(old,101,3,101_000,"tap")
        assertTrue(b.arm(110,110,50))
        val fresh=checkNotNull(b.ticket(111))
        b.beginAttempt(fresh,111,4,111_000,"tap")
        b.attemptStage(old,111,3,111_001,"stale","stale_payload")
        b.attemptWriter(old,111,3,"task",2,2,sample(111_002,.75f)) {error("stale layer resolution")}
        b.endAttempt(fresh,112,4,112_000,"ok")
        val s=checkNotNull(b.finish(110))
        assertTrue(s.contains("attempt=4"))
        assertFalse(s.contains("stale_payload"))
        assertFalse(s.contains("attempt=3"))
    }
}

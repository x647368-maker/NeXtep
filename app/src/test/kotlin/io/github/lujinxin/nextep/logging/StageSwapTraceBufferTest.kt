package io.github.x647368maker.atomicworkbench.logging

import org.junit.Assert.*
import org.junit.Test

class StageSwapTraceBufferTest {
    @Test fun taskIdentityAndTimeoutCoverSubmissionSurviveTraceOverflow() {
        var now=0L
        val r=StageSwapTraceBuffer({now})
        val a=r.begin("tap_swap")
        repeat(20) { now++;r.event("prepare_$it",a) }
        now++;r.event("move_tasks up=12 down=34",a)
        now++;r.event("handoff_timeout:target_not_ready",a)
        now++;r.event("cover_hide_submit:timeout up=12 down=34",a)
        repeat(80) { now++;r.event("gpu_stats_$it",a) }
        r.finish("not_ready",a)
        val report=r.describe()
        assertTrue(report.contains("move_tasks up=12 down=34@21ms"))
        assertTrue(report.contains("handoff_timeout:target_not_ready@22ms"))
        assertTrue(report.contains("cover_hide_submit:timeout up=12 down=34@23ms"))
        assertTrue(report.contains("end:not_ready"))
        assertTrue(report.contains("omitted="))
        assertTrue(report.split(" > ").size<=29)
    }

    @Test fun essentialGpuMilestonesSurviveReadinessOscillation() {
        var now=0L
        val recorder=StageSwapTraceBuffer({now})
        val id=recorder.begin("tap_swap")
        repeat(14) { now++;recorder.event("prepare_$it",id) }
        val essential=listOf("native_terminal_transaction_committed",
            "real_move_requested", "real_move_ack", "handoff_target_ready",
            "endpoint_reveal_start", "endpoint_reveal_commit")
        essential.forEach { now++;recorder.event(it,id) }
        // Each transition is individually valid; under the old single-tier
        // protection, repeated WAIT/READY oscillations evicted all six events.
        repeat(40) {
            now++
            val state=if(it%2==0) "target_wait=slot_post_ack_buffer"
                else "handoff_state=target_not_ready"
            recorder.event(state,id)
        }
        now++
        recorder.finish("gpu_completed",id)
        val trace=recorder.describe()
        essential.forEach { assertTrue("lost critical milestone: $it",trace.contains("$it@")) }
        assertTrue(trace.contains("end:gpu_completed@"))
        assertTrue(trace.contains("[omitted="))
        assertTrue(trace.split(" > ").size<=29)
    }

    @Test fun gpuHandoffMilestonesSurviveDeviceTraceTruncation() {
        var clock=0L
        val recorder=StageSwapTraceBuffer({clock})
        recorder.begin("tap_swap")
        repeat(14) { recorder.event("prepare_$it") }
        val stages=listOf("native_terminal_transaction_committed", "real_move_requested",
            "real_move_ack", "target_wait=slot_post_ack_buffer", "handoff_target_ready",
            "endpoint_reveal_start", "endpoint_reveal_commit")
        stages.forEach { clock+=20;recorder.event(it) }
        repeat(18) { recorder.event("gpu_stats_$it") }
        recorder.finish("completed")
        val report=recorder.describe()
        stages.forEach { assertTrue("lost $it",report.contains(it+"@")) }
        assertTrue(report.contains("omitted="))
        assertTrue(report.split(" > ").size<=29)
    }

    @Test fun keepsExactlyLastFiveExchangesWithIndividualDurations() {
        var time = 100L
        val recorder = StageSwapTraceBuffer({ time })
        repeat(7) { i ->
            recorder.begin("tap_swap")
            time += 12
            recorder.event("mirror_${i}")
            time += 8
            recorder.finish("ok")
        }
        val entries = recorder.describe().lines()
        assertEquals(5, entries.size)
        assertTrue(entries.first().startsWith("#3 tap_swap"))
        assertTrue(entries.last().startsWith("#7 tap_swap"))
        assertTrue(entries.last().contains("mirror_6@12ms"))
        assertTrue(entries.last().contains("end:ok@20ms"))
        assertFalse(recorder.describe().contains("mirror_0"))
    }

    @Test fun interruptionNeverSilentlyReportsSuccess() {
        var time = 0L
        val recorder = StageSwapTraceBuffer({ time })
        recorder.begin("tap_swap")
        recorder.event("native_armed")
        time = 44
        recorder.begin("tap_swap")
        val report = recorder.describe()
        assertTrue(report.contains("end:superseded"))
        assertTrue(report.contains("#2 tap_swap begin@0ms"))
    }

    @Test fun lateNativeCallbackCannotBeMisattributedToNextTap() {
        var time=0L
        val recorder=StageSwapTraceBuffer({ time })
        val first=recorder.begin("tap_swap")
        assertTrue(recorder.event("gpu_begin",first))
        time+=10
        val second=recorder.begin("tap_swap")
        assertFalse(recorder.event("late_surface_commit",first))
        recorder.finish("late_wrong_finish",first)
        assertTrue(recorder.event("gpu_begin",second))
        recorder.finish("ok",second)
        val traces=recorder.describe()
        assertFalse(traces.contains("late_surface_commit"))
        assertFalse(traces.contains("late_wrong_finish"))
        assertTrue(traces.contains("end:superseded"))
        assertTrue(traces.contains("end:ok"))
    }

    @Test fun longTraceRetainsTailAndExactFinalOutcome() {
        var clock=0L
        val recorder=StageSwapTraceBuffer({clock})
        val id=recorder.begin("tap_swap")
        repeat(26) {
            clock+=10
            recorder.event("stage_$it-"+"x".repeat(78),id)
        }
        recorder.finish("handoff_timeout",id)
        val trace=recorder.describe()
        assertTrue(trace.length>1000)
        assertTrue(trace.contains("stage_0"))
        assertTrue(trace.contains("stage_25"))
        assertTrue(trace.contains("end:handoff_timeout"))
        assertTrue(trace.length<4000)
    }

    @Test fun keepsReadinessCausesWhenMiddleEventsAreTruncated() {
        var now=0L
        val recorder=StageSwapTraceBuffer({now})
        val id=recorder.begin("tap_swap")
        repeat(21) { now++;recorder.event("gpu_prepare_$it",id) }
        now++;recorder.event("target_wait=slot_post_ack_buffer",id)
        now++;recorder.event("handoff_state=target_not_ready",id)
        repeat(100) { now++;recorder.event("gpu_frame_$it",id) }
        now++;recorder.event("handoff_target_ready",id)
        now++;recorder.finish("gpu_completed",id)
        val text=recorder.describe()
        assertTrue(text.contains("target_wait=slot_post_ack_buffer@22ms"))
        assertTrue(text.contains("handoff_state=target_not_ready@23ms"))
        assertTrue(text.contains("handoff_target_ready@124ms"))
        assertTrue(text.contains("end:gpu_completed@125ms"))
        assertTrue(text.contains("[omitted="))
        assertTrue(text.length<4000)
    }

    @Test fun boundsEventNamesAndCapsTheirNumber() {
        var time = 0L
        val recorder = StageSwapTraceBuffer({ time }, maxRecords=2, maxEvents=4)
        recorder.begin("tap_swap")
        repeat(500) {
            time++
            recorder.event("private-data-".repeat(20))
        }
        recorder.finish("timeout")
        val report = recorder.describe()
        assertTrue(report.length <= 1000)
        assertTrue(report.contains("private-data-"))
        assertTrue(report.contains("end:timeout")) // outcome must survive a full trace
    }

    @Test fun overflowKeepsLateCompositorHandoffAndDisclosesMissingMiddle() {
        var now=0L
        val recorder=StageSwapTraceBuffer({ now })
        val id=recorder.begin("tap_swap")
        repeat(120) { i -> now++;recorder.event("stage_$i",id) }
        now++
        recorder.event("terminal_commit",id)
        now++
        recorder.finish("handoff_timeout",id)
        val trace=recorder.describe()
        assertTrue(trace.contains("stage_0@1ms"))
        assertTrue(trace.contains("terminal_commit@121ms"))
        assertTrue(trace.contains("end:handoff_timeout@122ms"))
        assertTrue(trace.contains("[omitted=95]"))
        assertFalse(trace.contains("stage_60@"))
    }
}

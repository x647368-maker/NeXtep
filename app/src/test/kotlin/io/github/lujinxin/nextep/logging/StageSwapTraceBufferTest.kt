package io.github.x647368maker.atomicworkbench.logging

import org.junit.Assert.*
import org.junit.Test

class StageSwapTraceBufferTest {
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
        assertFalse(report.contains("end:timeout")) // the 4-event cap prevents unbounded string append
    }
}

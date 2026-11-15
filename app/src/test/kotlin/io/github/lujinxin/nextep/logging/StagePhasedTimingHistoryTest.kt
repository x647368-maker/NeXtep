package io.github.x647368maker.atomicworkbench.logging

import org.junit.Assert.*
import org.junit.Test

class StagePhasedTimingHistoryTest {
    @Test fun retainsLastFiveWithAccurateTimingAndExplicitCancellation() {
        var now = 0L
        val history = StagePhasedTimingHistory { now }
        repeat(8) { i ->
            history.begin("slot=$i")
            now += 12
            history.event("promote")
            now += 7
            history.event("demote")
            now += 5
            history.finish(if (i == 7) "cancelled" else "ok=true")
        }
        val lines = history.describe().lines()
        assertEquals(5,lines.size)
        assertTrue(lines.first().startsWith("#4 slot=3"))
        assertTrue(lines.last().contains("cancelled total=24ms"))
        assertTrue(lines.last().contains("demote=7ms@19ms"))
    }

    @Test fun noisyAttemptsKeepBothEndsAndNeverInventCompletion() {
        var now=0L
        val h=StagePhasedTimingHistory { now }
        h.begin("slot=0")
        repeat(160) { i -> now++;h.event("phase_$i") }
        assertTrue(h.describe().contains("ongoing"))
        h.begin("slot=1")
        val text=h.describe()
        assertTrue(text.contains("superseded"))
        assertTrue(text.contains("phase_0="))
        assertTrue(text.contains("phase_159="))
        assertTrue(text.contains("omitted=140"))
        assertTrue(text.contains("#2 slot=1 ongoing"))
        h.finish("ok=false")
        val before=h.describe()
        h.event("late");h.finish("ok=true")
        assertEquals(before,h.describe())
    }
}

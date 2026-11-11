package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StagePanelDrawProbePolicyTest {
    @Test fun unchangedFrameBurstDoesNotRescanEveryDraw() {
        val start=10_000L
        assertTrue(StagePanelDrawProbePolicy.shouldProbe(start, Long.MIN_VALUE))
        assertFalse(StagePanelDrawProbePolicy.shouldProbe(start+8,start))
        assertFalse(StagePanelDrawProbePolicy.shouldProbe(start+99,start))
        assertTrue(StagePanelDrawProbePolicy.shouldProbe(start+100,start))
    }

    @Test fun resetAfterUptimeRollbackIsImmediate() {
        assertTrue(StagePanelDrawProbePolicy.shouldProbe(100L,1000L))
        assertTrue(StagePanelDrawProbePolicy.shouldProbe(0L,Long.MIN_VALUE))
    }

    @Test fun expectedProbeCountIsBoundedAcrossNinetyHertzFrames() {
        var last=Long.MIN_VALUE
        var probes=0
        for(i in 0..899) {
            val now=i*11L
            if(StagePanelDrawProbePolicy.shouldProbe(now,last)){
                probes++
                last=now
            }
        }
        // 900 PRE-DRAW frames should produce ~90 scans, not 900.
        assertTrue("Too many state scans: $probes",probes in 85..100)
    }
}

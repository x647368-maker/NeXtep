package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test

class StageCpuEvidenceTest {
    @Test fun reportsWholeProcessCpuTimeNotModulePower() {
        assertEquals(3.944, StageCpuEvidence.averageCoreEquivalents(
            0, 3_549_600, 100_000, 1_000_000)!!, 0.001)
        assertEquals(0.0, StageCpuEvidence.averageCoreEquivalents(
            50, 50, 100, 1000)!!, 0.0)
    }
    @Test fun rejectsResetNegativeOrMissingSamples() {
        assertNull(StageCpuEvidence.averageCoreEquivalents(-1,200,1,10))
        assertNull(StageCpuEvidence.averageCoreEquivalents(200,100,1,10))
        assertNull(StageCpuEvidence.averageCoreEquivalents(0,10,100,100))
        assertNull(StageCpuEvidence.averageCoreEquivalents(0,10,110,100))
    }
}

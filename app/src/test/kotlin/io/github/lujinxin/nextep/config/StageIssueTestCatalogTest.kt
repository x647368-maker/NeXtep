package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test

class StageIssueTestCatalogTest {
    @Test fun eightUniqueGuidedDiagnosesHaveHonestLimits() {
        assertEquals(8,StageIssueTestCatalog.cases.size)
        assertEquals(8,StageIssueTestCatalog.cases.map{it.id}.toSet().size)
        for (test in StageIssueTestCatalog.cases) {
            assertTrue(test.name.isNotBlank())
            assertTrue(test.instructions.contains("1."))
            assertTrue(test.boundary.isNotBlank())
            assertTrue(StageIssueTestCatalog.guide(test.id).contains(test.instructions))
            assertTrue(StageIssueTestCatalog.guide(test.id).contains(test.boundary))
        }
    }
    @Test fun untrustedUnknownCategorySafelyFallsBack() {
        assertEquals("other",StageIssueTestCatalog.find("strange\nraw").id)
        assertEquals("power",StageIssueTestCatalog.find("power").id)
    }
}

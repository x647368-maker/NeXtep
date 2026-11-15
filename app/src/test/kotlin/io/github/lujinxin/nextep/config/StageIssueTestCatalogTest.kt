package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test

class StageIssueTestCatalogTest {
    @Test fun combinedAndFiveUniqueGuidedDiagnosesHaveHonestLimits() {
        val expected = listOf("all","performance","layout","content","compatibility","services")
        assertEquals(expected,StageIssueTestCatalog.cases.map{it.id})
        assertEquals(expected.drop(1).toSet(),StageIssueTestCatalog.modulesFor("all"))
        for (test in StageIssueTestCatalog.cases) {
            assertTrue(test.name.isNotBlank())
            assertTrue(test.instructions.isNotBlank())
            assertTrue(test.boundary.isNotBlank())
            assertTrue(StageIssueTestCatalog.guide(test.id).contains(test.instructions))
            assertTrue(StageIssueTestCatalog.guide(test.id).contains(test.boundary))
            if (test.id != "all") assertEquals(setOf(test.id),StageIssueTestCatalog.modulesFor(test.id))
        }
    }

    @Test fun allEightLegacyReportCategoriesRetainTheirOriginalGuides() {
        val ids = listOf("windows","foldable","gaming","unlock","power","crash","status","other")
        val expectedModules = listOf("layout","compatibility","content","compatibility",
            "performance","performance","compatibility",null)
        for ((i,id) in ids.withIndex()) {
            val test = StageIssueTestCatalog.find(id)
            assertEquals(id,test.id)
            assertTrue(test.name.isNotBlank())
            assertTrue(test.instructions.contains("1."))
            assertTrue(test.boundary.isNotBlank())
            assertTrue(StageIssueTestCatalog.guide(id).contains(test.instructions))
            assertTrue(StageIssueTestCatalog.guide(id).contains(test.boundary))
            assertEquals(setOfNotNull(expectedModules[i]),StageIssueTestCatalog.modulesFor(id))
        }
    }

    @Test fun untrustedUnknownCategorySafelyFallsBack() {
        assertEquals("other",StageIssueTestCatalog.find("strange\nraw").id)
        assertEquals("power",StageIssueTestCatalog.find("power").id)
    }
}

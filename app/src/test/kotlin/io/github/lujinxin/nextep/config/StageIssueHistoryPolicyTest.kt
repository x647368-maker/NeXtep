package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test

class StageIssueHistoryPolicyTest {
    @Test fun newestReportWinsAndRetentionIgnoresWallClockChanges() {
        val records=(0..20).map { StageIssueHistoryPolicy.Record("$it",it.toLong(),"all","report_$it") }
        val revised=records.first().copy(text="updated user note")
        val history=StageIssueHistoryPolicy.retain(listOf(revised)+records)
        assertEquals(10,history.size)
        assertEquals(revised,history.first())
        assertEquals("9",history.last().id)
        assertEquals(history,StageIssueHistoryPolicy.retain(history))
    }
    @Test fun fiveModulesAndLegacyReportLabelsArePreserved() {
        assertEquals(6,StageIssueTestCatalog.cases.size)
        assertEquals(5,StageIssueTestCatalog.modulesFor("all").size)
        for (module in StageIssueTestCatalog.modules) {
            assertEquals(setOf(module.id),StageIssueTestCatalog.modulesFor(module.id))
        }
        assertEquals("四小窗与交换",StageIssueTestCatalog.find("windows").name)
        assertEquals(setOf("layout"),StageIssueTestCatalog.modulesFor("windows"))
        assertTrue(StageIssueTestCatalog.modulesFor("other").isEmpty())
    }
}

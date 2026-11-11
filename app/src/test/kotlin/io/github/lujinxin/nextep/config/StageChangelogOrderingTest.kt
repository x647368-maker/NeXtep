package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageChangelogOrderingTest {
    private fun item(v: String, label: String = v) =
        StageChange(v, label, "记录", listOf("说明"))

    @Test fun currentFirstHistorySortedSemanticallyAndNoDuplicates() {
        val result = StageChangelogOrdering.merge(
            item("0.9.19-dev", "当前真实说明"),
            listOf(item("0.9.17-dev", "旧归档"), item("0.9.9", "旧归档"), item("0.9.18-dev", "旧归档")),
            listOf(item("0.9.18-dev", "人工核对"), item("0.9.16-dev"), item("0.9.9", "人工核对"))
        )
        assertEquals(listOf("0.9.19-dev", "0.9.18-dev", "0.9.17-dev", "0.9.16-dev", "0.9.9"),
            result.map { it.version })
        assertEquals("人工核对", result.first { it.version == "0.9.18-dev" }.title)
        assertEquals("人工核对", result.first { it.version == "0.9.9" }.title)
        assertEquals("当前真实说明", result.first().title)
    }

    @Test fun installedBuildOverridesCuratedEntryAndOldRangeGoesLast() {
        val current = item("0.9.18-dev", "构建内说明")
        val result = StageChangelogOrdering.merge(current,
            listOf(item("0.9.17-dev")), listOf(item("0.9.18-dev", "人工核对"), item("0.1–0.4")))
        assertEquals("构建内说明", result.first().title)
        assertTrue(result.last().version.contains("0.1"))
    }
}

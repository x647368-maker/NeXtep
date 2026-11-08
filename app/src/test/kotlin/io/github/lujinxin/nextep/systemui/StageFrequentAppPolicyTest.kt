package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageFrequentAppPolicyTest {
    private val now = 2_000_000_000_000L
    @Test fun realUsageRanksRecentFrequentAppsAndCapsQuickRowAtFive() {
        val keys = (0..8).map { "0:app$it" }
        val usage = keys.mapIndexed { i,k -> StageFrequentAppPolicy.Usage(k,now - i * 3600000L, selections = if (i==3) 50 else 0) }
        val chosen = StageFrequentAppPolicy.select(keys,usage,now)
        assertEquals(5,chosen.size); assertEquals("0:app3",chosen.first())
        assertTrue(chosen.indexOf("0:app0") < chosen.indexOf("0:app4"))
    }
    @Test fun removedAppsAreExcludedAndProfilesAreSeparateAndDuplicatesDoNotInflateUsage() {
        val a = StageFrequentAppPolicy.Usage("0:same",now,60000,5)
        val clone = StageFrequentAppPolicy.Usage("999:same",now-1000)
        val result = StageFrequentAppPolicy.select(listOf(a.key,clone.key),listOf(a,a,clone,StageFrequentAppPolicy.Usage("removed",now)),now)
        assertEquals(listOf(a.key,clone.key),result)
    }
    @Test fun noUsageProducesNoInventedFavoritesAndOldUsageDecays() {
        assertTrue(StageFrequentAppPolicy.select(listOf("a"),emptyList(),now).isEmpty())
        val records = listOf(StageFrequentAppPolicy.Usage("old",now - 30L*24*3600000,999999999,1000), StageFrequentAppPolicy.Usage("new",now))
        assertEquals("new",StageFrequentAppPolicy.select(listOf("old","new"),records,now).first())
    }
}

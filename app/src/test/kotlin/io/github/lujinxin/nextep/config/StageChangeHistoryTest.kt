package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageChangeHistoryTest {
    @Test fun versionsAreUniqueAndEntriesHaveContent() {
        val entries = StageChangeHistory.entries
        assertTrue(entries.size >= 19)
        assertEquals(entries.size, entries.map { it.version }.toSet().size)
        entries.forEach {
            assertFalse(it.version.isBlank())
            assertFalse(it.title.isBlank())
            assertFalse(it.status.isBlank())
            assertTrue(it.highlights.isNotEmpty())
        }
    }

    @Test fun allPointNineMilestonesAreRecorded() {
        for (minor in 0..18) {
            assertTrue("missing version 0.9.$minor", StageChangeHistory.entries.any {
                it.version == "0.9.$minor" || it.version == "0.9.$minor-dev"
            })
        }
    }

    @Test fun noFalseClaimForUnverifiedOrFailedVersions() {
        val entries = StageChangeHistory.entries
        assertEquals("0.9.18-dev", entries.first().version)
        val v18 = entries.single { it.version == "0.9.18-dev" }
        assertTrue(v18.status.contains("未解决"))
        assertTrue(v18.caution.contains("视觉回归失败"))
        val v17 = entries.single { it.version == "0.9.17-dev" }
        assertTrue(v17.caution.contains("状态栏遮挡"))
        val v16 = entries.single { it.version == "0.9.16-dev" }
        assertTrue(v16.status.contains("待真机验收"))
        assertTrue(v16.caution.contains("拖动交换"))
        assertTrue(v16.caution.contains("省电"))
        assertTrue(entries.single { it.version == "0.9.15-dev" }.caution.contains("形变"))
        assertTrue(entries.single { it.version == "0.9.9" }.status.contains("未通过"))
        assertTrue(entries.single { it.version == "0.9.8" }.status.contains("真机"))
    }
}

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
        for (minor in 0..16) {
            assertTrue("missing version 0.9.$minor", StageChangeHistory.entries.any {
                it.version == "0.9.$minor" || it.version == "0.9.$minor-dev"
            })
        }
    }

    @Test fun noFalseClaimForUnverifiedOrFailedVersions() {
        val entries = StageChangeHistory.entries
        assertEquals("0.9.16-dev", entries.first().version)
        assertTrue(entries.first().status.contains("待真机验收"))
        assertTrue(entries.first().caution.contains("拖动交换"))
        assertTrue(entries.first().caution.contains("省电"))
        assertTrue(entries.single { it.version == "0.9.15-dev" }.caution.contains("形变"))
        assertTrue(entries.single { it.version == "0.9.9" }.status.contains("未通过"))
        assertTrue(entries.single { it.version == "0.9.8" }.status.contains("真机"))
    }
}

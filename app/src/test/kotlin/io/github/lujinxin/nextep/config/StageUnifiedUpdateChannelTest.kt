package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test

class StageUnifiedUpdateChannelTest {
    @Test fun priorStableAndDevUsersBothReceiveVerifiedNewerBuilds() {
        for (legacy in listOf(null, "STABLE", "DEV", "invalid")) {
            assertEquals(UpdatePolicy.Channel.DEV,StageUnifiedUpdateChannel.fromLegacySelection(legacy))
        }
    }
    @Test fun legacyDeviceCanUpgradeAcrossFormerChannelBoundary() {
        val code=49L
        val meta=UpdatePolicy.Metadata("0.9.34",code,UpdatePolicy.Channel.STABLE,
            "a".repeat(64),26500000L,"AtomicWorkbench-v0.9.34-49-SIGNED.apk")
        assertTrue(UpdatePolicy.eligible(meta,48L,StageUnifiedUpdateChannel.fromLegacySelection("STABLE")))
        assertFalse(UpdatePolicy.eligible(meta,49L,StageUnifiedUpdateChannel.fromLegacySelection("DEV")))
    }
}

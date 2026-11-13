package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageGpuCoverPolicyTest {
    @Test fun stationaryOccludersSitBelowBothTaskMirrors() {
        assertTrue(StageGpuCoverPolicy.correctOrder())
        assertTrue(StageGpuCoverPolicy.SOURCE_COVER_LAYER <
            StageGpuCoverPolicy.SOURCE_MIRROR_LAYER)
        assertTrue(StageGpuCoverPolicy.MAIN_COVER_LAYER <
            StageGpuCoverPolicy.MAIN_MIRROR_LAYER)
    }
    @Test fun rejectSubwindowAndOutOfBoundsMasksBeforeTaskMutation() {
        assertTrue(StageGpuCoverPolicy.fits(40,80,250,470,1080,2400))
        assertTrue(StageGpuCoverPolicy.fits(300,90,950,2200,1080,2400))
        assertFalse(StageGpuCoverPolicy.fits(-1,80,250,470,1080,2400))
        assertFalse(StageGpuCoverPolicy.fits(40,80,1180,470,1080,2400))
        assertFalse(StageGpuCoverPolicy.fits(40,80,250,2500,1080,2400))
        assertFalse(StageGpuCoverPolicy.fits(40,80,40,470,1080,2400))
    }
}

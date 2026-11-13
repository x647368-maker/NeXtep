package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageSwapTapLatencyPolicyTest {
    @Test fun genuineOccupiedPhasedTapStartsMigrationWithoutBitmapPreview() {
        assertTrue(StageSwapTapLatencyPolicy.useImmediatePhasedPath(true,true,true))
    }
    @Test fun dragHomeAndLegacyPathsStillUseFrozenOriginalControllers() {
        assertFalse(StageSwapTapLatencyPolicy.useImmediatePhasedPath(false,true,true))
        assertFalse(StageSwapTapLatencyPolicy.useImmediatePhasedPath(true,false,true))
        assertFalse(StageSwapTapLatencyPolicy.useImmediatePhasedPath(true,true,false))
        assertFalse(StageSwapTapLatencyPolicy.useImmediatePhasedPath(false,false,false))
    }
}

package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure timing contract: a frozen preview has no reason to wait for its invisible animation. */
class StageSwapTimingPolicyTest {
    @Test fun stationaryTapUsesNextFrameInsteadOfConfiguredAnimationHold() {
        for (ms in listOf(100, 200, 300, 600, 1000)) {
            assertEquals(1L, StageSwapTimingPolicy.previewDurationMs(ms, true, false))
        }
    }

    @Test fun draggingRetainsUserConfiguredDuration() {
        assertEquals(200L, StageSwapTimingPolicy.previewDurationMs(200, false, false))
        assertEquals(100L, StageSwapTimingPolicy.previewDurationMs(80, false, false))
        assertEquals(1000L, StageSwapTimingPolicy.previewDurationMs(1600, false, false))
    }

    @Test fun returningHomeRetainsWallpaperTransitionDuration() {
        assertEquals(200L, StageSwapTimingPolicy.previewDurationMs(200, true, true))
        assertEquals(300L, StageSwapTimingPolicy.previewDurationMs(300, false, true))
    }
}

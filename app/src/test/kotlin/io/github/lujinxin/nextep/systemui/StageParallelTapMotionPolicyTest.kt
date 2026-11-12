package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageParallelTapMotionPolicyTest {
    @Test fun shortBoundedVisualAnimation() {
        assertEquals(280L, StageParallelTapMotionPolicy.durationMs(80))
        assertEquals(280L, StageParallelTapMotionPolicy.durationMs(200))
        assertEquals(300L, StageParallelTapMotionPolicy.durationMs(300))
        assertEquals(360L, StageParallelTapMotionPolicy.durationMs(1000))
    }
    @Test fun fullMotionOnlyForSafeTap() {
        assertTrue(StageParallelTapMotionPolicy.concurrent(true,false,true,true))
        assertFalse(StageParallelTapMotionPolicy.concurrent(true,true,true,true))
        assertFalse(StageParallelTapMotionPolicy.concurrent(false,false,true,true))
        assertFalse(StageParallelTapMotionPolicy.concurrent(true,false,false,true))
        assertFalse(StageParallelTapMotionPolicy.concurrent(true,false,true,false))
    }
    @Test fun handoffWaitsForBothButNeverDelaysStartingTheMove() {
        assertFalse(StageParallelTapMotionPolicy.canHandoff(false,true))
        assertFalse(StageParallelTapMotionPolicy.canHandoff(true,false))
        assertFalse(StageParallelTapMotionPolicy.canHandoff(false,false))
        assertTrue(StageParallelTapMotionPolicy.canHandoff(true,true))
    }
}

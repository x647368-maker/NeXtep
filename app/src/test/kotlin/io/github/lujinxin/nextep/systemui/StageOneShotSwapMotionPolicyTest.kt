package io.github.x647368maker.atomicworkbench.systemui
import org.junit.Assert.*
import org.junit.Test

class StageOneShotSwapMotionPolicyTest {
    @Test fun compositorCueIsBoundedAndSmall() {
        assertEquals(160L,StageOneShotSwapMotionPolicy.TOTAL_MS)
        assertTrue(StageOneShotSwapMotionPolicy.PEEK_SCALE<1.05f)
    }
    @Test fun noIdleOrPowerSaveAnimation() {
        assertTrue(StageOneShotSwapMotionPolicy.allowed(true,false,true,120,220))
        assertFalse(StageOneShotSwapMotionPolicy.allowed(false,false,true,120,220))
        assertFalse(StageOneShotSwapMotionPolicy.allowed(true,true,true,120,220))
        assertFalse(StageOneShotSwapMotionPolicy.allowed(true,false,false,120,220))
        assertFalse(StageOneShotSwapMotionPolicy.allowed(true,false,true,0,220))
    }
}

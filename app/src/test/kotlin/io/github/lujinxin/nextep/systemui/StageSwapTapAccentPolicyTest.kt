package io.github.x647368maker.atomicworkbench.systemui
import org.junit.Assert.*
import org.junit.Test

class StageSwapTapAccentPolicyTest {
    @Test fun runsOnlyForUserTapWithAnimationsEnabled() {
        assertTrue(StageSwapTapAccentPolicy.shouldAnimate(true,false,true))
        assertFalse(StageSwapTapAccentPolicy.shouldAnimate(false,false,true))
        assertFalse(StageSwapTapAccentPolicy.shouldAnimate(true,true,true))
        assertFalse(StageSwapTapAccentPolicy.shouldAnimate(true,false,false))
    }
    @Test fun noUnboundedRenderingOrOpaqueOverlay() {
        assertTrue(StageSwapTapAccentPolicy.DURATION_MS in 80L..180L)
        assertEquals(0,StageSwapTapAccentPolicy.opacity(0f))
        assertEquals(96,StageSwapTapAccentPolicy.opacity(.5f))
        assertEquals(0,StageSwapTapAccentPolicy.opacity(1f))
    }
}

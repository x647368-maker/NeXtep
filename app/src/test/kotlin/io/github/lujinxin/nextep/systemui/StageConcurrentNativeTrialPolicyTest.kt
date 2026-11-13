package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageConcurrentNativeTrialPolicyTest {
    @Test fun existingPhysicalWindowAllowsNoNewViewRoot() {
        StageConcurrentNativeTrialPolicy.resetForTests()
        for((w,h) in listOf(1140 to 2616, 2248 to 2480, 2560 to 1600))
            assertTrue(StageConcurrentNativeTrialPolicy.fullScreenHost(0,0,w,h,w,h))
        assertFalse(StageConcurrentNativeTrialPolicy.fullScreenHost(0,85,1080,2300,1080,2400))
        assertFalse(StageConcurrentNativeTrialPolicy.fullScreenHost(0,0,900,2400,1080,2400))
    }
    @Test fun trialAlwaysGatedByVisibilityAndSaver() {
        StageConcurrentNativeTrialPolicy.resetForTests()
        assertEquals(165L, StageConcurrentNativeTrialPolicy.DURATION_MS)
        assertTrue(StageConcurrentNativeTrialPolicy.allowed(true,true,false))
        assertFalse(StageConcurrentNativeTrialPolicy.allowed(false,true,false))
        assertFalse(StageConcurrentNativeTrialPolicy.allowed(true,false,false))
        assertFalse(StageConcurrentNativeTrialPolicy.allowed(true,true,true))
    }
    @Test fun slowPreparationImmediatelyDisablesSubsequentTrials() {
        StageConcurrentNativeTrialPolicy.resetForTests()
        assertTrue(StageConcurrentNativeTrialPolicy.acceptPrepareTime(12))
        assertFalse(StageConcurrentNativeTrialPolicy.acceptPrepareTime(13))
        assertFalse(StageConcurrentNativeTrialPolicy.allowed(true,true,false))
        StageConcurrentNativeTrialPolicy.resetForTests()
        StageConcurrentNativeTrialPolicy.disableForSession("OEM")
        assertFalse(StageConcurrentNativeTrialPolicy.allowed(true,true,false))
        StageConcurrentNativeTrialPolicy.resetForTests()
    }
}

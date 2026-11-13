package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageNativeSwapFullscreenHostTest {
    @Test fun oldMainOnlyWindowCannotPresentTwoWayCardFlight() {
        // Historical PR49 bug: contentWidth x contentHeight host cannot draw
        // a card outside it. This test must reject exactly that geometry.
        assertFalse(StageNativeSwapFullscreenHost.coversDisplay(
            230,110,850,2250,1080,2400))
    }
    @Test fun trueScreenWidePhysicalRootAcceptsPhoneFoldAndTablet() {
        val devices=listOf(1080 to 2400,2248 to 2480,2560 to 1600)
        for ((w,h) in devices)
            assertTrue(StageNativeSwapFullscreenHost.coversDisplay(0,0,w,h,w,h))
    }
    @Test fun cutoutOrStatusBarShiftMustNotSilentlyReportSuccess() {
        assertFalse(StageNativeSwapFullscreenHost.coversDisplay(0,50,1080,2350,1080,2400))
        assertFalse(StageNativeSwapFullscreenHost.coversDisplay(0,0,1079,2400,1080,2400))
        assertFalse(StageNativeSwapFullscreenHost.coversDisplay(0,0,1080,2399,1080,2400))
    }
}

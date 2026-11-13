package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertFalse
import org.junit.Test

class StageNativeSwapPerformanceGateTest {
    @Test fun nativeTwoMirrorPathIsDisabledAfterRealDeviceRegression() {
        assertFalse(StageNativeSwapPerformanceGate.enabledByDefault())
    }
}

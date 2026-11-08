package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageBackdropReusePolicyTest {
    @Test fun closingAddingAndCollapsingWindowsCanKeepMatchingWallpaperBuffers() {
        repeat(8) { assertTrue(StageBackdropReusePolicy.canReuse(true,1264,2780,0,1264,2780,0)) }
    }
    @Test fun missingChangedOrRotatedBuffersRequireRecapture() {
        assertFalse(StageBackdropReusePolicy.canReuse(false,1264,2780,0,1264,2780,0))
        assertFalse(StageBackdropReusePolicy.canReuse(true,0,0,0,1264,2780,0))
        assertFalse(StageBackdropReusePolicy.canReuse(true,1264,2780,0,2780,1264,1))
        assertFalse(StageBackdropReusePolicy.canReuse(true,1264,2780,0,1264,2780,2))
    }
}

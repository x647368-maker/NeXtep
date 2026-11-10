package io.github.x647368maker.atomicworkbench.systemserver

import org.junit.Assert.*
import org.junit.Test

class StageOrientationHookScopeTest {
    private class DisplayInfo(val displayId: Int, val name: String)
    private class TestDisplay(private val details: DisplayInfo) {
        fun getDisplayId() = details.displayId
        fun getDisplayInfo() = details
    }
    private class TestTask(private val mode: Int) { fun getWindowingMode() = mode }
    private class TestApplicationInfo(val category: Int)
    private class TestActivityInfo(val applicationInfo: TestApplicationInfo)
    private class TestActivity(
        private val display: TestDisplay, private val orientation: Int,
        private val task: TestTask, val info: TestActivityInfo
    ) {
        fun getDisplayContent() = display
        fun getRequestedOrientation() = orientation
        fun getTask() = task
    }

    private fun snap(displayId: Int, displayName: String, requested: Int, category: Int, mode: Int) =
        StageOrientationHookScope.snapshot(TestActivity(
            TestDisplay(DisplayInfo(displayId, displayName)), requested,
            TestTask(mode), TestActivityInfo(TestApplicationInfo(category))
        ))

    private fun assertSystemControlled(s: StageOrientationHookScope.Snapshot) {
        assertNull(StageOrientationHookScope.aspectOverride(s, true, 2.1f))
        assertFalse(StageOrientationHookScope.shouldBypassSizeCompat(s))
        assertFalse(StageOrientationHookScope.canRewriteMainLandscape(s))
    }

    @Test fun neverModifyGamesOrColorOsParallelTasks() {
        for (orientation in listOf(0, 1, 6, 8, 11, -1)) {
            for (mode in listOf(1, 2, 5)) {
                for (displayId in listOf(0, 3, 11)) {
                    val name = when (displayId) {
                        0 -> "Built-in display"
                        3 -> "NeXtep-slot-2"
                        else -> "ColorOS parallel"
                    }
                    assertSystemControlled(snap(displayId, name, orientation, 0, mode))
                }
            }
        }
        for (category in listOf(-1, 1, 4)) {
            for (mode in listOf(2, 3, 5, 6)) {
                for (displayId in listOf(0, 3, 11)) {
                    val name = if (displayId == 3) "NeXtep-slot-1" else "ColorOS parallel"
                    assertSystemControlled(snap(displayId, name, 1, category, mode))
                }
            }
        }
    }

    @Test fun familiarFullscreenPortraitPolicyIsRetained() {
        val main = snap(0, "Built-in", 1, -1, 1)
        assertEquals(2.1f, StageOrientationHookScope.aspectOverride(main, true, 2.1f)!!, 0.0001f)
        assertTrue(StageOrientationHookScope.shouldBypassSizeCompat(main))
        assertTrue(StageOrientationHookScope.canRewriteMainLandscape(main))
        val slot = snap(3, "NeXtep-slot-2", 1, -1, 1)
        assertEquals(1120f / 720f, StageOrientationHookScope.aspectOverride(slot, true, 2.1f)!!, 0.0001f)
        assertEquals(2.1f, StageOrientationHookScope.aspectOverride(slot, false, 2.1f)!!, 0.0001f)
        assertTrue(StageOrientationHookScope.shouldBypassSizeCompat(slot))
    }

    @Test fun unknownDisplayAndLandscapeFailOpen() {
        assertSystemControlled(StageOrientationHookScope.snapshot(null))
        assertSystemControlled(snap(11, "ColorOS parallel", 1, -1, 1))
        assertSystemControlled(snap(3, "NeXtep-slot-5", 1, -1, 1))
        assertSystemControlled(snap(3, "Other-slot-2", 1, -1, 1))
        assertSystemControlled(snap(3, "NeXtep-slot-2", 0, -1, 1))
        assertNull(StageOrientationHookScope.aspectOverride(snap(3,"NeXtep-slot-2",1,-1,1),true,Float.NaN))
    }
}

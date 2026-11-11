package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageNativeParallelDiagnosticsTest {
    private fun scene(
        width: Int = 2248,
        height: Int = 2480,
        dpi: Int = 420,
        task: StageNativeParallelDiagnostics.Task? = StageNativeParallelDiagnostics.Task(
            23, "test.two.pane", 0, 1, false, "0,0-2248,2480"),
        fitted: Int? = 23,
    ) = StageNativeParallelDiagnostics.Scene(
        screenWidth = width, screenHeight = height, densityDpi = dpi,
        railAtTop = false, controlHeight = 148, sidebarWidth = 460,
        sidebarHeight = 2200, mainLeft = 470, mainTop = 150,
        mainWidth = 1780, mainHeight = 2200,
        fittedMainId = fitted, tasks = listOfNotNull(task),
    )

    @Test fun nearSquareRuleDoesNotAffectOrdinaryPhoneOrY700() {
        assertTrue(StageNativeParallelDiagnostics.nearSquareExpanded(scene()))
        assertFalse(StageNativeParallelDiagnostics.nearSquareExpanded(scene(1080, 2400, 440)))
        assertFalse(StageNativeParallelDiagnostics.nearSquareExpanded(scene(2560, 1600, 320)))
        assertFalse(StageNativeParallelDiagnostics.nearSquareExpanded(scene(0, 1600, 320)))
    }

    @Test fun distinguishesNativeAndUnfittedWindowsWithoutOverridingThem() {
        assertEquals("FULLSCREEN_FITTED_OEM_PARALLEL_UNKNOWN", StageNativeParallelDiagnostics.classification(scene()))
        assertEquals("FULLSCREEN_NOT_FITTED", StageNativeParallelDiagnostics.classification(scene(fitted = null)))
        assertEquals("OPLUS_NATIVE_WINDOWED", StageNativeParallelDiagnostics.classification(
            scene(task = StageNativeParallelDiagnostics.Task(23,"oplus",0,1,true,"0,0-2248,2480"))))
        assertEquals("ANDROID_WINDOWED_MODE_6", StageNativeParallelDiagnostics.classification(
            scene(task = StageNativeParallelDiagnostics.Task(23,"split",0,6,false,"0,0-1100,2480"))))
        assertEquals("NO_PHYSICAL_TASK", StageNativeParallelDiagnostics.classification(scene(task = null)))
    }

    @Test fun reportsPhysicalCanvasAndMainRegionSoFoldableOverlapCanBeReproduced() {
        val report = StageNativeParallelDiagnostics.report(scene(fitted = null))
        assertTrue(report.contains("physical=2248x2480"))
        assertTrue(report.contains("mainBounds=470,150 1780x2200"))
        assertTrue(report.contains("FULLSCREEN_NOT_FITTED"))
        assertTrue(report.contains("test.two.pane"))
    }
}

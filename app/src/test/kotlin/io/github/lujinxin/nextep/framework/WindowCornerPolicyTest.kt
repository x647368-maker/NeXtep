package io.github.x647368maker.atomicworkbench.framework

import org.junit.Assert.*
import org.junit.Test

class WindowCornerPolicyTest {
    @Test fun smallAndMainWindowsUseSoftRadiusWithoutCapsuleEnds() {
        assertEquals(22.4f, WindowCornerPolicy.radius(280f, 440f, 3.2f), .001f)
        assertEquals(51.2f, WindowCornerPolicy.radius(900f, 1900f, 3.2f), .001f)
    }
    @Test fun nativeTaskRadiusMatchesTheVisibleCardRadiusAtEveryWorkspaceScale() {
        for (scale in listOf(.4f,.55f,.72f,.9f)) {
            val source = WindowCornerPolicy.sourceRadius(scale,scale,1264,2780,3.2f,true)
            assertEquals(WindowCornerPolicy.radius(1264*scale,2780*scale,3.2f), source*scale, .001f)
        }
    }
    @Test fun restoringFullscreenAndUnscaledSlotSurfacesClearWorkspaceCorners() {
        assertEquals(0f,WindowCornerPolicy.sourceRadius(1f,1f,1264,2780,3.2f,true),0f)
        assertEquals(0f,WindowCornerPolicy.sourceRadius(.7f,.7f,1264,2780,3.2f,false),0f)
        assertEquals(0f,WindowCornerPolicy.sourceRadius(0f,0f,1264,2780,3.2f,true),0f)
        val radii = listOf(.9f,.94f,.97f,.99f,1f).map { s -> WindowCornerPolicy.sourceRadius(s,s,1264,2780,3.2f,true)*s }
        radii.zipWithNext().forEach { (a,b) -> assertTrue(a >= b) }
    }
}

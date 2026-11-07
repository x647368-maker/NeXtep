package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotGeometryTest {
    @Test
    fun allSlotsReusePhysicalPortraitCanvasAcrossRotation() {
        val portrait = SlotGeometry.forRecordedDisplay(1080, 2354, 480)
        val landscape = SlotGeometry.forRecordedDisplay(2354, 1080, 480)
        assertEquals(portrait, landscape)
        assertEquals(1080, portrait.width)
        assertEquals(2354, portrait.height)
        assertEquals(480, portrait.densityDpi)
    }
}

package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test

class SlotTouchProjectionTest {
    @Test fun compactViewportMapsEdgesCenterAndLetterboxWithoutStretch() {
        val p = SlotTouchProjection(300, 500, 720, 1120)
        assertEquals(360f, p.sourceX(150f), .001f)
        assertEquals(560f, p.sourceY(250f), .001f)
        assertFalse(p.contains(150f, 0f))
        assertTrue(p.contains(150f, 250f))
        assertEquals(0f, p.sourceX(p.left), .001f)
        assertEquals(0f, p.sourceY(p.top), .001f)
        assertEquals(720f, p.sourceX(p.left + 720 * p.scale), .001f)
    }
    @Test fun wholePortraitAndCompactSourcesMapUniformlyForDifferentRailSizes() {
        for ((w,h) in listOf(720 to 1120, 1264 to 2780)) for ((vw,vh) in listOf(284 to 442, 280 to 570, 160 to 247)) {
            val p = SlotTouchProjection(vw,vh,w,h)
            assertEquals(w / 2f,p.sourceX(vw / 2f),.001f)
            assertEquals(h / 2f,p.sourceY(vh / 2f),.001f)
        }
    }
}

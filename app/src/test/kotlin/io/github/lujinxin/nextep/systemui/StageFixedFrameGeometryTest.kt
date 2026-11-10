package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StageFixedFrameGeometryTest {
    @Test fun previewAlwaysUsesUniformScaleAndStaysWithinItsOriginalFrame() {
        val shapes = listOf(
            intArrayOf(1920,1080,350,700,0,0),
            intArrayOf(1080,1920,350,700,5,9),
            intArrayOf(720,1560,300,654,0,0),
            intArrayOf(300,654,1000,1620,300,100),
            intArrayOf(800,800,300,300,0,0),
            intArrayOf(1080,2400,1272,2772,0,0),
        )
        for (v in shapes) {
            val (bw,bh,fw,fh,ox,oy) = v.toList()
            val fit = StageFixedFrameGeometry.fit(bw,bh,ox.toFloat(),oy.toFloat(),(ox+fw).toFloat(),(oy+fh).toFloat())
            assertEquals(fit.width/bw, fit.height/bh, 0.001f)
            assertTrue(fit.left >= ox - .001f && fit.right <= ox + fw + .001f)
            assertTrue(fit.top >= oy - .001f && fit.bottom <= oy + fh + .001f)
        }
    }

    @Test fun invalidBitmapOrWindowCannotProduceANonUniformTransform() {
        val cases = listOf(Pair(0,200), Pair(200,0))
        for ((w,h) in cases) {
            val r = runCatching { StageFixedFrameGeometry.fit(w,h,0f,0f,300f,600f) }
            assertTrue(r.isFailure)
        }
        assertTrue(runCatching { StageFixedFrameGeometry.fit(200,300,0f,0f,0f,600f) }.isFailure)
        assertTrue(runCatching { StageFixedFrameGeometry.fit(200,300,Float.NaN,0f,300f,600f) }.isFailure)
    }

    @Test fun letterboxingKeepsContentCenteredWithoutCropping() {
        val box = StageFixedFrameGeometry.fit(1920,1080,50f,100f,350f,700f)
        assertEquals(50f, box.left, .001f)
        assertEquals(350f, box.right, .001f)
        assertEquals(400f, (box.top+box.bottom)/2f, .001f)
        assertEquals(300f/1920f, box.height/1080f, .001f)
    }
}

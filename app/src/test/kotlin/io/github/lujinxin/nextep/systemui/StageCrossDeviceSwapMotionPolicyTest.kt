package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One motion strategy for all supported display shapes. We never select
 * by OEM name, ported ROM identity, Android rotation enum or device whitelist.
 */
class StageCrossDeviceSwapMotionPolicyTest {
    private fun b(left: Float, top: Float, right: Float, bottom: Float) =
        StageCrossDeviceSwapMotionPolicy.Bounds(left, top, right, bottom)

    @Test fun allPhoneFoldAndTabletOrientationsGetRealTwoWayTapMotion() {
        val pairs = listOf(
            // Ace 7 Pro portrait: left card -> large task
            b(20f,300f,280f,770f) to b(310f,170f,1110f,2430f),
            // phone landscape: compact side rail to wide main
            b(10f,170f,400f,440f) to b(450f,100f,2600f,1080f),
            // N5 inner display, left rail
            b(40f,230f,480f,930f) to b(520f,140f,2200f,2340f),
            // N5 outer folded portrait
            b(10f,280f,265f,680f) to b(285f,170f,1080f,2380f),
            // Lenovo Y700 native landscape
            b(36f,260f,585f,565f) to b(625f,180f,2510f,1500f),
            // Lenovo Y700 native portrait
            b(35f,200f,330f,680f) to b(365f,140f,1580f,2460f),
            // a top-row workbench on any hardware
            b(30f,150f,320f,360f) to b(65f,380f,1460f,1550f),
        )
        for ((source, target) in pairs) {
            assertTrue(StageCrossDeviceSwapMotionPolicy.canAnimateTap(source,target,true))
            assertTrue(StageCrossDeviceSwapMotionPolicy.canAnimateTap(target,source,true))
        }
    }

    @Test fun unsafeGeometryOrUncapturedRealFrameUsesExistingStableFallback() {
        val valid = b(20f, 30f, 120f, 190f)
        assertFalse(StageCrossDeviceSwapMotionPolicy.canAnimateTap(valid,valid,false))
        assertFalse(StageCrossDeviceSwapMotionPolicy.canAnimateTap(b(20f,30f,20f,110f),valid,true))
        assertFalse(StageCrossDeviceSwapMotionPolicy.canAnimateTap(valid,b(0f,0f,3f,3f),true))
        assertFalse(StageCrossDeviceSwapMotionPolicy.canAnimateTap(
            b(Float.NaN,1f,100f,120f),valid,true))
        assertFalse(StageCrossDeviceSwapMotionPolicy.canAnimateTap(
            valid,b(0f,0f,Float.POSITIVE_INFINITY,120f),true))
    }

    @Test fun liveMotionUsesConfiguredTimeWhileOldFrozenFallbackStillFast() {
        assertEquals(200L, StageSwapTimingPolicy.previewDurationMs(200,false,false))
        assertEquals(300L, StageSwapTimingPolicy.previewDurationMs(300,false,false))
        assertEquals(1L, StageSwapTimingPolicy.previewDurationMs(300,true,false))
    }

    @Test fun interpolatedContentFitNeverStretchesImage() {
        for ((left, top, right, bottom) in listOf(
            listOf(100f,100f,350f,480f), listOf(300f,230f,1900f,1380f),
            listOf(100f,120f,800f,580f), listOf(20f,200f,400f,840f)
        )) {
            for ((width,height) in listOf(720 to 1280, 1920 to 1080, 1280 to 1280)) {
                val fitted=StageFixedFrameGeometry.fit(width,height,left,top,right,bottom)
                assertTrue(fitted.left>=left-.01f && fitted.right<=right+.01f)
                assertTrue(fitted.top>=top-.01f && fitted.bottom<=bottom+.01f)
                assertEquals(width.toFloat()/height,
                    fitted.width/fitted.height,.001f)
            }
        }
    }
}

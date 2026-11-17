package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagePreviewFillPolicyTest {
    @Test fun letterboxPolicyDoesNotStretchPortraitIntoLandscape() {
        val result=StagePreviewFillPolicy.frame(100,200,10f,20f,510f,320f)
        assertTrue(result.hasUncoveredArea)
        assertTrue(result.horizontalGap > 300f)
        assertEquals(0f,result.verticalGap,.001f)
        assertEquals(result.content.width/100f,result.content.height/200f,.001f)
    }

    @Test fun landscapeIntoPortraitUsesPreparedBacking() {
        val result=StagePreviewFillPolicy.frame(200,100,0f,0f,200f,600f)
        assertTrue(result.hasUncoveredArea)
        assertTrue(result.verticalGap > 400f)
        assertEquals(0f,result.horizontalGap,.001f)
    }

    @Test fun alignedFrameHasNoArtificialBars() {
        val result=StagePreviewFillPolicy.frame(100,200,0f,0f,300f,600f)
        assertFalse(result.hasUncoveredArea)
        assertEquals(0f,result.horizontalGap,.001f)
        assertEquals(0f,result.verticalGap,.001f)
    }

    @Test fun previewRequiresARealOpaqueCover() {
        assertFalse(StagePreviewFillPolicy.canStart(false,false))
        assertTrue(StagePreviewFillPolicy.canStart(true,false))
        assertTrue(StagePreviewFillPolicy.canStart(false,true))
        assertTrue(StagePreviewFillPolicy.canStart(true,true))
    }
}

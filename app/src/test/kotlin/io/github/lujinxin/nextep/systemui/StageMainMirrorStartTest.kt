package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageMainMirrorStartTest {
    private val g=StageLiveSurfaceFlightGeometry
    private val crop=StageLiveSurfaceFlightGeometry.Crop(0,0,1272,2772)
    private val visible=StageLiveSurfaceFlightGeometry.Box(318f,460f,1272f,2539f)
    @Test fun detachedUsesRealNonUnitSourceTransform() {
        val f=checkNotNull(g.mainMirrorStart(visible,crop,.75f,318f,460f,true))
        assertEquals(crop,f.crop)
        assertEquals(.75f,f.scale,0f)
        assertEquals(318f,f.x,0f)
        assertEquals(460f,f.y,0f)
        // A content point, not just a card edge.
        assertEquals(618f,400f*f.scale+f.x,0f)
        assertEquals(1060f,800f*f.scale+f.y,0f)
    }
    @Test fun retainedUsesProjectedRootAndDoesNotDoubleTransform() {
        val f=checkNotNull(g.mainMirrorStart(visible,crop,.75f,318f,460f,false))
        assertEquals(StageLiveSurfaceFlightGeometry.Crop(318,460,1272,2539),f.crop)
        assertEquals(1f,f.scale,0f)
        assertEquals(0f,f.x,0f)
        assertEquals(0f,f.y,0f)
        assertEquals(618f,(400f*.75f+318f)*f.scale+f.x,0f)
    }
    @Test fun rejectsWrongOrUnsettledSourceMapping() {
        assertNull(g.mainMirrorStart(visible,crop,1f,0f,0f,true))
        assertNull(g.mainMirrorStart(visible,crop,Float.NaN,318f,460f,false))
        assertNull(g.mainMirrorStart(visible,crop,.75f,340f,460f,true))
        assertNull(g.mainMirrorStart(visible,
            StageLiveSurfaceFlightGeometry.Crop(0,0,1272,2517),.75f,318f,460f,true))
    }
    @Test fun nonzeroLocalCropIsNotAssumedFullDisplay() {
        val local=StageLiveSurfaceFlightGeometry.Crop(100,200,900,1200)
        val box=StageLiveSurfaceFlightGeometry.Box(70f,130f,470f,630f)
        val f=checkNotNull(g.mainMirrorStart(box,local,.5f,20f,30f,true))
        assertEquals(local,f.crop)
        assertEquals(box,f.box)
    }
    @Test fun plannerMayNotChangeContentScaleOrAnchor() {
        val first=checkNotNull(g.mainMirrorStart(visible,crop,.75f,318f,460f,true))
        assertTrue(g.sameMirrorContent(first,first))
        assertFalse(g.sameMirrorContent(first,first.copy(scale=1f,x=0f,y=0f)))
        assertFalse(g.sameMirrorContent(first,first.copy(y=first.y+2f)))
        assertFalse(g.sameMirrorContent(first,first.copy(crop=StageLiveSurfaceFlightGeometry.Crop(1,0,1272,2772))))
    }
}

package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageMainDestinationCoordinateTest {
    private val p=StageHeldEndpointPolicy
    private fun fit(h:Int,detached:Boolean)=checkNotNull(p.mainDestinationFit(
        318f,460f,1272f,2539f,0,0,1272,h,.75f,318f,460f,detached))

    @Test fun detachedCopiesRealDestinationAffineInsteadOfRefitting() {
        for(h in listOf(2517,2772)) {
            val f=fit(h,true)
            assertEquals(.75f,f.scale,0f)
            assertEquals(318f,f.x,0f)
            assertEquals(460f,f.y,0f)
            assertEquals(0,f.left);assertEquals(0,f.top)
            assertEquals(1272,f.right);assertEquals(h,f.bottom)
            assertEquals(618f,400f*f.scale+f.x,0f)
            assertEquals(1060f,800f*f.scale+f.y,0f)
        }
    }

    @Test fun retainedDoesNotApplyProducerMapTwice() {
        for(h in listOf(2517,2772)) {
            val f=fit(h,false)
            assertEquals(1f,f.scale,0f)
            assertEquals(0f,f.x,0f);assertEquals(0f,f.y,0f)
            assertEquals(318,f.left);assertEquals(460,f.top)
            assertEquals(1272,f.right)
            assertEquals(kotlin.math.floor(460+h*.75).toInt(),f.bottom)
            assertEquals(618f,(400f*.75f+318f)*f.scale+f.x,0f)
            assertEquals(1060f,(800f*.75f+460f)*f.scale+f.y,0f)
        }
    }

    @Test fun clipUsesCorrectRootSpaceWithoutMovingContent() {
        val f=checkNotNull(p.mainDestinationFit(40f,60f,100f,120f,
            10,20,400,500,.5f,20f,30f,true))
        assertEquals(40,f.left);assertEquals(60,f.top)
        assertEquals(160,f.right);assertEquals(180,f.bottom)
        assertEquals(.5f,f.scale,0f)
        assertEquals(20f,f.x,0f);assertEquals(30f,f.y,0f)
        val r=checkNotNull(p.mainDestinationFit(40f,60f,100f,120f,
            10,20,400,500,.5f,20f,30f,false))
        assertEquals(40,r.left);assertEquals(60,r.top)
        assertEquals(100,r.right);assertEquals(120,r.bottom)
        assertEquals(1f,r.scale,0f)
    }

    @Test fun invalidOrDisjointMapRefused() {
        assertNull(p.mainDestinationFit(0f,0f,10f,10f,0,0,100,100,.75f,100f,100f,true))
        assertNull(p.mainDestinationFit(0f,0f,10f,10f,0,0,100,100,Float.NaN,0f,0f,false))
    }
}

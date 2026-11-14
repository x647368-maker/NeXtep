package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageHeldEndpointPolicyTest {
    private val p=StageHeldEndpointPolicy
    private fun inside(f:StageHeldEndpointPolicy.Fit,l:Float,t:Float,r:Float,b:Float) {
        val x1=f.x+f.left*f.scale
        val x2=f.x+f.right*f.scale
        val y1=f.y+f.top*f.scale
        val y2=f.y+f.bottom*f.scale
        assertTrue(x1>=l-.01f && x2<=r+.01f)
        assertTrue(y1>=t-.01f && y2<=b+.01f)
        assertEquals((l+r)*.5f,(x1+x2)*.5f,.01f)
        assertEquals((t+b)*.5f,(y1+y2)*.5f,.01f)
    }
    @Test fun promotedRetainedRootUsesNewPhysicalMainCoordinates() {
        val f=checkNotNull(p.fit(0f,460f,954f,2539f,
            0,0,1272,2772,.75f,0f,460f,false))
        assertEquals(0,f.left)
        assertEquals(460,f.top)
        assertEquals(954,f.right)
        assertEquals(2539,f.bottom)
        assertEquals(1f,f.scale,.001f)
        inside(f,0f,460f,954f,2539f)
    }
    @Test fun detachedRootKeepsLocalCoordinates() {
        val f=checkNotNull(p.fit(214f,821f,1058f,2660f,
            0,0,1272,2772,.6634199f,214f,821f,true))
        assertEquals(0,f.left)
        assertEquals(0,f.top)
        assertEquals(1272,f.right)
        assertEquals(2772,f.bottom)
        inside(f,214f,821f,1058f,2660f)
    }
    @Test fun demotedProducerUsesNewLocalCanvasAcrossDeviceShapes() {
        for (size in listOf(1272 to 2517,720 to 1120,1800 to 1200,1200 to 1800)) {
            for (box in listOf(floatArrayOf(17f,200f,267f,695f),
                floatArrayOf(300f,122f,650f,422f),floatArrayOf(1400f,210f,1720f,670f))) {
                val f=checkNotNull(p.fit(box[0],box[1],box[2],box[3],
                    0,0,size.first,size.second,1f,0f,0f,false))
                assertEquals(0,f.left)
                assertEquals(0,f.top)
                assertEquals(size.first,f.right)
                assertEquals(size.second,f.bottom)
                inside(f,box[0],box[1],box[2],box[3])
            }
        }
    }
    @Test fun invalidGeometryDoesNotWriteAnEndpoint() {
        assertNull(p.fit(0f,0f,0f,100f,0,0,720,1120,1f,0f,0f,false))
        assertNull(p.fit(0f,0f,100f,100f,0,0,1,1120,1f,0f,0f,false))
        assertNull(p.fit(0f,0f,100f,100f,0,0,720,1120,Float.NaN,0f,0f,false))
        assertNull(p.fit(0f,0f,100f,100f,0,0,720,1120,0f,0f,0f,false))
        assertNull(p.fit(0f,0f,100f,100f,0,0,720,1120,1f,Float.POSITIVE_INFINITY,0f,false))
    }
}

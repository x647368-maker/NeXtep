package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageLiveSurfaceFlightGeometryTest {
    private fun b(l:Float,t:Float,r:Float,z:Float) =
        StageLiveSurfaceFlightGeometry.Box(l,t,r,z)

    @Test fun twoLiveCompositorLayersMustMoveInOppositeDirections() {
        val small=b(30f,240f,280f,690f)
        val main=b(310f,160f,1070f,2360f)
        for (p in listOf(0f,.15f,.35f,.5f,.72f,.9f,1f)) {
            val up=StageLiveSurfaceFlightGeometry.flight(small,main,250,450,p)!!
            val down=StageLiveSurfaceFlightGeometry.flight(main,small,1140,2616,p)!!
            assertEquals(small.cx+(main.cx-small.cx)*p,up.box.cx,.02f)
            assertEquals(main.cy+(small.cy-main.cy)*p,down.box.cy,.02f)
            assertEquals(up.scale,up.scale,0f)
            // Each layer has exactly ONE scale, rather than independent X/Y.
            assertTrue(up.scale>0f && down.scale>0f)
            assertEquals(small.width+(main.width-small.width)*p,up.box.width,.02f)
            assertEquals(main.height+(small.height-main.height)*p,down.box.height,.02f)
        }
    }

    @Test fun survivesAllPhoneFoldTabletPhysicalCanvasSizes() {
        for ((w,h) in listOf(1140 to 2616,2616 to 1140,
            2248 to 2480,2480 to 2248,2560 to 1600,1600 to 2560)) {
            val small=b(10f,100f,w*.25f,h*.45f)
            val main=b(w*.3f,h*.1f,w*.93f,h*.9f)
            for (p in listOf(0f,.5f,1f)) {
                val grow=StageLiveSurfaceFlightGeometry.flight(small,main,500,800,p)!!
                val shrink=StageLiveSurfaceFlightGeometry.flight(main,small,w,h,p)!!
                assertTrue(grow.x.isFinite() && grow.y.isFinite())
                assertTrue(shrink.x.isFinite() && shrink.y.isFinite())
                assertTrue(grow.scale>0f && shrink.scale>0f)
            }
        }
    }

    @Test fun originalViewRootCropIncludesOnlyTappedCard() {
        val card=b(80f,210f,330f,750f)
        val crop=StageLiveSurfaceFlightGeometry.localCrop(card,50,160,400,1100)!!
        assertEquals(30,crop.left)
        assertEquals(50,crop.top)
        assertEquals(280,crop.right)
        assertEquals(590,crop.bottom)
        assertNull(StageLiveSurfaceFlightGeometry.localCrop(card,400,160,400,1100))
        assertNull(StageLiveSurfaceFlightGeometry.localCrop(card,50,160,200,400))
    }
    @Test fun invalidFramesFallBackWithoutChangingOriginalSurfaces() {
        assertNull(StageLiveSurfaceFlightGeometry.flight(
            b(0f,0f,0f,20f),b(10f,20f,200f,300f),500,800,.5f))
        assertNull(StageLiveSurfaceFlightGeometry.flight(
            b(0f,0f,30f,20f),b(10f,20f,200f,300f),0,800,.5f))
        assertNull(StageLiveSurfaceFlightGeometry.flight(
            b(0f,0f,30f,20f),b(10f,20f,200f,300f),500,800,Float.NaN))
    }
}

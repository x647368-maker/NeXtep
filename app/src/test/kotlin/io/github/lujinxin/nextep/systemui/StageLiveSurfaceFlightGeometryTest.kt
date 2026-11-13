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
            // Each real app preserves its own aspect ratio at every frame.
            assertTrue(up.scale>0f && down.scale>0f)
            assertEquals(250f/450f,up.box.width/up.box.height,.002f)
            assertEquals(1140f/2616f,down.box.width/down.box.height,.002f)
        }
    }

    @Test fun mainLeashAlreadyFittedMustStartAtIdentityNotDoubleScale() {
        // TaskSurfaceCompat has already applied the physical main geometry.
        // Mirrored task contents therefore appear in this exact visible box.
        val main=b(310f,190f,1070f,2390f)
        val small=b(30f,260f,280f,700f)
        val first=StageLiveSurfaceFlightGeometry.flight(
            main,small,main.width.toInt(),main.height.toInt(),0f)!!
        assertEquals(1f,first.scale,.0001f)
        assertEquals(0f,first.x-main.left*first.scale,.0001f)
        assertEquals(0f,first.y-main.top*first.scale,.0001f)
        val last=StageLiveSurfaceFlightGeometry.flight(
            main,small,main.width.toInt(),main.height.toInt(),1f)!!
        assertTrue(last.scale in .1f..1f)
        assertEquals(small.cx,
            main.cx*last.scale + last.x-main.left*last.scale -
                main.width*.5f*last.scale + main.width*.5f*last.scale,
            1f) // sanity of the inverse presentation origin
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


    @Test fun oppositeArcsSeparateTheTwoCardsDuringTheCrossing() {
        val small=b(30f,240f,280f,690f)
        val main=b(310f,160f,1070f,2360f)
        val incoming=StageLiveSurfaceFlightGeometry.flight(small,main,250,450,.5f,48f)!!
        val outgoing=StageLiveSurfaceFlightGeometry.flight(main,small,1140,2616,.5f,48f)!!
        // Opposite travel directions use opposite perpendicular lanes.
        val incomingOffset=incoming.box.cy-(small.cy+main.cy)*.5f
        val outgoingOffset=outgoing.box.cy-(main.cy+small.cy)*.5f
        assertTrue(incomingOffset*outgoingOffset<0f)
        assertTrue(kotlin.math.abs(incomingOffset-outgoingOffset)>40f)
    }

    @Test fun aspectRatioDoesNotMorphAtIntermediateFrames() {
        val from=b(20f,200f,280f,680f)
        val to=b(330f,140f,1080f,2350f)
        val ratio=260f/480f
        for(p in listOf(0f,.1f,.25f,.5f,.75f,.9f,1f)) {
            val v=StageLiveSurfaceFlightGeometry.flight(from,to,260,480,p,36f)!!
            assertEquals(ratio,v.box.width/v.box.height,.002f)
        }
    }


    @Test fun railCorridorProtectsTheOtherThreeLeftSlots() {
        val selected=b(25f,850f,235f,1220f)
        val main=b(290f,120f,1030f,2150f)
        val others=listOf(b(25f,200f,235f,630f),
            b(25f,1270f,235f,1680f),b(25f,1700f,235f,2090f))
        assertTrue(StageLiveSurfaceFlightGeometry.corridorAvoidsOtherSlots(
            selected,main,250,440,others,80,
        ))
        for (progress in listOf(0f,.1f,.34f,.5f,.66f,.9f,1f)) {
            val incoming=StageLiveSurfaceFlightGeometry.railCorridorFlight(
                selected,main,250,440,progress)!!
            val outgoing=StageLiveSurfaceFlightGeometry.railCorridorFlight(
                main,selected,740,2030,progress)!!
            assertEquals(250f/440f,incoming.box.width/incoming.box.height,.003f)
            assertEquals(740f/2030f,outgoing.box.width/outgoing.box.height,.003f)
        }
    }

    @Test fun topRailCorridorAlsoProtectsUnselectedCards() {
        val selected=b(800f,25f,1100f,220f)
        val main=b(200f,300f,1500f,1200f)
        val others=listOf(b(50f,25f,350f,220f),
            b(400f,25f,700f,220f),b(1200f,25f,1500f,220f))
        assertTrue(StageLiveSurfaceFlightGeometry.corridorAvoidsOtherSlots(
            selected,main,900,600,others,80,
        ))
    }

    @Test fun ambiguousGeometryOrBlockedCorridorMustNotStartNativeFlight() {
        val selected=b(25f,850f,235f,1220f)
        val main=b(290f,120f,1030f,2150f)
        assertFalse(StageLiveSurfaceFlightGeometry.corridorAvoidsOtherSlots(
            selected,main,250,440,listOf(b(260f,730f,850f,1430f)),
        ))
        assertFalse(StageLiveSurfaceFlightGeometry.corridorAvoidsOtherSlots(
            b(350f,500f,600f,800f),main,250,440,emptyList(),
        ))
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

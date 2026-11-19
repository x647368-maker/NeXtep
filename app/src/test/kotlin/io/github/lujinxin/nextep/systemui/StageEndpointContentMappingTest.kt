package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageEndpointContentMappingTest {
    private val m=StageEndpointContentMapping
    private val short=StageLiveSurfaceFlightGeometry.Crop(0,0,1272,2517)
    private val tall=StageLiveSurfaceFlightGeometry.Crop(0,0,1272,2772)
    private fun map(s:Float,x:Float,y:Float)=StageEndpointContentMapping.ContentMap(s,x,y)
    private fun box(l:Float,t:Float,r:Float,b:Float)=StageLiveSurfaceFlightGeometry.Box(l,t,r,b)
    private fun frame(c:StageLiveSurfaceFlightGeometry.Crop,s:Float,x:Float,y:Float)=
        StageLiveSurfaceFlightGeometry.MirrorFrame(s,x,y,c,box(c.left*s+x,c.top*s+y,c.right*s+x,c.bottom*s+y))
    private fun effective(f:StageLiveSurfaceFlightGeometry.MirrorFrame,r:StageEndpointContentMapping.ContentMap)=
        m.compose(map(f.scale,f.x,f.y),r)
    private fun heldMap(f:StageHeldEndpointPolicy.Fit,r:StageEndpointContentMapping.ContentMap)=
        m.compose(map(f.scale,f.x,f.y),r)

    @Test fun rejectedProductionCanvasCounterexampleKeepsCoordinateOnlyRoute() {
        // Exact device #174 crop and affine, not a same-width fixture.
        val small=StageLiveSurfaceFlightGeometry.Crop(0,0,720,1120)
        val root=map(1f,0f,0f)
        val target=map(.7649371f,299f,438.79712f)
        val main=box(299f,439f,1272f,2559f)
        val slot=box(14f,965f,285f,1387f)
        val first=checkNotNull(StageAdaptiveCoveredRailRoute.frame(slot,small))
        val rawEnd=checkNotNull(StageAdaptiveCoveredRailRoute.frame(main,small))
        val broken=frame(small,target.scale,target.x,target.y)
        // Even a zero affine error is consistent with a catastrophic hole.
        assertEquals(0f,m.mapError(target,effective(broken,root),small),.001f)
        assertTrue(main.bottom-broken.box.bottom>1260f)
        assertTrue(broken.box.width<552f)
        assertFalse(m.sameLocalCanvas(small,tall))
        assertFalse(m.sameLocalCanvas(tall,small))
        assertNull(m.anchor(root,small,small,tall,target,main,first,first,rawEnd))
        val reverseStart=checkNotNull(StageAdaptiveCoveredRailRoute.frame(main,tall))
        val reverseEnd=checkNotNull(StageAdaptiveCoveredRailRoute.frame(slot,tall))
        assertNull(m.anchor(root,tall,tall,small,
            map(.37638888f,14f,965.2222f),slot,reverseStart,reverseStart,reverseEnd))
        // Geometry-only reference: the existing renderer can cover both roles
        // using its own producer canvas. Real relayout remains unproven.
        assertTrue(StageLiveSurfaceFlightGeometry.endpointGapPx(main,rawEnd.box)<=2f)
        assertTrue(StageLiveSurfaceFlightGeometry.endpointGapPx(slot,reverseEnd.box)<=2f)
    }

    @Test fun equalPixelsDoNotAuthorizeIdentityMappingAcrossDensityDomains() {
        assertTrue(m.sameLocalCanvas(tall,tall))
        assertFalse(m.sameKnownDensity(320,512))
        assertFalse(m.sameKnownDensity(512,320))
        assertFalse(m.sameKnownDensity(240,512))
        assertFalse(m.sameKnownDensity(0,0))
        assertFalse(m.sameKnownDensity(-1,-1))
        assertFalse(m.sameKnownDensity(512,0))
        assertTrue(m.sameKnownDensity(512,512))
    }

    @Test fun equalCanvasStillRequiresIndependentCoverage() {
        val root=map(1f,0f,0f)
        val target=map(.75f,318f,460f)
        val bounds=frame(tall,target.scale,target.x,target.y).box
        val start=frame(tall,.19654088f,34f,1415.6533f)
        val end=frame(tall,target.scale,target.x,target.y)
        val exact=checkNotNull(m.anchor(root,tall,tall,tall,target,bounds,start,start,end))
        assertTrue(m.sameLocalCanvas(tall,tall))
        assertTrue(m.fullEndpointCoverage(exact))
        // Same local crop is necessary but not sufficient if the actual route
        // or source-root crop cannot cover the intended viewport.
        val shortened=frame(StageLiveSurfaceFlightGeometry.Crop(0,0,1272,1800),
            target.scale,target.x,target.y)
        assertNull(m.anchor(root,tall,tall,tall,target,bounds,start,start,shortened))
        assertFalse(m.sameLocalCanvas(StageLiveSurfaceFlightGeometry.Crop(1,0,1273,2772),
            StageLiveSurfaceFlightGeometry.Crop(1,0,1273,2772)))
        assertFalse(m.sameLocalCanvas(StageLiveSurfaceFlightGeometry.Crop(0,0,1,1),
            StageLiveSurfaceFlightGeometry.Crop(0,0,1,1)))
    }

    @Test fun recordedSideAndTopPromotionRejectsUnprovenLocalDomain() {
        val cases=listOf(floatArrayOf(.75f,318f,460f,.8259833f,269.67462f),
            floatArrayOf(.54256856f,290.9264f,1156f,.59753674f,255.96664f))
        for(c in cases) {
            val target=map(c[0],c[1],c[2])
            val viewport=frame(tall,target.scale,target.x,target.y).box
            val first=frame(short,.19654088f,34f,1415.6533f)
            val rawEnd=frame(StageLiveSurfaceFlightGeometry.Crop(59,0,1213,2517),c[3],c[4],c[2])
            val root=map(1f,0f,0f)
            assertFalse(m.sameLocalCanvas(short,tall))
            assertNull(m.anchor(root,short,short,tall,target,viewport,first,first,rawEnd))
            assertTrue(viewport.bottom-frame(short,target.scale,target.x,target.y).box.bottom>130f)
            assertTrue(m.mapError(map(rawEnd.scale,rawEnd.x,rawEnd.y),target,short)>100f)
        }
    }

    @Test fun recordedDemotionRejectsUnprovenLocalDomain() {
        val target=map(.19654088f,34f,1415.6533f)
        val viewport=frame(short,target.scale,target.x,target.y).box
        val first=frame(tall,.75f,318f,460f)
        val rawEnd=frame(StageLiveSurfaceFlightGeometry.Crop(0,124,1272,2647),.19654088f,34f,1390.6926f)
        val root=map(1f,0f,0f)
        assertFalse(m.sameLocalCanvas(tall,short))
        assertNull(m.anchor(root,tall,tall,short,target,viewport,first,first,rawEnd))
        assertTrue(m.mapError(map(rawEnd.scale,rawEnd.x,rawEnd.y),target,short)>24f)
    }

    @Test fun retainedRootRebaseDoesNotApplyProducerTransformTwice() {
        val target=map(.75f,318f,460f)
        val root=map(1f,0f,0f)
        val viewport=frame(tall,target.scale,target.x,target.y).box
        val first=frame(tall,.19654088f,34f,1415.6533f)
        val end=frame(tall,target.scale,target.x,target.y)
        val a=checkNotNull(m.anchor(root,tall,tall,tall,target,viewport,first,first,end))
        val changed=map(.75f,318f,460f)
        val h=checkNotNull(m.held(a,tall,changed))
        assertEquals(1f,h.scale,0f)
        assertEquals(0f,h.x,.001f);assertEquals(0f,h.y,.001f)
        assertEquals(0f,m.mapError(target,heldMap(h,changed),tall),.001f)
        // Neither requested metadata extent nor zero affine error licenses
        // a new partial or expanded canvas.
        assertNull(m.held(a,short,changed))
        assertNull(m.held(a,StageLiveSurfaceFlightGeometry.Crop(0,0,1272,3000),changed))
        assertNull(m.held(a,StageLiveSurfaceFlightGeometry.Crop(2000,0,3000,1000),changed))
        assertNull(m.held(a,tall,map(Float.NaN,0f,0f)))
        val detached=checkNotNull(m.held(a,tall,root))
        for((x,y) in listOf(200f to 400f,800f to 1800f)) {
            val before=effective(a.terminal,root)
            val after=heldMap(detached,root)
            assertEquals(before.x+before.scale*x,after.x+after.scale*x,.01f)
            assertEquals(before.y+before.scale*y,after.y+after.scale*y,.01f)
        }
    }

    @Test fun temporaryContentReadUsesOnlyExistingHandoffDeadline() {
        val pending=StageEndpointContentMapping.DestinationRead.PENDING
        val ready=StageEndpointContentMapping.DestinationRead.READY
        val rejected=StageEndpointContentMapping.DestinationRead.REJECTED
        val wait=StageEndpointContentMapping.DestinationHandoff.WAIT
        val success=StageEndpointContentMapping.DestinationHandoff.READY
        val expired=StageEndpointContentMapping.DestinationHandoff.EXPIRED
        val deny=StageEndpointContentMapping.DestinationHandoff.REJECTED
        assertEquals(wait,m.destinationHandoff(0,100,true,true,true,pending))
        assertEquals(wait,m.destinationHandoff(16,100,true,true,true,pending))
        assertEquals(success,m.destinationHandoff(32,100,true,true,true,ready))
        assertEquals(wait,m.destinationHandoff(99,100,true,true,true,pending))
        assertEquals(expired,m.destinationHandoff(100,100,true,true,true,pending))
        assertEquals(deny,m.destinationHandoff(16,100,true,true,true,rejected))
        assertEquals(wait,m.destinationHandoff(16,100,true,false,true,ready))
        assertEquals(wait,m.destinationHandoff(16,100,true,true,false,ready))
        assertEquals(expired,m.destinationHandoff(100,100,false,true,true,ready))
        assertEquals(100L,StageConcurrentNativeTrialPolicy.nextHandoffCheck(99L,100L))
    }

    @Test fun correctionCannotExpandTheExistingRouteFootprint() {
        val target=map(.75f,318f,460f)
        val first=frame(tall,.19654088f,34f,1415.6533f)
        val end=frame(tall,.7501f,317.9f,459.9f)
        val bounds=frame(tall,.75f,318f,460f).box
        val a=checkNotNull(m.anchor(map(1f,0f,0f),tall,tall,tall,target,bounds,first,first,end))
        assertEquals(first,m.flight(first,a,0f))
        for(i in 1..999) {
            val t=i/1000f
            val s=first.scale+(end.scale-first.scale)*t
            val x=first.x+(end.x-first.x)*t
            val y=first.y+(end.y-first.y)*t
            val raw=frame(tall,s,x,y)
            val f=checkNotNull(m.flight(raw,a,t))
            assertTrue(f.box.left>=raw.box.left-.01f && f.box.top>=raw.box.top-.01f &&
                f.box.right<=raw.box.right+.01f && f.box.bottom<=raw.box.bottom+.01f)
            assertTrue(f.crop.left>=0 && f.crop.top>=0 && f.crop.right<=1272 && f.crop.bottom<=2772)
        }
        val wrong=map(target.scale,target.x,target.y+30f)
        assertEquals(30f,m.mapError(target,wrong,short),.001f)
        val nonuniform=box(0f,0f,250f,600f)
        assertNull(m.slotMap(short,nonuniform))
    }
}

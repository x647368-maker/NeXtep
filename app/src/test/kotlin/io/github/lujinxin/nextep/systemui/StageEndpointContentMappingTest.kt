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

    @Test fun recordedSideAndTopPromotionRemoveTenPercentCoverRefit() {
        val cases=listOf(floatArrayOf(.75f,318f,460f,.8259833f,269.67462f),
            floatArrayOf(.54256856f,290.9264f,1156f,.59753674f,255.96664f))
        for(c in cases) {
            val target=map(c[0],c[1],c[2])
            val viewport=frame(tall,target.scale,target.x,target.y).box
            val first=frame(short,.19654088f,34f,1415.6533f)
            val rawEnd=frame(StageLiveSurfaceFlightGeometry.Crop(59,0,1213,2517),c[3],c[4],c[2])
            val root=map(1f,0f,0f)
            val a=checkNotNull(m.anchor(root,short,short,tall,target,viewport,first,first,rawEnd))
            val terminal=checkNotNull(m.flight(rawEnd,a,1f))
            val held=checkNotNull(m.held(a,tall,root))
            assertEquals(target.scale,terminal.scale,0f)
            assertEquals(target.x,terminal.x,0f);assertEquals(target.y,terminal.y,0f)
            assertEquals(0f,m.mapError(effective(terminal,root),heldMap(held,root),short),.001f)
            assertEquals(0f,m.mapError(target,effective(terminal,root),short),.001f)
            // Compare two distinguishable local marker coordinates and their
            // separation, not the moving card's bounding box.
            val before=effective(terminal,root)
            val after=heldMap(held,root)
            for((x,y) in listOf(200f to 400f,800f to 1800f)) {
                assertEquals(before.x+before.scale*x,after.x+after.scale*x,.01f)
                assertEquals(before.y+before.scale*y,after.y+after.scale*y,.01f)
                assertEquals(target.x+target.scale*x,after.x+after.scale*x,.01f)
                assertEquals(target.y+target.scale*y,after.y+after.scale*y,.01f)
            }
            assertEquals(600f*target.scale,600f*before.scale,.01f)
            assertEquals(1400f*target.scale,1400f*after.scale,.01f)
            assertTrue(terminal.crop.bottom<=2517)
            assertTrue(viewport.bottom-terminal.box.bottom>130f)
            // The metadata alone did not prove the expanded producer buffer.
            // Never expose requested extra rows during the held stage.
            assertTrue(held.bottom<=2517 && held.bottom>=2516)
            assertTrue(m.mapError(map(rawEnd.scale,rawEnd.x,rawEnd.y),target,short)>100f)
        }
    }

    @Test fun recordedDemotionUsesSlotCanvasAnchorNotCenteredTallCrop() {
        val target=map(.19654088f,34f,1415.6533f)
        val viewport=frame(short,target.scale,target.x,target.y).box
        val first=frame(tall,.75f,318f,460f)
        val rawEnd=frame(StageLiveSurfaceFlightGeometry.Crop(0,124,1272,2647),.19654088f,34f,1390.6926f)
        val root=map(1f,0f,0f)
        val a=checkNotNull(m.anchor(root,tall,tall,short,target,viewport,first,first,rawEnd))
        val terminal=checkNotNull(m.flight(rawEnd,a,1f))
        val held=checkNotNull(m.held(a,short,root))
        assertEquals(0f,m.mapError(effective(terminal,root),heldMap(held,root),short),.001f)
        assertEquals(0f,m.mapError(effective(terminal,root),target,short),.001f)
        assertEquals(target.y,terminal.y,0f)
        assertTrue(terminal.crop.top<=2);assertTrue(terminal.crop.bottom<=2517)
        assertTrue(m.mapError(map(rawEnd.scale,rawEnd.x,rawEnd.y),target,short)>24f)
    }

    @Test fun retainedRootRebaseDoesNotApplyProducerTransformTwice() {
        val target=map(.75f,318f,460f)
        val root=map(1f,0f,0f)
        val viewport=frame(tall,target.scale,target.x,target.y).box
        val first=frame(short,.19654088f,34f,1415.6533f)
        val end=frame(StageLiveSurfaceFlightGeometry.Crop(59,0,1213,2517),.8259833f,269.67462f,460f)
        val a=checkNotNull(m.anchor(root,short,short,tall,target,viewport,first,first,end))
        val changed=map(.75f,318f,460f)
        val h=checkNotNull(m.held(a,tall,changed))
        assertEquals(1f,h.scale,0f)
        assertEquals(0f,h.x,.001f);assertEquals(0f,h.y,.001f)
        assertEquals(0f,m.mapError(target,heldMap(h,changed),short),.001f)
        val partial=checkNotNull(m.held(a,short,changed))
        assertEquals(0f,m.mapError(target,heldMap(partial,changed),short),.001f)
        assertEquals(partial,h) // larger requested crop is not a buffer receipt
        val intermediate=checkNotNull(m.held(a,StageLiveSurfaceFlightGeometry.Crop(0,0,1272,2000),changed))
        assertTrue(intermediate.bottom<h.bottom)
        assertEquals(0f,m.mapError(target,heldMap(intermediate,changed),
            StageLiveSurfaceFlightGeometry.Crop(0,0,1272,2000)),.001f)
        assertNull(m.held(a,StageLiveSurfaceFlightGeometry.Crop(2000,0,3000,1000),changed))
        assertNull(m.held(a,tall,map(Float.NaN,0f,0f)))
    }

    @Test fun correctionCannotExpandTheExistingRouteFootprint() {
        val target=map(.75f,318f,460f)
        val first=frame(short,.19654088f,34f,1415.6533f)
        val end=frame(StageLiveSurfaceFlightGeometry.Crop(59,0,1213,2517),.8259833f,269.67462f,460f)
        val bounds=frame(tall,.75f,318f,460f).box
        val a=checkNotNull(m.anchor(map(1f,0f,0f),short,short,tall,target,bounds,first,first,end))
        assertEquals(first,m.flight(first,a,0f))
        for(i in 1..999) {
            val t=i/1000f
            val s=first.scale+(end.scale-first.scale)*t
            val x=first.x+(end.x-first.x)*t
            val y=first.y+(end.y-first.y)*t
            val raw=frame(short,s,x,y)
            val f=checkNotNull(m.flight(raw,a,t))
            assertTrue(f.box.left>=raw.box.left-.01f && f.box.top>=raw.box.top-.01f &&
                f.box.right<=raw.box.right+.01f && f.box.bottom<=raw.box.bottom+.01f)
            assertTrue(f.crop.left>=0 && f.crop.top>=0 && f.crop.right<=1272 && f.crop.bottom<=2517)
        }
        val wrong=map(target.scale,target.x,target.y+30f)
        assertEquals(30f,m.mapError(target,wrong,short),.001f)
        val nonuniform=box(0f,0f,250f,600f)
        assertNull(m.slotMap(short,nonuniform))
    }
}

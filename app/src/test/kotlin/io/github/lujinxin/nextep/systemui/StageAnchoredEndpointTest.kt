package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Box
import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Crop
import io.github.x647368maker.atomicworkbench.systemui.StageHeldEndpointPolicy.ContentMap
import io.github.x647368maker.atomicworkbench.systemui.StageHeldEndpointPolicy.ContentAnchor
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class StageAnchoredEndpointTest {
    private val p=StageHeldEndpointPolicy
    private val g=StageLiveSurfaceFlightGeometry
    private val old=Crop(0,0,1272,2517)
    private val next=Crop(0,0,1272,2772)
    private val main=Box(318f,460f,1272f,2539f)
    private val slot=Box(20f,300f,270f,795f)
    private val target=ContentMap(.75f,318f,460f)
    private fun contained(f:StageLiveSurfaceFlightGeometry.MirrorFrame,b:Box) {
        assertTrue(f.box.left>=b.left-.01f && f.box.top>=b.top-.01f
            && f.box.right<=b.right+.01f && f.box.bottom<=b.bottom+.01f)
    }
    @Test fun oldAndNewBuffersKeepSameLocalContentMap() {
        val a=checkNotNull(p.anchoredFit(main,old,p.rootMap(1f,0f,0f,true),target))
        val b=checkNotNull(p.anchoredFit(main,next,p.rootMap(.75f,318f,460f,true),target))
        assertEquals(.75f,a.scale,0f);assertEquals(a.scale,b.scale,0f)
        assertEquals(a.x,b.x,0f);assertEquals(a.y,b.y,0f)
        assertEquals(2517,a.bottom);assertEquals(2772,b.bottom)
        assertEquals(618f,a.x+400*a.scale,0f)
        assertEquals(1060f,b.y+800*b.scale,0f)
    }
    @Test fun retainedRebaseCompensatesRootNotContent() {
        val root=p.rootMap(.75f,318f,460f,false)
        val b=checkNotNull(p.anchoredFit(main,next,root,target))
        assertEquals(1f,b.scale,0f);assertEquals(0f,b.x,0f);assertEquals(0f,b.y,0f)
        assertEquals(618f,(400f*root.scale+root.x)*b.scale+b.x,0f)
        assertEquals(1060f,(800f*root.scale+root.y)*b.scale+b.y,0f)
        val changed=p.rootMap(.9f,80f,100f,false)
        val c=checkNotNull(p.anchoredFit(main,next,changed,target))
        assertEquals(618f,(400f*changed.scale+changed.x)*c.scale+c.x,.001f)
        assertEquals(1060f,(800f*changed.scale+changed.y)*c.scale+c.y,.001f)
    }
    @Test fun coveredFlightArrivesAtTargetScaleNotOldCoverScale() {
        val rawEnd=checkNotNull(g.mirrorFrame(slot,main,old,1f,true))
        assertEquals(.8259833f,rawEnd.scale,.001f)
        val plan=ContentAnchor(ContentMap(1f,0f,0f),old,target,main,rawEnd)
        assertTrue(p.terminalFits(plan))
        var last:StageLiveSurfaceFlightGeometry.MirrorFrame?=null
        for(i in 0..1000) {
            val f=i/1000f
            val raw=checkNotNull(g.mirrorFrame(slot,main,old,f,true))
            val adjusted=checkNotNull(p.anchoredFlight(raw,plan,f))
            contained(adjusted,raw.box)
            last?.let { assertTrue(abs(adjusted.scale-it.scale)<.01f) }
            last=adjusted
        }
        assertEquals(.75f,last!!.scale,.00001f)
        assertEquals(318f,last!!.x,.00001f)
        assertEquals(460f,last!!.y,.00001f)
    }
    @Test fun invalidOrEmptyMappingRefused() {
        assertNull(p.anchoredFit(main,next,ContentMap(0f,0f,0f),target))
        assertNull(p.anchoredFit(main,next,ContentMap(1f,0f,0f),ContentMap(.75f,1e9f,1e9f)))
        assertNull(p.anchoredFit(main,Crop(0,0,1,1),ContentMap(1f,0f,0f),target))
    }
}

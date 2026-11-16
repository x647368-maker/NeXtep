package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.min

class StageSharedSnapshotRouteTest {
    private fun b(l:Float,t:Float,r:Float,bt:Float)=StageSnapshotMotion.Box(l,t,r,bt)
    @Test fun mixedAspectUsesGpuPlanWithoutStretchOrEndpointJump(){
        val screen=b(0f,0f,1272f,2772f)
        val main=b(318f,460f,1272f,2539f)
        val slots=(0..2).map{val y=539f+it*584f;b(18f,y,300f,y+558f)}
        for(small in slots){
            val others=slots.filter{it!=small}
            val actual=checkNotNull(StageSnapshotMotion.sharedFlights(small,main,1272,2517,954,2079,screen,others){true})
            assertTrue(actual.usesSharedClock)
            val plan=checkNotNull(actual.up.sharedPlan)
            for((flight,from,to) in listOf(Triple(actual.up,small,main),Triple(actual.down,main,small))){
                assertEquals(checkNotNull(StageSnapshotMotion.flight(from,to,flight.width,flight.height)).start,flight.start)
                assertEquals(checkNotNull(StageSnapshotMotion.flight(from,to,flight.width,flight.height)).end,flight.end)
            }
            for(i in 1..999){
                val t=i/1000f;val frame=plan.at(t)
                for((f,v) in listOf(actual.up to frame.incoming,actual.down to frame.outgoing)){
                    val pose=f.pose(t)
                    assertEquals(v.cx,pose.cx,.001f);assertEquals(v.cy,pose.cy,.001f)
                    assertEquals(min(v.width/f.width,v.height/f.height),pose.scale,.00001f)
                    assertTrue(pose.scale>0f && pose.scale.isFinite())
                }
            }
        }
    }
    @Test fun rejectedRouteDoesNotDisableLegacyMotion(){
        val screen=b(0f,0f,1272f,2772f);val main=b(318f,460f,1272f,2539f)
        val small=b(18f,539f,300f,1097f)
        assertNull(StageSnapshotMotion.sharedFlights(small,main,1272,2517,954,2079,screen,emptyList()){false})
        val old=checkNotNull(StageSnapshotMotion.pairedFlights(small,main,1272,2517,954,2079,screen,emptyList()))
        assertFalse(old.usesSharedClock)
        assertEquals(small.cx,old.up.start.cx,0f);assertEquals(main.cx,old.up.end.cx,0f)
    }
}

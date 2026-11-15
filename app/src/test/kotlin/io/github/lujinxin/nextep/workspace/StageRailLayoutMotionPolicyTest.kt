package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test

class StageRailLayoutMotionPolicyTest {
    @Test fun physicalAxesAndStaleCanvasGuard() {
        for(same in listOf(false,true)) for(changed in listOf(false,true))
            for(horizontal in listOf(false,true)) for(positive in listOf(false,true))
                for(distance in listOf(-1f,0f,Float.NaN,Float.POSITIVE_INFINITY,1f,10f,28f,112f))
                    for(duration in listOf(-1L,0L,200L,220L)) {
                        val plan = StageRailLayoutMotionPolicy.entry(same,changed,horizontal,positive,distance,duration)
                        val allowed = same && changed && distance.isFinite() && distance > 0 && duration > 0
                        assertEquals(allowed, plan != null)
                        if(plan != null) {
                            assertEquals(duration,plan.durationMs)
                            val axis = if(horizontal) plan.dy else plan.dx
                            assertEquals(distance,kotlin.math.abs(axis),0f)
                            assertEquals(positive,axis > 0)
                            assertEquals(0f,if(horizontal) plan.dx else plan.dy,0f)
                        }
                    }
    }
}

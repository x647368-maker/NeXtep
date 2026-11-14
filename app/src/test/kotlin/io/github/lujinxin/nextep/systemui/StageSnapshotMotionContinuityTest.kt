package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageSnapshotMotionContinuityTest {
    private fun box(l:Float,t:Float,r:Float,b:Float)=StageSnapshotMotion.Box(l,t,r,b)

    @Test fun outgoingCardContractsAheadOfIncomingGrowthToProtectRailVisibility(){
        val slot=box(12f,420f,250f,900f)
        val main=box(270f,320f,1070f,2250f)
        val pair=checkNotNull(StageSnapshotMotion.pairedFlights(
            slot,main,1080,2354,2354,1080,box(0f,0f,1080f,2354f),emptyList()))
        for(bias in listOf(0f,.35f,.70f)){
            val up=pair.up.copy(sizeBias=bias)
            val down=pair.down.copy(sizeBias=bias)
            val p=.2f
            val incomingGrowth=(up.pose(p).scale-up.start.scale)/(up.end.scale-up.start.scale)
            val outgoingShrink=(down.start.scale-down.pose(p).scale)/(down.start.scale-down.end.scale)
            assertTrue("bias=$bias outgoing=$outgoingShrink incoming=$incomingGrowth",
                outgoingShrink>incomingGrowth)
        }
    }

    @Test fun bothCardsRemainMonotonicAcrossScaleBiases(){
        val slot=box(12f,420f,250f,900f)
        val main=box(270f,320f,1070f,2250f)
        val pair=checkNotNull(StageSnapshotMotion.pairedFlights(
            slot,main,1080,2354,2354,1080,box(0f,0f,1080f,2354f),emptyList()))
        for(bias in listOf(0f,.35f,.70f)){
            for(f in listOf(pair.up.copy(sizeBias=bias),pair.down.copy(sizeBias=bias))){
                var prior=f.pose(0f).scale
                for(i in 1..200){
                    val now=f.pose(i/200f).scale
                    if(f.growing)assertTrue(now>=prior-1e-6f) else assertTrue(now<=prior+1e-6f)
                    prior=now
                }
            }
        }
    }

    @Test fun bothCardsKeepExactScaleEndpointsAcrossSizeBiases(){
        val slot=box(12f,420f,250f,900f)
        val main=box(270f,320f,1070f,2250f)
        val pair=checkNotNull(StageSnapshotMotion.pairedFlights(
            slot,main,1080,2354,2354,1080,box(0f,0f,1080f,2354f),emptyList()))
        for(bias in listOf(0f,.35f,.70f)){
            for(f in listOf(pair.up.copy(sizeBias=bias),pair.down.copy(sizeBias=bias))){
                assertEquals(f.start,f.pose(0f))
                assertEquals(f.end,f.pose(1f))
            }
        }
    }
}

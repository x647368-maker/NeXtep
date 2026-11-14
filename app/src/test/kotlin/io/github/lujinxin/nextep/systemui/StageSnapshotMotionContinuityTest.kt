package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StageSnapshotMotionContinuityTest {
    private fun box(l:Float,t:Float,r:Float,b:Float)=StageSnapshotMotion.Box(l,t,r,b)

    @Test fun incomingAndOutgoingCardsUseTheSameNormalizedScaleProgress(){
        val slot=box(12f,420f,250f,900f)
        val main=box(270f,320f,1070f,2250f)
        val screen=box(0f,0f,1080f,2354f)
        val pair=checkNotNull(StageSnapshotMotion.pairedFlights(
            slot,main,1080,2354,2354,1080,screen,emptyList()))
        for(bias in listOf(0f,.35f,.70f)){
            val up=pair.up.copy(sizeBias=bias)
            val down=pair.down.copy(sizeBias=bias)
            for(i in 0..100){
                val p=i/100f
                val a=up.pose(p).scale
                val b=down.pose(p).scale
                val upProgress=(a-up.start.scale)/(up.end.scale-up.start.scale)
                val downProgress=(down.start.scale-b)/(down.start.scale-down.end.scale)
                assertEquals("bias=$bias p=$p",upProgress,downProgress,.00002f)
            }
        }
    }

    @Test fun scaleVelocityApproachesZeroAtBothEndpoints(){
        val flight=checkNotNull(StageSnapshotMotion.flight(
            box(12f,420f,250f,900f),box(270f,320f,1070f,2250f),1080,2354))
        val first=abs(flight.pose(.01f).scale-flight.pose(0f).scale)
        val middle=abs(flight.pose(.51f).scale-flight.pose(.50f).scale)
        val last=abs(flight.pose(1f).scale-flight.pose(.99f).scale)
        assertTrue("scale should not jump at start: $first vs $middle",first<middle*.25f)
        assertTrue("scale should not jump at end: $last vs $middle",last<middle*.25f)
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
}

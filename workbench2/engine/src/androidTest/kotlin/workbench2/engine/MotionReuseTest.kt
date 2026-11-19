package workbench2.engine

import org.junit.Assert.*
import org.junit.Test
import workbench2.motion.*

class MotionReuseTest {
    @Test fun unchangedCardsReusePosesWithoutChangingGeometryOrOrder() {
        var checks=0
        for(n in 2..5) for(mode in 0..1) for(side in 1 until n) {
            val scene=Scene((0 until n).map { Owner(100+it,0,1000L+it,1,Canvas(100+it*37,240+it*21,440,1)) },
                (0 until n).map { i ->
                    if(i==0) Box(-0.0,0.0,900.0,1200.0)
                    else if(mode==0) Box(960.0,(i-1)*300.0,230.0,260.0)
                    else Box((i-1)*300.0,-0.0,260.0,230.0)
                },1)
            val flight=Flight(1,scene,side,24.0,12.0)
            val start=scene.owners.mapIndexed { i,o -> o to Pose.fit(scene.places[i],o.canvas,if(i==0)24.0 else 12.0) }.toMap()
            val target=scene.swap(side)
            val end=target.owners.mapIndexed { i,o -> o to Pose.fit(target.places[i],o.canvas,if(i==0)24.0 else 12.0) }.toMap()
            for(i in 0..1000) {
                val p=i/1000.0
                val expected=start.mapValues { (o,a) -> interpolate(a,end.getValue(o),p) }
                val actual=flight.sample(p)
                assertEquals(expected,actual)
                assertEquals(scene.owners,actual.keys.toList())
                checks++
            }
            val mid=flight.sample(.2)
            val next=flight.sample(.4)
            for(o in scene.owners.filter { it !in flight.changedOwners }) assertSame(mid[o],next[o])
            assertSame(flight.sample(0.0),flight.sample(0.0))
            assertSame(flight.sample(1.0),flight.sample(1.0))
            // Cached endpoint and changed-owner sets cannot be corrupted by an unsafe cast.
            try { (flight.sample(0.0) as MutableMap<Owner,Pose>).clear();fail("Cached start must be immutable") }
            catch (_: UnsupportedOperationException) {}
            try { (flight.sample(1.0) as MutableMap<Owner,Pose>).clear();fail("Cached end must be immutable") }
            catch (_: UnsupportedOperationException) {}
            try { (flight.changedOwners as MutableSet<Owner>).clear();fail("Changed owners must be immutable") }
            catch (_: UnsupportedOperationException) {}
            // Fresh middle frame is never the cached template or a shared mutable result.
            (mid as MutableMap<Owner,Pose>).clear()
            assertEquals(n,flight.sample(.2).size)
            assertEquals(next,flight.sample(.4))
        }
        assertEquals(20020,checks)
        val a=Owner(1,0,1,1,Canvas(100,200,160,1));val b=a.copy(task=2,token=2)
        val f=Flight(1,Scene(listOf(a,b),listOf(Box(0.0,0.0,100.0,200.0),Box(120.0,0.0,50.0,100.0)),1),1,24.0,12.0)
        for(p in listOf(Double.NaN,Double.POSITIVE_INFINITY,-.01,1.01)) {
            try { f.sample(p);fail("Invalid progress must fail") } catch (_: IllegalArgumentException) {}
        }
    }
}

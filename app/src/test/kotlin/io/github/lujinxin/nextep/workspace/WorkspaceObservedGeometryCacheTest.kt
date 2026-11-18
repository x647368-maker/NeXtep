package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WorkspaceObservedGeometryCacheTest {
    @Test fun beforeObservationUsesExistingSafeDirectRead() {
        val cache=WorkspaceObservedGeometryCache<String>()
        val reads=AtomicInteger()
        assertEquals("main:1",cache.getOrRead { "main:${reads.incrementAndGet()}" })
        assertEquals("main:2",cache.getOrRead { "main:${reads.incrementAndGet()}" })
        assertEquals(2,reads.get())
    }

    @Test fun fullyObservedInactiveWorkspaceNeverReadsSettingsFromWmCallback() {
        val cache=WorkspaceObservedGeometryCache<String>()
        cache.observe(null)
        val reads=AtomicInteger()
        repeat(2000) {
            assertNull(cache.getOrRead {
                reads.incrementAndGet()
                "wrong"
            })
        }
        assertEquals(0,reads.get())
        cache.observe("same-session-geometry")
        repeat(2000) {
            assertEquals("same-session-geometry",
                cache.getOrRead { error("Unexpected synchronous settings read") })
        }
        assertEquals(0,reads.get())
    }

    @Test fun publishedGeometryIsAtomicAndAlwaysReplacesPreviousSnapshot() {
        data class Geometry(val width:Int,val height:Int,val rotation:Int)
        val cache=WorkspaceObservedGeometryCache<Geometry>()
        val first=Geometry(1272,2772,0)
        val second=Geometry(2772,1272,1)
        cache.observe(first)
        assertSame(first,cache.getOrRead { null })
        cache.observe(second)
        assertSame(second,cache.getOrRead { null })
        cache.observe(null)
        assertNull(cache.getOrRead { throw AssertionError("Inactive is a loaded null") })
        cache.clear()
        assertEquals(first,cache.getOrRead { first })
    }

    @Test fun concurrentWmReaderSeesOnlyWholeImmutableSnapshots() {
        data class Geometry(val width:Int,val height:Int)
        val cache=WorkspaceObservedGeometryCache<Geometry>()
        cache.observe(Geometry(1272,2772))
        val started=CountDownLatch(1)
        val completed=CountDownLatch(1)
        val failures=AtomicInteger()
        val reader=Thread {
            started.countDown()
            repeat(10000) {
                val value=cache.getOrRead { error("unexpected fallback") }
                if(value!=null && value.width!=1272 && value.width!=2772)failures.incrementAndGet()
                if(value!=null && value.width==1272 && value.height!=2772)failures.incrementAndGet()
                if(value!=null && value.width==2772 && value.height!=1272)failures.incrementAndGet()
            }
            completed.countDown()
        }
        reader.start()
        assertTrue(started.await(3,TimeUnit.SECONDS))
        repeat(1000) {
            cache.observe(if(it%2==0)Geometry(2772,1272) else Geometry(1272,2772))
        }
        assertTrue(completed.await(3,TimeUnit.SECONDS))
        reader.join(3000)
        assertEquals(0,failures.get())
    }
}

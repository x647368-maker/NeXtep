package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageWallpaperCoverCacheTest {
    private class Resource:AutoCloseable {
        var closes=0
        override fun close(){closes++}
    }
    private val key=StageWallpaperCoverCache.Key(1272,2772,0)
    @Test fun missingCoverRecoveryIsOncePerGenerationAndNeverWhenReady() {
        val cache=StageWallpaperCoverCache<Resource>()
        assertTrue(cache.claimMissingRecovery(key,10))
        assertFalse(cache.claimMissingRecovery(key,10))
        assertFalse(cache.claimMissingRecovery(key.copy(rotation=1),10))
        val resource=Resource()
        cache.replace(key,resource)
        assertFalse(cache.claimMissingRecovery(key,11))
        cache.clear()
        assertEquals(1,resource.closes)
        assertTrue(cache.claimMissingRecovery(key,11))
        assertFalse(cache.claimMissingRecovery(key,11))
        cache.invalidateRecovery()
        assertTrue(cache.claimMissingRecovery(key,11))
        assertFalse(cache.claimMissingRecovery(key,11))
    }
    @Test fun retireWhileBorrowedClosesOnlyAfterLastRelease() {
        val cache=StageWallpaperCoverCache<Resource>()
        val old=Resource();val next=Resource()
        cache.replace(key,old)
        val a=checkNotNull(cache.acquire(key))
        val b=checkNotNull(cache.acquire(key))
        cache.replace(key,next)
        assertEquals(0,old.closes)
        a.close();a.close()
        assertEquals(0,old.closes)
        b.close()
        assertEquals(1,old.closes)
        val now=checkNotNull(cache.acquire(key))
        assertSame(next,now.resource)
        cache.clear();cache.clear()
        assertNull(cache.acquire(key))
        assertEquals(0,next.closes)
        now.close();now.close()
        assertEquals(1,next.closes)
    }
    @Test fun wrongPhysicalViewportCannotBorrowAndClearReleasesIdleFrame() {
        val cache=StageWallpaperCoverCache<Resource>()
        val resource=Resource()
        cache.replace(key,resource)
        assertNull(cache.acquire(key.copy(rotation=1)))
        assertNull(cache.acquire(key.copy(width=2772,height=1272)))
        cache.clearIfMismatched(key)
        assertEquals(0,resource.closes)
        cache.clearIfMismatched(key.copy(rotation=2))
        assertEquals(1,resource.closes)
        assertNull(cache.acquire(key))
    }
}

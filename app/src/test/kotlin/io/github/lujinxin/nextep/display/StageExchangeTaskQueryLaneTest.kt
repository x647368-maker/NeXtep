package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class StageExchangeTaskQueryLaneTest {
    @Test fun taskSnapshotQueryAndDecodeCanRunOffOwnerWhileCallbackReturnsToOwner(){
        val owner=Thread.currentThread()
        val callbacks=LinkedBlockingQueue<()->Unit>()
        val lane=StageExchangeBinderLane{callbacks.add(it)}
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        var snapshot:List<Int>?=null
        var callbackOnOwner=false
        try{
            assertTrue(lane.submit({
                check(Thread.currentThread()!==owner)
                entered.countDown()
                check(release.await(3,TimeUnit.SECONDS))
                snapshot=listOf(11,22,33)
            }){result->
                assertSame(owner,Thread.currentThread())
                result.getOrThrow()
                callbackOnOwner=true
            })
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertNull(snapshot)
            assertFalse(callbackOnOwner)
            assertFalse(lane.submit({error("query and mutation must serialize")}){})
            release.countDown()
            checkNotNull(callbacks.poll(3,TimeUnit.SECONDS)).invoke()
            assertEquals(listOf(11,22,33),snapshot)
            assertTrue(callbackOnOwner)
        }finally{release.countDown()}
    }
}

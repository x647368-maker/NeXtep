package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StageExchangeBinderLaneTest {
    @Test fun blockedBinderDoesNotBlockOwnerAndCannotStartSecondMutation(){
        val owner=Thread.currentThread()
        val callbacks=LinkedBlockingQueue<()->Unit>()
        val lane=StageExchangeBinderLane { callbacks.add(it) }
        val entered=CountDownLatch(1)
        val allowReturn=CountDownLatch(1)
        var completed=false
        try {
            assertTrue(lane.submit({
                check(Thread.currentThread()!==owner)
                entered.countDown()
                check(allowReturn.await(3,TimeUnit.SECONDS))
            }){result->
                assertSame(owner,Thread.currentThread())
                result.getOrThrow()
                completed=true
            })
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertFalse(completed)
            assertFalse(lane.submit({error("No concurrent mutation")}){error("No fake callback")})
            assertNull(callbacks.poll())
            allowReturn.countDown()
            checkNotNull(callbacks.poll(3,TimeUnit.SECONDS)).invoke()
            assertTrue(completed)
            assertNull(callbacks.poll())
        } finally { allowReturn.countDown() }
    }

    @Test fun realBinderErrorReturnsBeforeLaneMayAcceptNextMutation(){
        val callbacks=LinkedBlockingQueue<()->Unit>()
        val lane=StageExchangeBinderLane { callbacks.add(it) }
        val error=IllegalStateException("IPC rejected")
        var observed:Throwable?=null
        assertTrue(lane.submit({throw error}){observed=it.exceptionOrNull()})
        val callback=checkNotNull(callbacks.poll(3,TimeUnit.SECONDS))
        assertFalse(lane.submit({}){})
        callback.invoke()
        assertSame(error,observed)
        var secondCompleted=false
        assertTrue(lane.submit({}){it.getOrThrow();secondCompleted=true})
        checkNotNull(callbacks.poll(3,TimeUnit.SECONDS)).invoke()
        assertTrue(secondCompleted)
    }
}

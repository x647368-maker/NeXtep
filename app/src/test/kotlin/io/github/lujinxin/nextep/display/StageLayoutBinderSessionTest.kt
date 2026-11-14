package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Models owner-side integration of a real, blocked layout IPC and cancellation. */
class StageLayoutBinderSessionTest {
    private class Fixture(private val fail:Boolean=false) {
        val owner=Thread.currentThread()
        val queue=LinkedBlockingQueue<()->Unit>()
        val lane=StageExchangeBinderLane{queue.add(it)}
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        var valid=true
        var observed=0
        var mutations=0
        var rollback=0
        var result:Boolean?=null
        val session=ExchangeSession(
            19,400L,ExchangeSession.Origin(1,0,"main",0,"m"),
            ExchangeSession.Origin(2,0,"slot",7,"s"),
            {0L},{queue.add(it)},object:ExchangeSession.Driver{
                override val nonBlocking=true
                override fun promote()=error("sync")
                override fun demoteAndVerify()=error("sync")
                override fun restoreAndVerify(){rollback++}
                override fun promoteAsync(done:(Throwable?)->Unit){
                    check(valid)
                    check(lane.submit({
                        check(Thread.currentThread()!==owner)
                        mutations++
                        entered.countDown()
                        check(release.await(3,TimeUnit.SECONDS))
                        if(fail)error("layout rejected")
                    }){r->
                        check(Thread.currentThread()===owner)
                        done(runCatching{
                            r.getOrThrow()
                            check(valid)
                            observed++
                        }.exceptionOrNull())
                    })
                }
                override fun demoteAndVerifyAsync(done:(Throwable?)->Unit){done(null)}
            },{result=it})
        fun tick()=checkNotNull(queue.poll(3,TimeUnit.SECONDS)).invoke()
        fun begin(){
            session.start();tick()
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            assertEquals(ExchangeSession.Phase.PROMOTING,session.phase)
            assertEquals(0,observed)
            assertNull(result)
            assertFalse(lane.submit({error("parallel mutation")}){})
        }
        fun finish(){
            release.countDown();tick()
            repeat(4){if(queue.isNotEmpty())queue.remove().invoke()}
        }
    }
    @Test fun layoutObservationWaitsForOwnerReply(){
        val f=Fixture()
        try{
            f.begin();f.finish()
            assertEquals(1,f.observed);assertEquals(1,f.mutations)
            assertEquals(true,f.result);assertEquals(0,f.rollback)
        }finally{f.release.countDown()}
    }
    @Test fun staleLayoutReplyDoesNotObserveOrReleaseEarly(){
        val f=Fixture()
        try{
            f.begin();f.valid=false;f.session.cancel()
            assertEquals(0,f.rollback);assertNull(f.result)
            f.finish()
            assertEquals(0,f.observed);assertEquals(1,f.rollback)
            assertEquals(false,f.result)
        }finally{f.release.countDown()}
    }
    @Test fun failedLayoutReplyDoesNotAdvance(){
        val f=Fixture(true)
        try{
            f.begin();f.finish()
            assertEquals(0,f.observed);assertEquals(1,f.rollback)
            assertEquals(false,f.result)
        }finally{f.release.countDown()}
    }
}

package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class StageForegroundBinderSessionTest {
    private class Fixture(private val fails:Boolean=false) {
        val owner=Thread.currentThread()
        val queue=LinkedBlockingQueue<()->Unit>()
        val lane=StageExchangeBinderLane { queue.add(it) }
        val entered=CountDownLatch(1)
        val unblock=CountDownLatch(1)
        var frontCalls=0
        var demotes=0
        var rollbacks=0
        var result:Boolean?=null
        val session=ExchangeSession(
            13,400L,ExchangeSession.Origin(1,0,"main",0,"main"),
            ExchangeSession.Origin(2,0,"slot",7,"slot"),
            {0L},{queue.add(it)},object:ExchangeSession.Driver{
                override val nonBlocking=true
                override fun promote()=error("unexpected synchronous promotion")
                override fun demoteAndVerify()=error("unexpected synchronous demotion")
                override fun restoreAndVerify(){rollbacks++}
                override fun promoteAsync(done:(Throwable?)->Unit){
                    // A real move returns to owner, then the same serialized
                    // lane runs the synchronous foreground IPC.
                    check(lane.submit({}){move->
                        move.getOrThrow()
                        check(lane.submit({
                            check(Thread.currentThread()!==owner)
                            frontCalls++
                            entered.countDown()
                            check(unblock.await(3,TimeUnit.SECONDS))
                            if(fails)error("foreground IPC rejected")
                        }){front->done(front.exceptionOrNull())})
                    })
                }
                override fun demoteAndVerifyAsync(done:(Throwable?)->Unit){
                    demotes++
                    done(null)
                }
            },{result=it})
        fun tick()=checkNotNull(queue.poll(3,TimeUnit.SECONDS)).invoke()
        fun reachFront(){
            session.start();tick();tick()
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            assertEquals(ExchangeSession.Phase.PROMOTING,session.phase)
            assertNull(result)
            assertEquals(0,demotes)
            assertFalse(lane.submit({error("must serialize")}){})
        }
        fun finishFront(){unblock.countDown();tick();repeat(3){if(queue.isNotEmpty())queue.remove().invoke()}}
    }
    @Test fun demotionWaitsForActualForegroundReply(){
        val f=Fixture()
        try{
            f.reachFront()
            f.finishFront()
            assertEquals(true,f.result)
            assertEquals(1,f.frontCalls)
            assertEquals(1,f.demotes)
            assertEquals(0,f.rollbacks)
        }finally{f.unblock.countDown()}
    }
    @Test fun cancellationWaitsForForegroundReplyThenRollsBack(){
        val f=Fixture()
        try{
            f.reachFront()
            f.session.cancel()
            assertNull(f.result)
            assertEquals(0,f.rollbacks)
            f.finishFront()
            assertEquals(false,f.result)
            assertEquals(1,f.frontCalls)
            assertEquals(0,f.demotes)
            assertEquals(1,f.rollbacks)
        }finally{f.unblock.countDown()}
    }
    @Test fun foregroundFailureDoesNotDemote(){
        val f=Fixture(true)
        try{
            f.reachFront()
            f.finishFront()
            assertEquals(false,f.result)
            assertEquals(0,f.demotes)
            assertEquals(1,f.rollbacks)
        }finally{f.unblock.countDown()}
    }
}
